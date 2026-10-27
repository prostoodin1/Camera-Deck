package io.github.prostoodin1.cameradeck;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
import java.util.*;

public class SensorScaleTest {
    JSONObject camera()throws Exception{return new JSONObject().put("cameraId","0").put("mode","video").put("videoFps",60).put("manualSensor",true).put("isoMin",80).put("isoMax",5000).put("iso",200).put("exposureNs",60000000).put("exposureMinNs",100000).put("exposureMaxNs",1000000000).put("focusMax",5);}
    @Test public void stepsRespectActualEndpoints()throws Exception{List<Double> choices=SensorScale.presets("iso",camera());assertEquals(80,choices.get(0),.001);assertEquals(5000,choices.get(choices.size()-1),.001);assertFalse(choices.contains(50.));assertFalse(choices.contains(6400.));}
    @Test public void shutterRespectsFramePeriod()throws Exception{assertEquals(16666666,SensorScale.bounds("shutter",camera())[1],.001);for(double value:SensorScale.presets("shutter",camera()))assertTrue(value<=16666666);}
    @Test public void manualIsoPairsOnlyWhenNeeded()throws Exception{JSONObject s=camera();JSONObject cmd=SensorScale.command("iso",false,800,s);assertEquals(800,cmd.getInt("iso"));assertEquals(16666666,cmd.getLong("exposureNs"));assertFalse(cmd.has("focus"));s.put("manualIso",100).put("manualExposureNs",10000000);cmd=SensorScale.command("iso",false,400,s);assertFalse(cmd.has("exposureNs"));assertEquals(400,cmd.getInt("iso"));}
    @Test public void focusDoesNotResetExposure()throws Exception{JSONObject cmd=SensorScale.command("focus",false,2,camera());assertEquals(2,cmd.getDouble("focus"),.001);assertFalse(cmd.has("iso"));assertFalse(cmd.has("exposureNs"));}
    @Test public void autoResetsBothExposureInputs()throws Exception{JSONObject cmd=SensorScale.command("shutter",true,1000000,camera());assertEquals(0,cmd.getInt("iso"));assertEquals(0,cmd.getLong("exposureNs"));}
    @Test public void logarithmicScaleAndConfigurationGuard()throws Exception{double value=SensorScale.fromProgress("iso",5000,100,6400);assertEquals(800,value,.001);assertEquals(5000,SensorScale.progress("iso",value,100,6400));JSONObject a=camera(),b=camera().put("videoFps",30);assertNotEquals(SensorScale.context(a),SensorScale.context(b));}
    @Test public void evUsesRealStepAndIsUnavailableInManual()throws Exception{JSONObject s=camera().put("imageControls",new JSONObject().put("evMin",-2).put("evMax",2).put("evStep",1./3));assertEquals(2./3,SensorScale.command("ev",false,.5,s).getDouble("ev"),.00001);s.put("manualIso",100);assertFalse(SensorScale.supported("ev",s));}
}
