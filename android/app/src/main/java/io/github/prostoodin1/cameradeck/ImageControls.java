package io.github.prostoodin1.cameradeck;
import android.hardware.camera2.*;
import android.hardware.camera2.params.*;
import android.graphics.Rect;
import android.util.Range;
import org.json.*;

/** Only advertise Camera2 controls the selected camera reports. */
final class ImageControls {
    CameraCharacteristics camera;
    int ev,wb=1,anti=3,effect,metering;boolean lock;
    float aperture,contrast=1,gamma=1,blur;
    int appliedEffect;float appliedAperture;int aeState;
    void configure(CameraCharacteristics c){camera=c;ev=0;wb=pick(c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES),1);anti=pick(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES),3);effect=0;lock=false;metering=0;aperture=0;contrast=gamma=1;blur=0;}
    static boolean has(int[] values,int n){if(values==null)return false;for(int v:values)if(v==n)return true;return false;}
    static int pick(int[] values,int preferred){return has(values,preferred)?preferred:values==null||values.length==0?0:values[0];}
    Range<Integer> evRange(){Range<Integer> r=camera.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);return r==null?new Range<>(0,0):r;}
    double evStep(){android.util.Rational r=camera.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);return r==null||r.doubleValue()<=0?1:r.doubleValue();}
    boolean curves(){return has(camera.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES),CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE);}
    boolean styled(){return effect!=0||contrast!=1||gamma!=1||blur>0;}
    void update(JSONObject cmd,boolean raw,boolean recording)throws Exception{
        int nextEv=cmd.has("ev")?ExposureMath.evSteps(cmd.getDouble("ev"),evStep(),evRange().getLower(),evRange().getUpper()):ev;
        int nextWb=cmd.optInt("whiteBalance",wb),nextAnti=cmd.optInt("antiBanding",anti),nextEffect=cmd.optInt("filter",effect),nextMeter=cmd.optInt("metering",metering);
        boolean nextLock=cmd.optBoolean("aeLock",lock);float nextAperture=(float)cmd.optDouble("aperture",aperture),nextContrast=(float)cmd.optDouble("contrast",contrast),nextGamma=(float)cmd.optDouble("gamma",gamma),nextBlur=(float)cmd.optDouble("backgroundBlur",blur);
        if(cmd.has("whiteBalance")&&!has(camera.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES),nextWb))throw new IllegalArgumentException("Баланс белого не поддерживается");
        if(cmd.has("antiBanding")&&!has(camera.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES),nextAnti))throw new IllegalArgumentException("Антимерцание не поддерживается");
        if(!has(camera.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS),nextEffect)&&nextEffect!=0)throw new IllegalArgumentException("Фильтр не поддерживается");
        if(nextLock&&!Boolean.TRUE.equals(camera.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE)))throw new IllegalArgumentException("Блокировка AE не поддерживается");
        if(nextMeter<0||nextMeter>3)throw new IllegalArgumentException("Неизвестный замер");
        Integer maxRegions=camera.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
        if(nextMeter!=0&&(maxRegions==null||maxRegions==0))throw new IllegalArgumentException("Ручной замер не поддерживается");
        if(!Float.isFinite(nextContrast)||nextContrast<.5f||nextContrast>1.8f||!Float.isFinite(nextGamma)||nextGamma<.6f||nextGamma>1.8f)throw new IllegalArgumentException("Контраст/гамма вне диапазона");
        if((nextContrast!=1||nextGamma!=1)&&!curves())throw new IllegalArgumentException("Ручная тональная кривая недоступна");
        if(!Float.isFinite(nextBlur)||nextBlur<0||nextBlur>1)throw new IllegalArgumentException("Размытие от 0 до 1");
        if(recording&&nextBlur!=blur)throw new IllegalArgumentException("Измените размытие после остановки записи");
        if(raw&&(nextEffect!=0||nextContrast!=1||nextGamma!=1||nextBlur>0))throw new IllegalArgumentException("RAW сохраняет сенсорные данные: отключите эффекты или выберите JPEG");
        if(nextAperture!=0){boolean found=false;float[] values=camera.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);if(values!=null)for(float a:values)if(Math.abs(a-nextAperture)<.01f)found=true;if(!found)throw new IllegalArgumentException("Диафрагма не поддерживается");}
        ev=nextEv;wb=nextWb;anti=nextAnti;effect=nextEffect;lock=nextLock;metering=nextMeter;aperture=nextAperture;contrast=nextContrast;gamma=nextGamma;blur=nextBlur;
    }
    void apply(CaptureRequest.Builder b,Rect area,float[] focusPoint,boolean manual){
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,ev);
        if(Boolean.TRUE.equals(camera.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE)))b.set(CaptureRequest.CONTROL_AE_LOCK,!manual&&lock);
        if(camera.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)!=null)b.set(CaptureRequest.CONTROL_AWB_MODE,wb);
        if(camera.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)!=null)b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,anti);
        if(camera.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS)!=null)b.set(CaptureRequest.CONTROL_EFFECT_MODE,effect);
        if(camera.getAvailableCaptureRequestKeys().contains(CaptureRequest.LENS_APERTURE))b.set(CaptureRequest.LENS_APERTURE,aperture>0?aperture:null);
        Integer regions=camera.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
        if(regions!=null&&regions>0){
            if(metering==0)b.set(CaptureRequest.CONTROL_AE_REGIONS,null);
            else{float x=.5f,y=.5f;if((metering==2||metering==3)&&focusPoint!=null){x=focusPoint[0];y=focusPoint[1];}int size=Math.max(1,Math.min(area.width(),area.height())/(metering==1?3:10));int l=Math.max(area.left,Math.min(area.right-size,area.left+Math.round(x*area.width())-size/2)),t=Math.max(area.top,Math.min(area.bottom-size,area.top+Math.round(y*area.height())-size/2));b.set(CaptureRequest.CONTROL_AE_REGIONS,new MeteringRectangle[]{new MeteringRectangle(l,t,size,size,1000)});}
        }
        if(curves()){
            if(contrast!=1||gamma!=1){float[] points=new float[32];for(int i=0;i<16;i++){points[2*i]=i/15f;points[2*i+1]=ExposureMath.tone(i/15f,contrast,gamma);}b.set(CaptureRequest.TONEMAP_MODE,CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE);b.set(CaptureRequest.TONEMAP_CURVE,new TonemapCurve(points,points,points));}
            else{int[] modes=camera.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES);int mode=pick(modes,CaptureRequest.TONEMAP_MODE_FAST);b.set(CaptureRequest.TONEMAP_MODE,mode);float[] identity={0,0,1,1};b.set(CaptureRequest.TONEMAP_CURVE,mode==CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE?new TonemapCurve(identity,identity,identity):null);}
        }
    }
    JSONObject json()throws JSONException{
        JSONArray apertures=new JSONArray();float[] values=camera.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);if(values!=null)for(float a:values)apertures.put(a);
        return new JSONObject().put("ev",ev*evStep()).put("evMin",evRange().getLower()*evStep()).put("evMax",evRange().getUpper()*evStep()).put("evStep",evStep()).put("aeLock",lock).put("aeLockAvailable",Boolean.TRUE.equals(camera.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE))).put("aeState",aeState).put("whiteBalance",wb).put("whiteBalanceModes",new JSONArray(camera.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)==null?new int[0]:camera.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES))).put("antiBanding",anti).put("antiBandingModes",new JSONArray(camera.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)==null?new int[0]:camera.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES))).put("filter",effect).put("filters",new JSONArray(camera.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS)==null?new int[]{0}:camera.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS))).put("appliedFilter",appliedEffect).put("metering",metering).put("meteringAvailable",camera.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE)!=null&&camera.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE)>0).put("contrast",contrast).put("gamma",gamma).put("toneCurveAvailable",curves()).put("apertures",apertures).put("aperture",aperture).put("appliedAperture",appliedAperture).put("backgroundBlur",blur);
    }
}
