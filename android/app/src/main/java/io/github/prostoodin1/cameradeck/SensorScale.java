package io.github.prostoodin1.cameradeck;
import org.json.*;
import java.util.*;

/** Quick-control values come from the active camera, never a fixed advertised ISO range. */
final class SensorScale {
    static double[] bounds(String kind,JSONObject s){
        if(kind.equals("iso"))return new double[]{Math.max(1,s.optInt("isoMin",50)),Math.max(1,s.optInt("isoMax",6400))};
        if(kind.equals("shutter")){long low=Math.max(1,s.optLong("exposureMinNs",1000)),high=Math.max(1,s.optLong("exposureMaxNs",100000000));if(s.optString("mode").equals("video"))high=Math.min(high,1_000_000_000L/Math.max(1,s.optInt("videoFps",30)));return new double[]{low,high};}
        if(kind.equals("ev")){JSONObject image=s.optJSONObject("imageControls");return new double[]{image==null?0:image.optDouble("evMin",0),image==null?0:image.optDouble("evMax",0)};}
        return new double[]{0,s.optDouble("focusMax",0)};
    }
    static boolean supported(String kind,JSONObject s){double[] b=bounds(kind,s);if(b[1]<b[0])return false;if(kind.equals("focus"))return b[1]>0;if(kind.equals("ev"))return b[1]>b[0]&&s.optInt("manualIso")==0;return s.optBoolean("manualSensor");}
    static double clamp(double value,double low,double high){if(!Double.isFinite(value))throw new IllegalArgumentException("Некорректное значение");return Math.max(low,Math.min(high,value));}
    static double fromProgress(String kind,int progress,double low,double high){double f=clamp(progress,0,10000)/10000.;return (kind.equals("iso")||kind.equals("shutter"))&&low>0?low*Math.pow(high/low,f):low+(high-low)*f;}
    static int progress(String kind,double value,double low,double high){if(high<=low)return 0;value=clamp(value,low,high);double f=(kind.equals("iso")||kind.equals("shutter"))&&low>0?Math.log(value/low)/Math.log(high/low):(value-low)/(high-low);return (int)Math.round(f*10000);}
    static List<Double> presets(String kind,JSONObject s){
        double[] b=bounds(kind,s);TreeSet<Double> values=new TreeSet<>();if(b[1]<b[0])return new ArrayList<>();values.add(b[0]);values.add(b[1]);
        double[] choices=kind.equals("iso")?new double[]{25,50,100,200,400,800,1600,3200,6400,12800,25600,51200,102400}:kind.equals("shutter")?new double[]{1e9/8000,1e9/4000,1e9/2000,1e9/1000,1e9/500,1e9/250,1e9/125,1e9/100,1e9/60,1e9/50,1e9/30,1e9/15,1e9/8,1e9/4,1e9/2,1e9}:kind.equals("ev")?new double[]{-2,-1,0,1,2}:new double[]{0,.25,.5,1,2,5,10};
        for(double value:choices)if(value>=b[0]&&value<=b[1])values.add(value);return new ArrayList<>(values);
    }
    static String context(JSONObject s){return s.optString("cameraId")+"|"+s.optString("mode")+"|"+(s.optString("mode").equals("video")?s.optInt("videoFps"):0)+"|"+Arrays.toString(bounds("iso",s))+Arrays.toString(bounds("shutter",s))+Arrays.toString(bounds("focus",s))+Arrays.toString(bounds("ev",s));}
    static JSONObject command(String kind,boolean automatic,double value,JSONObject latest)throws JSONException{
        if(!supported(kind,latest))throw new IllegalArgumentException("Настройка недоступна для текущего объектива / режима");
        double[] b=bounds(kind,latest);JSONObject cmd=new JSONObject().put("action",kind.equals("ev")?"image_settings":"settings");
        if(kind.equals("focus"))return cmd.put("focus",automatic?-1:clamp(value,b[0],b[1]));
        if(kind.equals("ev")){JSONObject image=latest.getJSONObject("imageControls");double step=image.optDouble("evStep",1);if(!Double.isFinite(step)||step<=0)throw new IllegalArgumentException("Неизвестный шаг EV");return cmd.put("ev",clamp(Math.floor(value/step+.5)*step,b[0],b[1]));}
        if(automatic)return cmd.put("iso",0).put("exposureNs",0);
        if(kind.equals("iso")){cmd.put("iso",Math.round(clamp(value,b[0],b[1])));if(latest.optLong("manualExposureNs")==0){double[] e=bounds("shutter",latest);cmd.put("exposureNs",Math.round(clamp(latest.optLong("exposureNs",10000000)>0?latest.optLong("exposureNs"):10000000,e[0],e[1])));}}
        else{cmd.put("exposureNs",Math.round(clamp(value,b[0],b[1])));if(latest.optInt("manualIso")==0){double[] iso=bounds("iso",latest);cmd.put("iso",Math.round(clamp(latest.optInt("iso")>0?latest.optInt("iso"):iso[0],iso[0],iso[1])));}}
        return cmd;
    }
}
