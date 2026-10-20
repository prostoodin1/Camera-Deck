package io.github.prostoodin1.cameradeck;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import org.json.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

final class CameraEngine implements AutoCloseable {
    final Context context;
    final CameraManager manager;
    final HandlerThread thread = new HandlerThread("CameraDeck-camera");
    final Handler handler;
    final HandlerThread encoderThread=new HandlerThread("CameraDeck-preview-encoder");
    final Handler encoderHandler;
    final File mediaDir;
    final CaptureStore store;
    CaptureStore.Target recordingTarget;
    final Object frameReady=new Object();
    volatile boolean nativePreview;
    volatile int previewWidth,previewHeight;
    LocalOutput localOutput;
    final List<LocalOutput> retiredOutputs=new ArrayList<>();
    boolean localRejected;
    static final class LocalOutput {
        final android.graphics.SurfaceTexture texture;final Surface surface;
        LocalOutput(android.graphics.SurfaceTexture texture){this.texture=texture;surface=new Surface(texture);}
        void release(){surface.release();texture.release();}
    }
    void attachLocal(android.graphics.SurfaceTexture texture){handler.post(()->{
        if(closed||localOutput!=null&&localOutput.texture==texture)return;
        if(localOutput!=null){LocalOutput old=localOutput;retiredOutputs.add(old);if(recording&&request!=null&&session!=null)try{request.removeTarget(old.surface);request.addTarget(preview.getSurface());repeat();}catch(Exception e){fail(e);}}
        localOutput=new LocalOutput(texture);localRejected=false;
        if(recording)nativePreview=false;
        if(preview!=null)texture.setDefaultBufferSize(preview.getWidth(),preview.getHeight());
        if(ready&&!recording&&!photoPending)try{configure(false);}catch(Exception e){fail(e);}
    });}
    void detachLocal(android.graphics.SurfaceTexture texture){handler.post(()->{
        if(localOutput==null||localOutput.texture!=texture){texture.release();return;}
        LocalOutput old=localOutput;localOutput=null;nativePreview=false;retiredOutputs.add(old);
        try{if(ready&&!recording&&!photoPending)configure(false);else if(request!=null&&session!=null){request.removeTarget(old.surface);request.addTarget(preview.getSurface());repeat();}}catch(Exception e){fail(e);}
    });}
    final AudioEngine audio;
    final java.util.concurrent.ExecutorService photoWriter=java.util.concurrent.Executors.newSingleThreadExecutor();
    volatile String captureMode="photo", photoFormat="jpg";
    volatile int videoWidth=1920, videoHeight=1080, videoFps=30, photoWidth,photoHeight;
    volatile boolean sound=true;
    volatile JSONObject capabilities=new JSONObject();
    Image pendingRaw;
    TotalCaptureResult rawResult;
    long photoRequestId;
    volatile boolean photoSaving;
    final PowerPolicy power = new PowerPolicy();
    volatile String powerState = "opening";
    PowerPolicy.Plan appliedPlan;
    int appliedFps;
    int appliedDemand=-1;
    final Runnable powerTick = new Runnable() {
        public void run() {
            if (closed) return;
            try {
                if(recording && recorder!=null && !recorder.error.isEmpty()) { session.stopRepeating();session.abortCaptures();finishRecording(); }
                applyDemand(false);
            } catch (Exception e) { fail(e); }
            handler.postDelayed(this, 1000);
        }
    };
    volatile byte[] frame = new byte[0];
    volatile long frameTime, frameSequence;
    volatile String error = "", cameraId = "", lastFile = "";
    volatile boolean recording, ready;
    volatile int deviceRotation, rotation = 90;
    volatile int measuredIso;
    volatile long measuredExposure;
    CameraDevice camera;
    CameraCaptureSession session;
    CameraCharacteristics characteristics;
    volatile ImageReader preview;
    ImageReader photos;
    AvRecorder recorder;
    volatile LiveVideo live;
    volatile int liveUsers;
    volatile String liveError="";
    String liveProfile="balanced";
    boolean liveRejected;
    synchronized LiveVideo subscribeVideo()throws Exception{
        FutureTask<LiveVideo> task=new FutureTask<>(()->{
            if(!ready||photoPending||busy)throw new IllegalStateException("Камера занята");
            if(liveRejected)throw new IllegalStateException("Камера использует совместимый просмотр");
            if(live==null){
                JSONObject best=null;int bestRate=0;double score=Double.MAX_VALUE;
                int desired=liveProfile.equals("detail")?1920:1280,target=power.eco?30:60;
                JSONArray modes=capabilities.getJSONArray("videoModes");
                for(int i=0;i<modes.length();i++){JSONObject mode=modes.getJSONObject(i);int rate=0;JSONArray rates=mode.getJSONArray("fps");for(int j=0;j<rates.length();j++)if(rates.getInt(j)<=target)rate=Math.max(rate,rates.getInt(j));if(rate==0)continue;
                    android.media.MediaFormat f=AvRecorder.basicFormat(android.media.MediaFormat.MIMETYPE_VIDEO_AVC,mode.getInt("width"),mode.getInt("height"),rate,6_000_000);
                    if(new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).findEncoderForFormat(f)==null)continue;
                    double distance=Math.abs(mode.getInt("width")-desired)+Math.abs((double)mode.getInt("width")/mode.getInt("height")-16.0/9)*2000+(target-rate)*12;
                    if(distance<score){best=mode;bestRate=rate;score=distance;}
                }
                if(best==null)throw new IllegalStateException("H.264 предпросмотр недоступен");
                live=new LiveVideo(best.getInt("width"),best.getInt("height"),bestRate);liveError="";
                liveUsers++;try{configure(recording);}catch(Exception e){liveUsers--;live.close();live=null;throw e;}
            }else liveUsers++;
            live.sync();return live;
        });handler.post(task);return task.get(15,TimeUnit.SECONDS);
    }
    void unsubscribeVideo(LiveVideo source){handler.post(()->{if(source!=live)return;liveUsers=Math.max(0,liveUsers-1);try{applyDemand(true);}catch(Exception e){fail(e);}});}

    CaptureRequest.Builder request;
    long lastPreview;
    int iso;
    long exposure;
    float focus = -1;
    volatile float zoom=1,minZoom=1,maxZoom=1;
    volatile boolean autoZoom,tracking=true,faceFocus=true;
    volatile String framing="portrait",trackingError="";
    volatile JSONArray faces=new JSONArray();volatile long facesAt;
    volatile int afState;
    volatile float[] focusPoint;
    final FaceTracker tracker=new FaceTracker();
    android.view.OrientationEventListener orientation;
    boolean trackingNeeded(){return tracking&&power.preview(SystemClock.elapsedRealtime());}
    void trackFace(float height,float[] point){
        if(!ready||busy||photoPending||closed||!tracking)return;
        boolean changed=false;
        if(autoZoom&&point!=null){float next=Framing.smooth(zoom,height,framing,minZoom,maxZoom);
            float edge=Math.max(Math.abs(point[0]-.5f)+point[2],Math.abs(point[1]-.5f)+point[3]);
            if(edge>0)next=Math.max(minZoom,Math.min(maxZoom,Math.max(zoom*.95f,Math.min(next,zoom*.46f/edge))));
            if(Math.abs(next-zoom)>.005f){zoom=next;changed=true;}}
        if(faceFocus&&focus<0&&point!=null&&(focusPoint==null||Math.abs(focusPoint[0]-point[0])+Math.abs(focusPoint[1]-point[1])>.03f)){focusPoint=point;changed=true;}
        if(changed)try{settings(request);applyDemand(true);}catch(Exception e){fail(e);}
    }
    Rect meteringCrop(){
        Rect sensor=characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        Rect area=new Rect(sensor);
        if(android.os.Build.VERSION.SDK_INT<30||characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)==null){int w=Math.round(sensor.width()/zoom),h=Math.round(sensor.height()/zoom);area=new Rect(sensor.centerX()-w/2,sensor.centerY()-h/2,sensor.centerX()+w/2,sensor.centerY()+h/2);}
        float aspect=(float)previewWidth/previewHeight;if((float)area.width()/area.height()>aspect){int width=Math.round(area.height()*aspect);area.left=area.centerX()-width/2;area.right=area.left+width;}else{int height=Math.round(area.width()/aspect);area.top=area.centerY()-height/2;area.bottom=area.top+height;}return area;
    }

    volatile boolean busy, photoPending, closed;
    File recordingFile;

    CameraEngine(Context c,AudioEngine microphone) throws Exception {
        audio=microphone;
        context = c; manager = (CameraManager)c.getSystemService(Context.CAMERA_SERVICE);
        mediaDir = new File(c.getExternalFilesDir(null), "captures");
        if (!mediaDir.isDirectory() && !mediaDir.mkdirs()) throw new IOException("Cannot create capture directory");
        store=new CaptureStore(c,mediaDir);
        thread.start(); handler = new Handler(thread.getLooper());
        encoderThread.start();encoderHandler=new Handler(encoderThread.getLooper());
        power.eco = c.getSharedPreferences("deck", 0).getBoolean("eco", false);
        for (String id : manager.getCameraIdList()) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { cameraId = id; break; }
        }
        if (cameraId.isEmpty()) {
            String[] ids=manager.getCameraIdList();
            if(ids.length==0)throw new IOException("На устройстве нет доступных камер");
            cameraId=ids[0];
        }
        handler.post(() -> { try { open(cameraId); } catch (Exception e) { fail(e); } });
        orientation=new android.view.OrientationEventListener(c){public void onOrientationChanged(int degrees){if(degrees<0||characteristics==null)return;deviceRotation=((degrees+45)/90*90)%360;Integer sensor=characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);boolean front=Integer.valueOf(0).equals(characteristics.get(CameraCharacteristics.LENS_FACING));rotation=((sensor==null?90:sensor)+(front?deviceRotation:-deviceRotation)+360)%360;}};orientation.enable();
        handler.postDelayed(powerTick, 1000);
    }

    void demandRemote() { power.remote(SystemClock.elapsedRealtime()); handler.post(() -> { try { applyDemand(false); } catch (Exception e) { fail(e); } }); }
    void demandLocal() { power.local(SystemClock.elapsedRealtime()); }
    void hideLocal() { power.hideLocal(); }
    void setEco(boolean value) { power.eco = value; context.getSharedPreferences("deck",0).edit().putBoolean("eco",value).apply(); }

    void applyDemand(boolean force) throws Exception {
        if (session == null || request == null || busy || photoPending || closed) return;
        PowerPolicy.Plan plan = power.plan(SystemClock.elapsedRealtime(), recording);
        int fps = recording ? videoFps : (liveUsers>0&&live!=null?live.fps:nativePreview?30:power.fps());
        int demand=(power.localPreview(SystemClock.elapsedRealtime())?1:0)|(power.remotePreview(SystemClock.elapsedRealtime())?2:0)|(liveUsers>0?4:0);
        if (!force && plan == appliedPlan && fps == appliedFps && demand==appliedDemand) return;
        if (plan == PowerPolicy.Plan.STANDBY) {
            session.stopRepeating(); frame = new byte[0];
        } else {
            request.removeTarget(preview.getSurface());
            // Local hardware preview does not need CPU JPEG conversion.
            if ((plan==PowerPolicy.Plan.PREVIEW||plan==PowerPolicy.Plan.RECORD_PREVIEW)&&(liveUsers==0&&power.remotePreview(SystemClock.elapsedRealtime()) || !nativePreview || trackingNeeded())) request.addTarget(preview.getSurface());
            if(live!=null&&!liveRejected){request.removeTarget(live.surface);if(liveUsers>0)request.addTarget(live.surface);}
            if(nativePreview&&localOutput!=null){request.removeTarget(localOutput.surface);if(power.localPreview(SystemClock.elapsedRealtime()))request.addTarget(localOutput.surface);}
            // The recorder surface remains targeted when only preview is paused.
            Range<Integer>[] ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            Range<Integer> best = null; int score = Integer.MAX_VALUE;
            if (ranges != null) for (Range<Integer> range : ranges) {
                int candidate = Math.abs(range.getUpper() - fps) * 100 + Math.abs(range.getLower() - fps);
                if (candidate < score) { best = range; score = candidate; }
            }
            if (best != null) request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, best);
            if (iso > 0 && exposure > 0) request.set(CaptureRequest.SENSOR_FRAME_DURATION, Math.max(1_000_000_000L/fps, exposure));
            repeat();
        }
        appliedPlan = plan; appliedFps = fps;appliedDemand=demand;
        powerState = plan.name().toLowerCase(java.util.Locale.US);
    }

    boolean manual() {
        int[] caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (caps != null) for (int cap : caps) if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) return true;
        return false;
    }

    @SuppressWarnings("MissingPermission")
    void open(String id) throws Exception {
        if (closed) return;
        ready = false; busy = true; error = ""; cameraId = id;
        characteristics = manager.getCameraCharacteristics(id);
        capabilities=CaptureCapabilities.read(characteristics);
        Float digital=characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);minZoom=1;maxZoom=digital==null?1:Math.max(1,digital);
        if(android.os.Build.VERSION.SDK_INT>=30){Range<Float> range=characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);if(range!=null){minZoom=range.getLower();maxZoom=range.getUpper();}}
        zoom=Math.max(minZoom,Math.min(maxZoom,zoom));faces=new JSONArray();focusPoint=null;

        if(!CaptureCapabilities.videoAllowed(capabilities,videoWidth,videoHeight,videoFps)) {
            JSONArray modes=capabilities.getJSONArray("videoModes");
            if(modes.length()>0) {
                JSONObject selected=modes.getJSONObject(0);
                for(int i=0;i<modes.length();i++)if(modes.getJSONObject(i).getInt("width")==1920 && modes.getJSONObject(i).getInt("height")==1080)selected=modes.getJSONObject(i);
                videoWidth=selected.getInt("width"); videoHeight=selected.getInt("height");
                JSONArray rates=selected.getJSONArray("fps"); videoFps=rates.getInt(0);
                for(int i=0;i<rates.length();i++)if(rates.getInt(i)==30)videoFps=30;
            }
        }
        if(!CaptureCapabilities.photoAllowed(capabilities,photoFormat,photoWidth,photoHeight)) {
            photoFormat="jpg"; JSONArray sizes=capabilities.getJSONArray("photoSizes");
            if(sizes.length()==0)throw new IOException("No supported photo sizes");
            JSONObject selected=sizes.getJSONObject(0);
            for(int i=0;i<sizes.length();i++) { JSONObject candidate=sizes.getJSONObject(i); if(candidate.getInt("width")<=4096 && (long)candidate.getInt("width")*candidate.getInt("height")>(long)selected.getInt("width")*selected.getInt("height"))selected=candidate; }
            photoWidth=selected.getInt("width"); photoHeight=selected.getInt("height");
        }
        int sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        boolean front = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT;
        rotation = (sensor + (front ? deviceRotation : -deviceRotation) + 360) % 360;
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size p = choose(map.getOutputSizes(ImageFormat.YUV_420_888), 960, 540);
        previewWidth=p.getWidth();previewHeight=p.getHeight();
        if(localOutput!=null)localOutput.texture.setDefaultBufferSize(p.getWidth(),p.getHeight());
        preview = ImageReader.newInstance(p.getWidth(), p.getHeight(), ImageFormat.YUV_420_888, 2);
        preview.setOnImageAvailableListener(reader -> {
            if(closed||reader!=preview)return;
            try (Image img = reader.acquireLatestImage()) {
                if (img == null || (!trackingNeeded()&&!power.remotePreview(SystemClock.elapsedRealtime())&&nativePreview) || !power.preview(SystemClock.elapsedRealtime()) || System.nanoTime() - lastPreview < 1_000_000_000L/power.fps()) return;
                lastPreview = System.nanoTime();
                boolean detection=trackingNeeded()&&tracker.wants();
                boolean jpeg=(liveUsers==0&&power.remotePreview(SystemClock.elapsedRealtime()))||!nativePreview;
                if(!detection&&!jpeg)return;
                byte[] nv21 = nv21(img);
                if(detection)tracker.offer(nv21,img.getWidth(),img.getHeight(),rotation,this);
                if(!jpeg)return;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                new YuvImage(nv21, ImageFormat.NV21, img.getWidth(), img.getHeight(), null)
                    .compressToJpeg(new Rect(0, 0, img.getWidth(), img.getHeight()), power.quality(), out);
                frame = out.toByteArray(); frameTime = System.currentTimeMillis(); frameSequence++;
                synchronized(frameReady){frameReady.notifyAll();}
            } catch (Exception e) { if(!closed&&reader==preview)fail(e); }
        }, encoderHandler);
        createPhotoReader();
        manager.openCamera(id, new CameraDevice.StateCallback() {
            public void onOpened(CameraDevice device) { if (closed) { device.close(); return; } camera = device; try { configure(false); } catch (Exception e) { fail(e); busy = false; } }
            public void onDisconnected(CameraDevice device) { device.close(); ready = false; busy = false; error = "Camera disconnected"; }
            public void onError(CameraDevice device, int code) { device.close(); ready = false; busy = false; error = "Camera error " + code; }
        }, handler);
    }

    void createPhotoReader() {
        photos=ImageReader.newInstance(photoWidth,photoHeight,photoFormat.equals("dng")?ImageFormat.RAW_SENSOR:ImageFormat.JPEG,1);
        photos.setOnImageAvailableListener(reader -> {
            Image img=null;
            try {
                img=reader.acquireNextImage();
                if (img == null) return;
                if(!photoPending) { img.close(); return; }
                if(photoFormat.equals("dng")) { if(pendingRaw!=null)pendingRaw.close(); pendingRaw=img; img=null; saveRawIfReady(); }
                else { savePhoto(img,null); img=null; }
            } catch (Exception e) { if(img!=null)img.close(); fail(e); photoPending=false; }
        }, handler);
    }
    void saveRawIfReady() {
        if(pendingRaw==null || rawResult==null)return;
        Image img=pendingRaw; pendingRaw=null; TotalCaptureResult result=rawResult; rawResult=null;
        savePhoto(img,result);
    }
    void savePhoto(Image img,TotalCaptureResult result) {
        photoSaving=true;
        String format=photoFormat; int orientation=rotation; CameraCharacteristics c=characteristics;
        File f=nextFile(format); long requestId=photoRequestId;
        photoWriter.execute(()-> {
            try(Image image=img) { PhotoWriter.write(image,result,c,f,format,orientation);store.photo(f);lastFile=f.getName(); }
            catch(Exception e) { fail(e); }
            catch(OutOfMemoryError e){error="Недостаточно памяти для максимального фото. Выберите JPEG или меньшее разрешение.";}
            finally { handler.post(()-> { if(photoRequestId==requestId){photoPending=false;photoSaving=false;} }); }
        });
    }

    static Size choose(Size[] sizes, int w, int h) {
        return Arrays.stream(sizes).min(Comparator.comparingDouble(s -> Math.abs((double)s.getWidth()/s.getHeight() - (double)w/h)*10000 + Math.abs(s.getWidth()-w))).orElseThrow();
    }

    void configure(boolean video) throws Exception {
        ready = false; busy = true;
        if (session != null) { session.close(); session = null; }
        List<Surface> surfaces = new ArrayList<>(); surfaces.add(preview.getSurface());
        surfaces.add(video ? recorder.getSurface() : photos.getSurface());
        final LiveVideo streaming=liveRejected?null:live;if(streaming!=null)surfaces.add(streaming.surface);
        final LocalOutput output=localRejected?null:localOutput;
        if(output!=null)surfaces.add(output.surface);
        nativePreview=false;
        camera.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
            public void onConfigured(CameraCaptureSession s) {
                if (closed) { s.close(); return; }
                session = s;
                try {
                    if(!localRejected&&output!=localOutput){configure(video);return;}
                    nativePreview=output!=null&&output==localOutput;
                    request = camera.createCaptureRequest(video ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
                    request.addTarget(preview.getSurface());
                    if (video) request.addTarget(recorder.getSurface());
                    if(nativePreview)request.addTarget(output.surface);
                    if(streaming!=null&&liveUsers>0)request.addTarget(streaming.surface);
                    settings(request);
                    // Start recorder before applying demand so the recording target is never removed.
                    if (video) { repeat();if(!recorder.started)recorder.start(); recording = true; }
                    busy = false; ready = true; appliedPlan = null; applyDemand(true);
                } catch (Exception e) { recording = false; busy = false; fail(e); releaseRecorder(); }
            }
            public void onConfigureFailed(CameraCaptureSession s) {
                s.close();session=null;request=null;busy=false;ready=false;
                if(output!=null){localRejected=true;nativePreview=false;try{configure(video);}catch(Exception e){fail(e);busy=false;}return;}
                if(streaming!=null){liveRejected=true;liveError="Сочетание камеры и H.264 просмотра недоступно — совместимый JPEG";streaming.closed=true;try{configure(video);}catch(Exception e){fail(e);}return;}
                recording=false;releaseRecorder();
                error="Unsupported camera stream combination";
                try {
                    if(video) { error="Video size/FPS + preview combination failed; choose another recording mode";configure(false); }
                    else if(photoFormat.equals("dng")) {
                        error="RAW + preview unavailable on this camera; restored JPEG";
                        if(photos!=null)photos.close();photoFormat="jpg";
                        JSONObject size=capabilities.getJSONArray("photoSizes").getJSONObject(0);
                        photoWidth=size.getInt("width");photoHeight=size.getInt("height");
                        JSONObject supported=new JSONObject(capabilities.toString());
                        supported.put("photoFormats",new JSONArray().put("jpg").put("png").put("webp"));capabilities=supported;
                        createPhotoReader();configure(false);
                    }
                } catch(Exception e) { fail(e);busy=false; }
            }
            public void onClosed(CameraCaptureSession s){if(output!=null&&retiredOutputs.remove(output))output.release();}
        }, handler);
    }

    void settings(CaptureRequest.Builder b) {
        if(android.os.Build.VERSION.SDK_INT>=30&&characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)!=null)b.set(CaptureRequest.CONTROL_ZOOM_RATIO,zoom);
        else{Rect sensor=characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);int w=Math.round(sensor.width()/zoom),h=Math.round(sensor.height()/zoom);b.set(CaptureRequest.SCALER_CROP_REGION,new Rect(sensor.centerX()-w/2,sensor.centerY()-h/2,sensor.centerX()+w/2,sensor.centerY()+h/2));}
        if(focusPoint!=null){Integer count=characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);if(count!=null&&count>0){Rect area=meteringCrop();int size=Math.max(1,Math.min(area.width(),area.height())/12),x=area.left+Math.round(area.width()*focusPoint[0]),y=area.top+Math.round(area.height()*focusPoint[1]);x=Math.max(area.left,Math.min(area.right-size,x-size/2));y=Math.max(area.top,Math.min(area.bottom-size,y-size/2));b.set(CaptureRequest.CONTROL_AF_REGIONS,new android.hardware.camera2.params.MeteringRectangle[]{new android.hardware.camera2.params.MeteringRectangle(x,y,size,size,1000)});}}

        if (iso > 0 && exposure > 0 && manual()) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso); b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure);
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, Math.max(1_000_000_000L/(recorder!=null?videoFps:power.fps()), exposure));
        } else b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        if (focus >= 0) { b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF); b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus); }
        else {
            int preferred=recorder!=null?CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO:CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
            int[] modes=characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            b.set(CaptureRequest.CONTROL_AF_MODE,CameraModes.autofocus(modes,preferred));
        }
    }

    void repeat() throws Exception {
        session.setRepeatingRequest(request.build(), new CameraCaptureSession.CaptureCallback() {
            public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
                Integer i = result.get(CaptureResult.SENSOR_SENSITIVITY); Long t = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                Integer af=result.get(CaptureResult.CONTROL_AF_STATE);if(af!=null)afState=af;
                if (i != null) measuredIso = i; if (t != null) measuredExposure = t;
            }
        }, handler);
    }

    JSONObject command(JSONObject cmd) throws Exception {
        FutureTask<JSONObject> task = new FutureTask<>(() -> {
            String action = cmd.getString("action");
            if (!ready || busy) throw new IllegalStateException(error.isEmpty() ? "Camera is opening / busy" : error);
            power.operation(SystemClock.elapsedRealtime());
            switch (action) {
                case "framing": {
                    float next=(float)cmd.optDouble("zoom",zoom);if(!Float.isFinite(next)||next<minZoom||next>maxZoom)throw new IllegalArgumentException("Зум вне диапазона камеры");
                    String preset=cmd.optString("preset",framing);if(Framing.target(preset)==0)throw new IllegalArgumentException("Неизвестный план");
                    zoom=next;framing=preset;autoZoom=cmd.optBoolean("autoZoom",autoZoom);tracking=cmd.optBoolean("tracking",tracking)||autoZoom;faceFocus=cmd.optBoolean("faceFocus",faceFocus);if(!tracking){faces=new JSONArray();focusPoint=null;}
                    if(cmd.has("focusX")){float x=(float)cmd.getDouble("focusX"),y=(float)cmd.getDouble("focusY");if(!Float.isFinite(x)||!Float.isFinite(y)||x<0||x>1||y<0||y>1)throw new IllegalArgumentException("Точка вне кадра");focusPoint=new float[]{x,y};faceFocus=false;focus=-1;}
                    settings(request);applyDemand(true);break;
                }
                case "stream_config": {
                    String profile=cmd.optString("profile","balanced");if(!profile.equals("balanced")&&!profile.equals("detail"))throw new IllegalArgumentException("Unknown stream profile");
                    if(recording||photoPending)throw new IllegalStateException("Остановите запись перед сменой потока");
                    liveProfile=profile;liveRejected=false;liveError="";if(live!=null){LiveVideo old=live;live=null;liveUsers=0;configure(false);handler.postDelayed(old::close,1500);}break;
                }
                case "capture_config": {
                    if(recording || photoPending)throw new IllegalStateException("Stop recording / wait for photo before changing mode");
                    String mode=cmd.optString("mode",captureMode), format=cmd.optString("photoFormat",photoFormat);
                    int w=cmd.optInt("videoWidth",videoWidth),h=cmd.optInt("videoHeight",videoHeight),fps=cmd.optInt("videoFps",videoFps);
                    int pw=cmd.optInt("photoWidth",photoWidth),ph=cmd.optInt("photoHeight",photoHeight);
                    if(!mode.equals("photo") && !mode.equals("video"))throw new IllegalArgumentException("Unknown capture mode");
                    if((mode.equals("video") || capabilities.getJSONArray("videoModes").length()>0) && !CaptureCapabilities.videoAllowed(capabilities,w,h,fps))throw new IllegalArgumentException("Video size/FPS not supported by camera and encoder");
                    if(!CaptureCapabilities.photoAllowed(capabilities,format,pw,ph))throw new IllegalArgumentException("Photo format/size not supported");
                    error="";
                    boolean rebuild=!format.equals(photoFormat)||pw!=photoWidth||ph!=photoHeight;
                    captureMode=mode; videoWidth=w;videoHeight=h;videoFps=fps;photoFormat=format;photoWidth=pw;photoHeight=ph;sound=cmd.optBoolean("audio",sound);
                    if(rebuild) {
                        ready=false;busy=true;session.stopRepeating();session.abortCaptures();session.close();session=null;request=null;
                        photos.close();createPhotoReader();configure(false);
                    }
                    break;
                }
                case "settings": {
                    int newIso = cmd.optInt("iso", 0); long newExposure = cmd.optLong("exposureNs", 0);
                    float newFocus = (float)cmd.optDouble("focus", -1);
                    if ((newIso > 0) != (newExposure > 0)) throw new IllegalArgumentException("Set ISO and exposure together");
                    if (newIso > 0) {
                        if (!manual()) throw new IllegalArgumentException("Manual sensor unavailable");
                        Range<Integer> ir = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                        Range<Long> er = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                        if (!ir.contains(newIso) || !er.contains(newExposure) || (recording && newExposure > 1_000_000_000L/videoFps)) throw new IllegalArgumentException("Settings outside camera / selected FPS range");
                    }
                    Float maxFocus = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                    if (newFocus < -1 || (newFocus >= 0 && (maxFocus == null || maxFocus == 0 || newFocus > maxFocus))) throw new IllegalArgumentException("Focus outside camera range");
                    iso = newIso; exposure = newExposure; focus = newFocus; settings(request); applyDemand(true); break;
                }
                case "photo": {
                    if(!captureMode.equals("photo"))throw new IllegalStateException("Select Photo mode first");
                    if (recording || photoPending) throw new IllegalStateException("Photo unavailable during video / previous photo");
                    CaptureRequest.Builder shot = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                    shot.addTarget(photos.getSurface()); settings(shot); shot.set(CaptureRequest.JPEG_ORIENTATION, rotation);
                    photoPending = true; rawResult=null; long shotId=++photoRequestId;
                    session.capture(shot.build(), new CameraCaptureSession.CaptureCallback() {
                        public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest r,TotalCaptureResult result) { if(shotId==photoRequestId && photoFormat.equals("dng")) { rawResult=result;saveRawIfReady(); } }
                        public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure f) { if(shotId==photoRequestId) { photoPending = false; error = "Photo capture failed"; if(pendingRaw!=null){pendingRaw.close();pendingRaw=null;} } }
                    }, handler);
                    handler.postDelayed(()-> {
                        if(photoRequestId==shotId && photoPending && !photoSaving) {
                            photoPending=false;rawResult=null;
                            if(pendingRaw!=null){pendingRaw.close();pendingRaw=null;}
                            error="Photo image / RAW metadata timeout";
                        }
                    },Math.max(15000,exposure/1_000_000L+5000));
                    break;
                }
                case "record_start": {
                    if (recording) throw new IllegalStateException("Already recording");
                    if(!captureMode.equals("video"))throw new IllegalStateException("Select Video mode first");
                    if(sound && !audio.available())throw new IllegalStateException("Разрешите микрофон на телефоне или выключите звук в настройках");
                    if (exposure > 1_000_000_000L/videoFps) throw new IllegalArgumentException("Shutter is too long for selected FPS; shorten it or use Auto");
                    error="";
                    recordingFile = nextFile("mp4");
                    boolean realtime=characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)==CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;
                    recordingTarget=store.video(recordingFile.getName());
                    try{recorder=new AvRecorder(recordingTarget.fd(),videoWidth,videoHeight,videoFps,rotation,sound,audio,realtime);}catch(Exception e){recordingTarget.close();recordingTarget=null;throw e;}
                    try { configure(true); } catch (Exception e) { releaseRecorder(); throw e; } break;
                }
                case "record_stop": {
                    if (!recording) throw new IllegalStateException("Not recording");
                    ready = false;
                    session.stopRepeating(); session.abortCaptures();
                    finishRecording(); break;
                }
                case "camera": {
                    if (recording || photoPending) throw new IllegalStateException("Stop recording / wait for photo before switching");
                    String next = cmd.getString("id"); if (!Arrays.asList(manager.getCameraIdList()).contains(next)) throw new IllegalArgumentException("Unknown camera");
                    closeCamera(); iso = 0; exposure = 0; focus = -1; frame = new byte[0]; open(next); break;
                }
                default: throw new IllegalArgumentException("Unknown action");
            }
            return new JSONObject().put("accepted", true).put("action", action);
        });
        handler.post(task); return task.get(15, TimeUnit.SECONDS);
    }

    JSONObject trackingStatus()throws Exception{
        JSONObject o=new JSONObject().put("faces",SystemClock.elapsedRealtime()-facesAt<800?faces:new JSONArray()).put("afState",afState).put("zoom",zoom).put("rotation",rotation);
        if(focusPoint!=null)o.put("focusPoint",new JSONArray().put(focusPoint[0]).put(focusPoint[1]));return o;
    }
    JSONObject status() throws Exception {
        JSONObject o = new JSONObject().put("ready", ready).put("recording", recording).put("photoPending",photoPending).put("error", error)
            .put("cameraId", cameraId).put("rotation", rotation).put("frameTime", frameTime).put("frameSequence", frameSequence)
            .put("iso", measuredIso).put("exposureNs", measuredExposure).put("manualIso", iso).put("manualExposureNs", exposure).put("focus", focus)
            .put("lastFile", lastFile).put("audio", sound).put("audioAvailable",audio.available()).put("audioError",audio.error).put("preview", "JPEG 640px / up to 10fps");
        o.put("zoom",zoom).put("zoomMin",minZoom).put("zoomMax",maxZoom).put("autoZoom",autoZoom).put("framing",framing).put("tracking",tracking).put("faceFocus",faceFocus).put("faces",SystemClock.elapsedRealtime()-facesAt<800?faces:new JSONArray()).put("afState",afState).put("trackingError",trackingError).put("streamProfile",liveProfile);
        if(focusPoint!=null)o.put("focusPoint",new JSONArray().put(focusPoint[0]).put(focusPoint[1]));
        o.put("mode",captureMode).put("videoWidth",videoWidth).put("videoHeight",videoHeight).put("videoFps",videoFps)
            .put("photoWidth",photoWidth).put("photoHeight",photoHeight).put("photoFormat",photoFormat).put("captureCapabilities",capabilities);
        o.put("powerState", powerState).put("eco", power.eco).put("previewFps", power.fps());
        o.put("streamCodec",live!=null&&!liveRejected?"h264":"jpeg").put("streamWidth",live!=null?live.width:previewWidth).put("streamHeight",live!=null?live.height:previewHeight).put("streamFps",live!=null?live.fps:power.fps()).put("streamError",liveError).put("nativePreview",nativePreview).put("storageName",store.label()).put("storageFreeBytes",store.freeBytes()).put("phoneFreeBytes",mediaDir.getUsableSpace()).put("externalStorage",store.tree()!=null);
        JSONArray cameras = new JSONArray();
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            cameras.put(new JSONObject().put("id", id).put("facing", c.get(CameraCharacteristics.LENS_FACING)));
        }
        o.put("cameras", cameras);
        CameraCharacteristics c = characteristics;
        if (c != null) {
            Range<Integer> ir = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            Range<Long> er = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            o.put("manualSensor", manual()).put("isoMin", ir == null ? 0 : ir.getLower()).put("isoMax", ir == null ? 0 : ir.getUpper())
                .put("exposureMinNs", er == null ? 0 : er.getLower()).put("exposureMaxNs", er == null ? 0 : er.getUpper())
                .put("focusMax", c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE));
        }
        return o;
    }

    static byte[] nv21(Image img) {
        int w=img.getWidth(),h=img.getHeight();byte[] result=new byte[w*h*3/2];
        Image.Plane[] planes=img.getPlanes();
        for(int p=0;p<3;p++){
            ByteBuffer buffer=planes[p].getBuffer();int start=buffer.position(),rows=p==0?h:h/2,cols=p==0?w:w/2,stride=planes[p].getPixelStride();
            byte[] row=new byte[(cols-1)*stride+1];
            for(int y=0;y<rows;y++){
                buffer.position(start+y*planes[p].getRowStride());buffer.get(row);
                if(p==0&&stride==1){System.arraycopy(row,0,result,y*w,w);continue;}
                for(int x=0;x<cols;x++)result[p==0?y*w+x:w*h+y*w+x*2+(p==2?0:1)]=row[x*stride];
            }
        }
        return result;
    }
    File nextFile(String ext) { return new File(mediaDir, "CD_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0,8) + "." + ext); }
    void fail(Exception e) { error = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()); }
    void finishRecording() throws Exception {
        String failure="";
        try { recorder.stop(); } catch(Exception e) { failure=e.getMessage(); }
        recorder.close(); if(failure.isEmpty())failure=recorder.error;
        recording=false;recorder=null;
        try{if(failure.isEmpty()&&recordingTarget!=null){recordingTarget.finish();lastFile=recordingFile.getName();}else{if(recordingTarget!=null)recordingTarget.close();error="Recording not finalized; partial file retained: "+failure;}}catch(Exception e){fail(e);}finally{recordingTarget=null;}
        configure(false);
    }
    void releaseRecorder() { if (recorder != null) { try { recorder.close(); } catch (Exception ignored) {} recorder = null; }if(recordingTarget!=null){try{recordingTarget.close();}catch(Exception ignored){}recordingTarget=null;} }
    void closeCamera() {
        ready = false; appliedPlan = null; request = null; powerState = "opening";
        if (session != null) { session.close(); session = null; }
        if (camera != null) { camera.close(); camera = null; }
        if(live!=null){live.close();live=null;}liveUsers=0;liveRejected=false;
        if (preview != null) { preview.close(); preview = null; }
        if (photos != null) { photos.close(); photos = null; }
        if(pendingRaw!=null){pendingRaw.close();pendingRaw=null;}rawResult=null;
    }
    public void close() {
        FutureTask<Void> task = new FutureTask<>(() -> {
            closed = true;
            if (recording) {
                try { recorder.stop(); recorder.close(); if(recorder.error.isEmpty()&&recordingTarget!=null)recordingTarget.finish(); } catch (Exception ignored) {}
            }
            recording = false; releaseRecorder(); closeCamera();if(localOutput!=null){localOutput.surface.release();localOutput=null;}for(LocalOutput old:retiredOutputs)old.release();retiredOutputs.clear();return null;
        });
        handler.post(task); try { task.get(5, TimeUnit.SECONDS); } catch (Exception ignored) {}
        orientation.disable();tracker.close();thread.quitSafely();encoderThread.quitSafely();
        photoWriter.shutdown();
    }
}
