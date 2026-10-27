package io.github.prostoodin1.cameradeck;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Publish finalized copies; original camera backup is never removed. */
final class GalleryPublisher {
    static final String FOLDER=Environment.DIRECTORY_DCIM+"/Camera Deck/";
    static final ExecutorService worker=Executors.newSingleThreadExecutor();
    static final Set<String> inFlight=new HashSet<>();
    static volatile String state="",error="";
    static boolean enabled(Context c){return c.getSharedPreferences("deck",0).getBoolean("gallery",true);}
    static void recover(Context c,File directory){
        for(String name:new HashSet<>(c.getSharedPreferences("deck",0).getStringSet("galleryPending",Collections.emptySet())))if(CaptureStore.valid(name)&&new File(directory,name).isFile())enqueue(c,new File(directory,name));
    }
    static synchronized void journal(Context c,String name,boolean add){Set<String> pending=new HashSet<>(c.getSharedPreferences("deck",0).getStringSet("galleryPending",Collections.emptySet()));if(add)pending.add(name);else pending.remove(name);c.getSharedPreferences("deck",0).edit().putStringSet("galleryPending",pending).commit();}
    static void enqueue(Context context,File file){
        if(!enabled(context)||!CaptureStore.valid(file.getName()))return;
        Context c=context.getApplicationContext();synchronized(inFlight){if(!inFlight.add(file.getAbsolutePath()))return;}
        journal(c,file.getName(),true);
        worker.execute(()->{
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            state="Сохраняем в галерею";
            try{publish(c,file);journal(c,file.getName(),false);state="Галерея · Camera Deck";error="";}
            catch(Exception e){error="Галерея: "+String.valueOf(e.getMessage())+". Оригинал сохранён в приложении";state="Оригинал сохранён";}
            finally{synchronized(inFlight){inFlight.remove(file.getAbsolutePath());}}
        });
    }
    static String mime(String name){if(name.endsWith(".mp4"))return "video/mp4";if(name.endsWith(".png"))return "image/png";if(name.endsWith(".webp"))return "image/webp";if(name.endsWith(".dng"))return "image/x-adobe-dng";return "image/jpeg";}
    static void copy(InputStream in,OutputStream out)throws IOException{byte[] block=new byte[256*1024];int n;while((n=in.read(block))!=-1)out.write(block,0,n);out.flush();}
    static void publish(Context c,File file)throws Exception{
        if(!file.isFile()||file.length()==0)throw new IOException("Исходный файл недоступен");
        String mime=mime(file.getName());
        if(Build.VERSION.SDK_INT<29){
            if(c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED)throw new IOException("Разрешите сохранение в общую память");
            File dir=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),"Camera Deck");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Папка галереи недоступна");
            File target=new File(dir,file.getName());
            if(target.exists()){if(target.length()!=file.length())throw new IOException("В галерее уже есть другой файл с таким именем");}
            else{File partial=new File(dir,file.getName()+".partial");try(InputStream in=new FileInputStream(file);OutputStream out=new FileOutputStream(partial)){copy(in,out);}if(!partial.renameTo(target))throw new IOException("Не удалось завершить копию");}
            MediaScannerConnection.scanFile(c,new String[]{target.getAbsolutePath()},new String[]{mime},null);return;
        }
        ContentResolver resolver=c.getContentResolver();Uri collection=mime.startsWith("video")?MediaStore.Video.Media.EXTERNAL_CONTENT_URI:MediaStore.Images.Media.EXTERNAL_CONTENT_URI;Uri uri=null;
        String selection=MediaStore.MediaColumns.DISPLAY_NAME+"=? AND "+MediaStore.MediaColumns.RELATIVE_PATH+"=? AND "+MediaStore.MediaColumns.OWNER_PACKAGE_NAME+"=?";
        try(Cursor rows=resolver.query(collection,new String[]{MediaStore.MediaColumns._ID,MediaStore.MediaColumns.IS_PENDING},selection,new String[]{file.getName(),FOLDER,c.getPackageName()},null)){
            if(rows!=null&&rows.moveToFirst()){if(rows.getInt(1)==0)return;uri=ContentUris.withAppendedId(collection,rows.getLong(0));}
        }
        if(uri==null){ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,file.getName());values.put(MediaStore.MediaColumns.MIME_TYPE,mime);values.put(MediaStore.MediaColumns.RELATIVE_PATH,FOLDER);values.put(MediaStore.MediaColumns.IS_PENDING,1);uri=resolver.insert(collection,values);}
        if(uri==null)throw new IOException("Android не создал запись галереи");
        try{
            try(InputStream in=new FileInputStream(file);OutputStream out=resolver.openOutputStream(uri,"wt")){if(out==null)throw new IOException("Нет доступа к галерее");copy(in,out);}
            ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.IS_PENDING,0);if(resolver.update(uri,values,null,null)!=1)throw new IOException("Не подтверждено завершение копии");
        }catch(Exception e){try{resolver.delete(uri,null,null);}catch(Exception ignored){}throw e;}
    }
}
