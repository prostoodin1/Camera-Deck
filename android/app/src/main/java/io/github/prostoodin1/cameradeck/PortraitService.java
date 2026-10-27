package io.github.prostoodin1.cameradeck;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.media.MediaExtractor;
import android.net.Uri;
import android.os.*;
import androidx.media3.common.*;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.*;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.*;
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

/** Persistent offline jobs. Capture always has priority; camera originals are immutable. */
@android.annotation.SuppressLint("UnsafeOptInUsageError")
public final class PortraitService extends Service {
    static volatile PortraitService active;
    static volatile String state="",error="",lastFile="";
    final Handler main=new Handler(Looper.getMainLooper());
    final ExecutorService photos=Executors.newSingleThreadExecutor();
    Transformer transformer;File partial;boolean busy;volatile int generation;
    static synchronized JSONArray queue(Context c)throws JSONException{return new JSONArray(c.getSharedPreferences("deck",0).getString("portraitJobs","[]"));}
    static synchronized void save(Context c,JSONArray jobs){c.getSharedPreferences("deck",0).edit().putString("portraitJobs",jobs.toString()).putInt("portraitPendingCount",jobs.length()).commit();}
    static int pending(Context c){return c.getSharedPreferences("deck",0).getInt("portraitPendingCount",0);}
    static synchronized void enqueue(Context c,File input,float amount){
        if(amount<=0||!input.isFile()||!CaptureStore.valid(input.getName())||input.getName().endsWith(".dng"))return;
        try{
            JSONArray jobs=queue(c);if(jobs.length()>=20)throw new IOException("Очередь портретных копий заполнена");
            String ext=input.getName().endsWith(".mp4")?"mp4":"jpg";
            String name="CD_"+System.currentTimeMillis()+"_"+UUID.randomUUID().toString().substring(0,8)+"."+ext;
            jobs.put(new JSONObject().put("input",input.getName()).put("output",name).put("blur",amount));save(c,jobs);state="Размытие: копия в очереди · выключите камеру для обработки";
        }catch(Exception failure){error=String.valueOf(failure.getMessage())+". Исходник сохранён";}
    }
    static void startPending(Context c){if(pending(c)==0)return;try{c.startForegroundService(new Intent(c,PortraitService.class));}catch(Exception failure){error="Обработка не запущена: "+failure.getMessage()+". Откройте приложение; исходник сохранён";}}
    static void pauseForCamera(){PortraitService s=active;if(s!=null){s.generation++;if(s.transformer!=null){s.transformer.cancel();s.transformer=null;}state="Обработка приостановлена для камеры; очередь сохранена";s.stopSelf();}}
    synchronized void removeJob(String name)throws JSONException{JSONArray next=new JSONArray(),old=queue(this);for(int i=0;i<old.length();i++)if(!name.equals(old.getJSONObject(i).optString("output")))next.put(old.get(i));save(this,next);}
    Notification notification(String label){return new Notification.Builder(this,"portrait").setSmallIcon(android.R.drawable.ic_menu_edit).setContentTitle("Camera Deck · портретная копия").setContentText(label).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE)).build();}
    @Override public void onCreate(){super.onCreate();active=this;getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("portrait","Обработка снятых копий",NotificationManager.IMPORTANCE_LOW));Notification n=notification("Исходные фото и видео сохранены");if(Build.VERSION.SDK_INT>=35)startForeground(21,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);else if(Build.VERSION.SDK_INT>=29)startForeground(21,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);else startForeground(21,n);}
    @Override public int onStartCommand(Intent intent,int flags,int id){error="";next();return START_NOT_STICKY;}
    void next(){
        if(busy)return;
        if(CameraService.active!=null){state="Размытие ожидает выключения камеры";main.postDelayed(()->{if(active==this)next();},1000);return;}
        if(Build.VERSION.SDK_INT>=29&&getSystemService(PowerManager.class).getCurrentThermalStatus()>=3){state="Обработка отложена до остывания телефона";stopSelf();return;}
        try{
            JSONArray jobs=queue(this);if(jobs.length()==0){state="Портретные копии готовы";stopSelf();return;}
            JSONObject job=jobs.getJSONObject(0);String in=job.getString("input"),out=job.getString("output");
            if(!CaptureStore.valid(in)||!CaptureStore.valid(out))throw new IOException("Некорректная задача обработки");
            File folder=new File(getExternalFilesDir(null),"captures"),input=new File(folder,in),output=new File(folder,out);
            if(output.isFile()&&output.length()>0){complete(job,output);return;}
            if(!input.isFile()||input.length()==0){failed(job,"Исходный файл недоступен");return;}
            if(folder.getUsableSpace()<Math.max(256_000_000L,input.length()*2)){state="Недостаточно места для портретной копии; исходник сохранён";stopSelf();return;}
            busy=true;int epoch=++generation;partial=new File(folder,out+".portrait.partial");if(partial.exists()&&!partial.delete())throw new IOException("Не удалось убрать незавершённую копию");
            state="Размытие: обрабатываем отдельную копию";
            if(in.endsWith(".mp4"))video(job,input,output,epoch);else photo(job,input,output,epoch);
        }catch(Exception failure){error=String.valueOf(failure.getMessage())+". Исходники сохранены";stopSelf();}
    }
    static boolean audio(File file)throws IOException{MediaExtractor extractor=new MediaExtractor();try{extractor.setDataSource(file.getAbsolutePath());for(int i=0;i<extractor.getTrackCount();i++)if(extractor.getTrackFormat(i).getString("mime").startsWith("audio/"))return true;return false;}finally{extractor.release();}}
    void video(JSONObject job,File input,File output,int epoch)throws Exception{
        PortraitEffect effect=new PortraitEffect((float)job.getDouble("blur"));
        EditedMediaItem item=new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input))).setEffects(new Effects(Collections.emptyList(),List.of(effect))).build();
        transformer=new Transformer.Builder(this).setVideoMimeType(MimeTypes.VIDEO_H264).addListener(new Transformer.Listener(){
            @Override public void onCompleted(Composition composition,ExportResult result){
                if(epoch!=generation)return;transformer=null;
                try{if(effect.subjectFrames==0)throw new IOException("Не удалось выделить человека; копия не опубликована");if(audio(input)&&!audio(partial))throw new IOException("В копии отсутствует исходная аудиодорожка");if(!partial.renameTo(output))throw new IOException("Не удалось завершить копию");complete(job,output);}
                catch(Exception failure){failed(job,String.valueOf(failure.getMessage()));}
            }
            @Override public void onError(Composition composition,ExportResult result,ExportException failure){if(epoch!=generation)return;transformer=null;failed(job,String.valueOf(failure.getMessage()));}
        }).build();
        transformer.start(item,partial.getAbsolutePath());
    }
    void photo(JSONObject job,File input,File output,int epoch){
        final File target=partial;
        photos.execute(()->{
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);Segmenter segmenter=null;Bitmap image=null,processed=null;
            try{
                BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(input.getAbsolutePath(),options);int sample=1;while(Math.max(options.outWidth,options.outHeight)/sample>1920)sample*=2;options.inJustDecodeBounds=false;options.inSampleSize=sample;image=BitmapFactory.decodeFile(input.getAbsolutePath(),options);if(image==null)throw new IOException("Фото не декодируется");
                if(input.getName().endsWith(".jpg")){android.media.ExifInterface exif=new android.media.ExifInterface(input.getAbsolutePath());int orientation=exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1);Matrix matrix=new Matrix();if(orientation==6)matrix.postRotate(90);else if(orientation==3)matrix.postRotate(180);else if(orientation==8)matrix.postRotate(270);if(orientation==2)matrix.postScale(-1,1);else if(orientation==4)matrix.postScale(1,-1);else if(orientation==5){matrix.postRotate(90);matrix.postScale(-1,1);}else if(orientation==7){matrix.postRotate(270);matrix.postScale(-1,1);}if(!matrix.isIdentity()){Bitmap rotated=Bitmap.createBitmap(image,0,0,image.getWidth(),image.getHeight(),matrix,true);if(rotated!=image){image.recycle();image=rotated;}}}
                segmenter=Segmentation.getClient(new SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).enableRawSizeMask().build());
                Bitmap small=Bitmap.createScaledBitmap(image,Math.max(1,Math.round(image.getWidth()*Math.min(1,512f/Math.max(image.getWidth(),image.getHeight())))),Math.max(1,Math.round(image.getHeight()*Math.min(1,512f/Math.max(image.getWidth(),image.getHeight())))),true);
                SegmentationMask mask;try{mask=Tasks.await(segmenter.process(InputImage.fromBitmap(small,0)),10,TimeUnit.SECONDS);}finally{if(small!=image)small.recycle();}
                float[] confidence=new float[mask.getWidth()*mask.getHeight()];mask.getBuffer().order(ByteOrder.nativeOrder()).asFloatBuffer().get(confidence);double sum=0;for(float v:confidence)sum+=v;if(sum/confidence.length<.01||sum/confidence.length>.99)throw new IOException("Не удалось выделить человека");
                if(epoch!=generation)return;int w=image.getWidth(),h=image.getHeight();int[] pixels=new int[w*h];image.getPixels(pixels,0,w,0,0,w,h);int[] result=PortraitPixels.blend(pixels,w,h,confidence,mask.getWidth(),mask.getHeight(),(float)job.getDouble("blur"));processed=Bitmap.createBitmap(result,w,h,Bitmap.Config.ARGB_8888);
                try(OutputStream out=new FileOutputStream(target)){if(!processed.compress(Bitmap.CompressFormat.JPEG,95,out))throw new IOException("Не удалось сохранить JPEG");}
                if(epoch!=generation){target.delete();return;}if(!target.renameTo(output))throw new IOException("Не удалось завершить фото");main.post(()->{if(epoch==generation)complete(job,output);});
            }catch(Exception|OutOfMemoryError failure){main.post(()->{if(epoch==generation)failed(job,String.valueOf(failure.getMessage()));});}
            finally{if(segmenter!=null)segmenter.close();if(image!=null)image.recycle();if(processed!=null)processed.recycle();}
        });
    }
    void complete(JSONObject job,File output){try{removeJob(job.getString("output"));getSharedPreferences("deck",0).edit().putString("portraitSource:"+output.getName(),job.optString("input")).apply();lastFile=output.getName();GalleryPublisher.enqueue(this,output);state="Портретная копия готова · "+output.getName();busy=false;partial=null;main.post(this::next);}catch(Exception failure){error=failure.getMessage();stopSelf();}}
    void failed(JSONObject job,String message){error="Размытие: "+message+". Исходник сохранён";try{removeJob(job.optString("output"));}catch(Exception ignored){}if(partial!=null)partial.delete();partial=null;busy=false;main.post(this::next);}
    @Override public android.os.IBinder onBind(Intent intent){return null;}
    @Override public void onTimeout(int startId,int type){error="Android остановил длительную обработку; очередь и исходники сохранены";stopSelf();}
    @Override public void onDestroy(){generation++;main.removeCallbacksAndMessages(null);if(transformer!=null){transformer.cancel();transformer=null;}photos.shutdownNow();if(partial!=null)partial.delete();if(active==this)active=null;super.onDestroy();}
}
