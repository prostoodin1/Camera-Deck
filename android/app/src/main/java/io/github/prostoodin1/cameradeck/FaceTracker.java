package io.github.prostoodin1.cameradeck;
import android.graphics.*;
import android.os.SystemClock;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
/** Local detection and landmarks; does not identify people. */
final class FaceTracker implements AutoCloseable {
 final FaceDetector detector=FaceDetection.getClient(new FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).setMinFaceSize(.05f).setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL).enableTracking().build());
 final AtomicBoolean busy=new AtomicBoolean(); long last,autoSeen;int autoId=-1,nextFallback=1000000;volatile boolean closed;
 boolean wants(){return !closed&&!busy.get()&&SystemClock.elapsedRealtime()-last>=120;}
 static float clamp(float n){return Math.max(0,Math.min(1,n));}
 void offer(byte[] bytes,int w,int h,int rotation,CameraEngine engine){
  if(closed||!busy.compareAndSet(false,true))return;last=SystemClock.elapsedRealtime();
  long captured=last;String camera=engine.cameraId;float zoom=engine.zoom;int epoch=engine.configuration;
  int rw=rotation%180==0?w:h,rh=rotation%180==0?h:w;
  detector.process(InputImage.fromByteArray(bytes,w,h,rotation,InputImage.IMAGE_FORMAT_NV21)).addOnSuccessListener(found->{
   if(closed||engine.closed||!engine.tracking||!camera.equals(engine.cameraId)||epoch!=engine.configuration||Math.abs(zoom-engine.zoom)>.1f)return;
   try{
    List<Face> sorted=new ArrayList<>(found);sorted.sort((a,b)->Long.compare((long)b.getBoundingBox().width()*b.getBoundingBox().height(),(long)a.getBoundingBox().width()*a.getBoundingBox().height()));
    JSONArray out=new JSONArray();Set<Integer> used=new HashSet<>();
    for(Face face:sorted){
     if(out.length()>=engine.faceLimit)break;
     Rect r=face.getBoundingBox();float[] a=Framing.rotate((float)r.left/rw,(float)r.top/rh,(360-rotation)%360),b=Framing.rotate((float)r.right/rw,(float)r.bottom/rh,(360-rotation)%360);
     float l=clamp(Math.min(a[0],b[0])),t=clamp(Math.min(a[1],b[1])),rr=clamp(Math.max(a[0],b[0])),bb=clamp(Math.max(a[1],b[1]));if(rr<=l||bb<=t)continue;
     Integer tracked=face.getTrackingId();int id=tracked==null?-1:tracked;
     if(id==-1){double nearest=.15;for(int i=0;i<engine.faces.length();i++){JSONObject old=engine.faces.getJSONObject(i);int oldId=old.optInt("id",-1);if(oldId<1000000||used.contains(oldId))continue;double distance=Math.abs(old.getDouble("left")-l)+Math.abs(old.getDouble("top")-t);if(distance<nearest){nearest=distance;id=oldId;}}if(id==-1)id=nextFallback++;}
     used.add(id);JSONObject item=new JSONObject().put("id",id).put("left",l).put("top",t).put("right",rr).put("bottom",bb);JSONArray eyes=new JSONArray();
     for(int kind:new int[]{FaceLandmark.LEFT_EYE,FaceLandmark.RIGHT_EYE}){FaceLandmark landmark=face.getLandmark(kind);if(landmark==null)continue;PointF p=landmark.getPosition();float[] eye=Framing.rotate(p.x/rw,p.y/rh,(360-rotation)%360);JSONArray point=new JSONArray().put(clamp(eye[0])).put(clamp(eye[1]));item.put(kind==FaceLandmark.LEFT_EYE?"leftEye":"rightEye",point);eyes.put(point);}item.put("eyes",eyes);out.put(item);
    }
    int wanted=engine.selectedFaceId;JSONObject target=null;
    if(wanted==-1){for(int i=0;i<out.length();i++)if(out.getJSONObject(i).getInt("id")==autoId)target=out.getJSONObject(i);if(target!=null)autoSeen=captured;else if(captured-autoSeen>700||autoId==-1){target=out.length()>0?out.getJSONObject(0):null;if(target!=null){autoId=target.getInt("id");autoSeen=captured;}}}
    else for(int i=0;i<out.length();i++)if(out.getJSONObject(i).getInt("id")==wanted)target=out.getJSONObject(i);
    final JSONObject chosen=target;
    engine.handler.post(()->{
     if(closed||engine.closed||!engine.tracking||!camera.equals(engine.cameraId)||epoch!=engine.configuration||Math.abs(zoom-engine.zoom)>.1f)return;
     engine.faces=out;engine.facesAt=captured;engine.trackingError="";engine.activeFaceId=chosen==null?-1:chosen.optInt("id",-1);
     if(chosen==null){engine.trackFace(0,null,null);return;}
     float l=(float)chosen.optDouble("left"),t=(float)chosen.optDouble("top"),r=(float)chosen.optDouble("right"),b=(float)chosen.optDouble("bottom");float[] center={(l+r)/2,(t+b)/2,(r-l)/2,(b-t)/2},focus=center;
     JSONArray eye=chosen.optJSONArray(engine.focusTarget);if(eye!=null)focus=new float[]{(float)eye.optDouble(0),(float)eye.optDouble(1)};engine.trackFace(b-t,center,focus);
    });
   }catch(Exception error){engine.trackingError=String.valueOf(error.getMessage());}
  }).addOnFailureListener(error->{if(!closed)engine.trackingError=String.valueOf(error.getMessage());}).addOnCompleteListener(task->busy.set(false));
 }
 public void close(){closed=true;detector.close();}
}
