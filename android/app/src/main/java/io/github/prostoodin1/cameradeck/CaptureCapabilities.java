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
            if(size.getWidth()>3840 || size.getHeight()>2160)continue;
            JSONArray rates=new JSONArray();
            for(int fps:new int[]{15,24,25,30,50,60}) {
                boolean ae=false; if(ranges!=null)for(Range<Integer> range:ranges)if(range.getUpper()==fps)ae=true;
                long duration=map.getOutputMinFrameDuration(MediaCodec.class,size);
                if(!ae || (duration>0 && duration>1_000_000_000L/fps+1000))continue;
                if(codecs.findEncoderForFormat(AvRecorder.videoFormat(size.getWidth(),size.getHeight(),fps))!=null)rates.put(fps);
            }
            if(rates.length()>0)video.put(new JSONObject().put("width",size.getWidth()).put("height",size.getHeight()).put("fps",rates));
        }
        addSizes(processed,map.getOutputSizes(ImageFormat.JPEG));
        int[] capabilities=c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        boolean rawSensor=false;
        if(capabilities!=null)for(int cap:capabilities)if(cap==CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)rawSensor=true;
        if(rawSensor)addSizes(raw,map.getOutputSizes(ImageFormat.RAW_SENSOR));
        JSONArray formats=new JSONArray().put("jpg").put("png").put("webp");
        if(raw.length()>0)formats.put("dng");
        return new JSONObject().put("videoModes",video).put("photoSizes",processed).put("rawSizes",raw).put("photoFormats",formats);
    }
    static void addSizes(JSONArray out,Size[] sizes) throws Exception {
        if(sizes!=null)for(Size s:sizes)if((long)s.getWidth()*s.getHeight()<=24_000_000)
            out.put(new JSONObject().put("width",s.getWidth()).put("height",s.getHeight()));
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
