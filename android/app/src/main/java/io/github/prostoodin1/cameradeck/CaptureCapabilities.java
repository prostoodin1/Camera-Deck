package io.github.prostoodin1.cameradeck;

import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.*;
import android.util.Range;
import android.util.Size;
import org.json.*;
import java.util.*;

final class CaptureCapabilities {
    static JSONObject read(CameraCharacteristics c) throws Exception {
        StreamConfigurationMap map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        JSONArray video=new JSONArray(),processed=new JSONArray(),raw=new JSONArray();
        MediaCodecList codecs=new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        Range<Integer>[] ranges=c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        Size[] videoSizes=map.getOutputSizes(MediaCodec.class);
        if(videoSizes!=null) for(Size size:videoSizes) {
            JSONArray rates=new JSONArray();
            TreeSet<Integer> candidates=new TreeSet<>();
            if(ranges!=null)for(Range<Integer> range:ranges){candidates.add(range.getLower());candidates.add(range.getUpper());}
            for(int fps:candidates) {
                boolean ae=false; if(ranges!=null)for(Range<Integer> range:ranges)if(range.contains(fps))ae=true;
                long duration=map.getOutputMinFrameDuration(MediaCodec.class,size);
                if(!ae || (duration>0 && duration>1_000_000_000L/fps+1000))continue;
                try{if(codecs.findEncoderForFormat(AvRecorder.videoFormat(size.getWidth(),size.getHeight(),fps))!=null)rates.put(fps);}catch(IllegalArgumentException ignored){}
            }
            if(rates.length()>0)video.put(new JSONObject().put("width",size.getWidth()).put("height",size.getHeight()).put("fps",rates));
        }
        addSizes(processed,map.getOutputSizes(ImageFormat.JPEG));addSizes(processed,map.getHighResolutionOutputSizes(ImageFormat.JPEG));
        int[] capabilities=c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        boolean rawSensor=false;
        if(capabilities!=null)for(int cap:capabilities)if(cap==CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)rawSensor=true;
        if(rawSensor)addSizes(raw,map.getOutputSizes(ImageFormat.RAW_SENSOR));
        JSONArray formats=new JSONArray().put("jpg").put("png").put("webp");
        if(raw.length()>0)formats.put("dng");
        JSONArray highSpeed=new JSONArray();
        if(capabilities!=null)for(int cap:capabilities)if(cap==CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO){
            for(Size size:map.getHighSpeedVideoSizes()){JSONArray fps=new JSONArray();for(Range<Integer> r:map.getHighSpeedVideoFpsRangesFor(size))fps.put(new JSONObject().put("min",r.getLower()).put("max",r.getUpper()));highSpeed.put(new JSONObject().put("width",size.getWidth()).put("height",size.getHeight()).put("ranges",fps));}
        }
        JSONArray ae=new JSONArray();if(ranges!=null)for(Range<Integer> r:ranges)ae.put(new JSONObject().put("min",r.getLower()).put("max",r.getUpper()));
        JSONArray sensorSizes=new JSONArray();
        if(android.os.Build.VERSION.SDK_INT>=31){StreamConfigurationMap maximum=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);if(maximum!=null)addSizes(sensorSizes,maximum.getOutputSizes(ImageFormat.JPEG));}
        return new JSONObject().put("videoModes",video).put("photoSizes",processed).put("rawSizes",raw).put("photoFormats",formats).put("aeRanges",ae).put("highSpeedModes",highSpeed).put("maximumSensorSizes",sensorSizes)
            .put("specialModesNote","Высокоскоростные и maximum-sensor режимы показаны отдельно: для них нужен специальный сеанс камеры. В обычной съёмке доступны только проверенные сочетания камеры и кодека.");
    }
    static void addSizes(JSONArray out,Size[] sizes) throws Exception {
        if(sizes!=null)for(Size s:sizes){boolean found=false;for(int i=0;i<out.length();i++){JSONObject old=out.getJSONObject(i);if(old.getInt("width")==s.getWidth()&&old.getInt("height")==s.getHeight())found=true;}if(!found)out.put(new JSONObject().put("width",s.getWidth()).put("height",s.getHeight()));}
    }
    static boolean videoAllowed(JSONObject caps,int w,int h,int fps) throws Exception {
        JSONArray modes=caps.getJSONArray("videoModes");
        for(int i=0;i<modes.length();i++) { JSONObject mode=modes.getJSONObject(i); if(mode.getInt("width")!=w || mode.getInt("height")!=h)continue;
            JSONArray rates=mode.getJSONArray("fps"); for(int j=0;j<rates.length();j++)if(rates.getInt(j)==fps)return true;
        }
        return false;
    }
    static boolean photoAllowed(JSONObject caps,String format,int w,int h) throws Exception {
        boolean valid=false; JSONArray formats=caps.getJSONArray("photoFormats");
        for(int i=0;i<formats.length();i++)if(format.equals(formats.getString(i)))valid=true;
        if(!valid)return false;
        JSONArray sizes=caps.getJSONArray(format.equals("dng")?"rawSizes":"photoSizes");
        for(int i=0;i<sizes.length();i++) { JSONObject size=sizes.getJSONObject(i); if(size.getInt("width")==w && size.getInt("height")==h)return true; }
        return false;
    }
}
