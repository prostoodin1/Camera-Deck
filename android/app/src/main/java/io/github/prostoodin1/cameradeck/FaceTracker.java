package io.github.prostoodin1.cameradeck;

import android.graphics.*;
import android.os.SystemClock;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bundled on-device model; no frame is sent to a recognition service. */
final class FaceTracker implements AutoCloseable {
    final FaceDetector detector=FaceDetection.getClient(new FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL).enableTracking().build());
    final AtomicBoolean busy=new AtomicBoolean();
    long last;
    volatile boolean closed;
    boolean wants(){return !closed&&!busy.get()&&SystemClock.elapsedRealtime()-last>=180;}
    void offer(byte[] bytes,int w,int h,int rotation,CameraEngine engine){
        if(closed||!busy.compareAndSet(false,true))return;last=SystemClock.elapsedRealtime();
        long captured=last;String camera=engine.cameraId;float zoom=engine.zoom;
        int rw=rotation%180==0?w:h,rh=rotation%180==0?h:w;
        detector.process(InputImage.fromByteArray(bytes,w,h,rotation,InputImage.IMAGE_FORMAT_NV21))
            .addOnSuccessListener(faces->{
                if(closed||engine.closed||!camera.equals(engine.cameraId)||Math.abs(zoom-engine.zoom)>.1f)return;
                try{JSONArray out=new JSONArray();float largest=0;float[] center=null;
                    for(Face face:faces){Rect r=face.getBoundingBox();float[] a=Framing.rotate((float)r.left/rw,(float)r.top/rh,(360-rotation)%360),b=Framing.rotate((float)r.right/rw,(float)r.bottom/rh,(360-rotation)%360);
                        JSONObject item=new JSONObject().put("left",Math.min(a[0],b[0])).put("top",Math.min(a[1],b[1])).put("right",Math.max(a[0],b[0])).put("bottom",Math.max(a[1],b[1]));
                        JSONArray eyes=new JSONArray();for(int kind:new int[]{FaceLandmark.LEFT_EYE,FaceLandmark.RIGHT_EYE}){FaceLandmark landmark=face.getLandmark(kind);if(landmark!=null){PointF p=landmark.getPosition();float[] eye=Framing.rotate(p.x/rw,p.y/rh,(360-rotation)%360);eyes.put(new JSONArray().put(eye[0]).put(eye[1]));}}item.put("eyes",eyes);out.put(item);
                        float size=(float)r.height()/rh;if(size>largest){largest=size;center=new float[]{(a[0]+b[0])/2,(a[1]+b[1])/2,Math.abs(a[0]-b[0])/2,Math.abs(a[1]-b[1])/2};}
                    }
                    engine.faces=out;engine.facesAt=captured;final float height=largest;final float[] point=center;
                    engine.handler.post(()->engine.trackFace(height,point));
                }catch(Exception e){engine.trackingError=e.getMessage();}
            }).addOnFailureListener(e->engine.trackingError=e.getMessage()).addOnCompleteListener(task->busy.set(false));
    }
    public void close(){closed=true;detector.close();}
}
