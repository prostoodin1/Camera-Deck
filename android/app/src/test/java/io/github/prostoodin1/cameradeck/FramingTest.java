package io.github.prostoodin1.cameradeck;
import org.junit.Test;
import static org.junit.Assert.*;
public class FramingTest {
    @Test public void everyOrientationMapsBackToSameSensorPoint(){for(int rotation:new int[]{0,90,180,270}){float[] display=Framing.rotate(.2f,.7f,rotation),raw=Framing.rotate(display[0],display[1],(360-rotation)%360);assertArrayEquals(new float[]{.2f,.7f},raw,.0001f);}}
    @Test public void lostFaceDoesNotChangeZoom(){assertEquals(2,Framing.smooth(2,0,"close",1,10),0);}
    @Test public void zoomMovesGraduallyAndStaysInsideHardwareRange(){float zoom=1;for(int i=0;i<100;i++){float next=Framing.smooth(zoom,.05f,"close",1,3);assertTrue(next<=zoom*1.051f);assertTrue(next>=1&&next<=3);zoom=next;}assertTrue(zoom>2.9f);}
    @Test public void changingToWideShotZoomsOut(){assertTrue(Framing.smooth(3,.45f,"wide",1,10)<3);}
}
