package io.github.prostoodin1.cameradeck;
import org.junit.Test;
import static org.junit.Assert.*;
public class FramingTest {
    @Test public void rearCameraUsesSensorEventSignForBothLandscapes(){assertEquals(180,Framing.cameraRotation(90,90,false));assertEquals(0,Framing.cameraRotation(90,270,false));assertEquals(90,Framing.cameraRotation(90,0,false));assertEquals(270,Framing.cameraRotation(90,180,false));}
    @Test public void frontCameraInvertsSensorEventAngle(){assertEquals(180,Framing.cameraRotation(270,90,true));assertEquals(0,Framing.cameraRotation(270,270,true));assertEquals(270,Framing.cameraRotation(270,0,true));assertEquals(90,Framing.cameraRotation(270,180,true));}
    @Test public void cameraSensorOrientationIsNotAssumedToBeNinety(){assertEquals(270,Framing.cameraRotation(0,270,false));assertEquals(90,Framing.cameraRotation(0,270,true));assertEquals(180,Framing.cameraRotation(180,0,false));}
    @Test public void everyOrientationMapsBackToSameSensorPoint(){for(int rotation:new int[]{0,90,180,270}){float[] display=Framing.rotate(.2f,.7f,rotation),raw=Framing.rotate(display[0],display[1],(360-rotation)%360);assertArrayEquals(new float[]{.2f,.7f},raw,.0001f);}}
    @Test public void lostFaceDoesNotChangeZoom(){assertEquals(2,Framing.smooth(2,0,"close",1,10),0);}
    @Test public void zoomMovesGraduallyAndStaysInsideHardwareRange(){float zoom=1;for(int i=0;i<100;i++){float next=Framing.smooth(zoom,.05f,"close",1,3);assertTrue(next<=zoom*1.051f);assertTrue(next>=1&&next<=3);zoom=next;}assertTrue(zoom>2.9f);}
    @Test public void changingToWideShotZoomsOut(){assertTrue(Framing.smooth(3,.45f,"wide",1,10)<3);}
}
