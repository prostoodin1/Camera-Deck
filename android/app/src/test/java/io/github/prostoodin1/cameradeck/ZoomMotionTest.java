package io.github.prostoodin1.cameradeck;

import org.junit.Test;
import static org.junit.Assert.*;

public class ZoomMotionTest {
    private float run(int interval){
        ZoomMotion motion=new ZoomMotion();motion.reset(1,1000);
        float last=1;
        for(int t=1000;t<=5000;t+=interval){
            motion.observe(4,t);
            float next=motion.step(t);
            assertTrue("zoom never jumps",Math.abs(Math.log(next/last))<=.1001*interval/1000);
            assertTrue(next>=last);last=next;
        }
        return last;
    }
    @Test public void motionDoesNotDependOnFaceOrDisplayCadence(){assertEquals(run(20),run(40),.008);}
    @Test public void lostFaceHoldsAndResumeDoesNotJump(){
        ZoomMotion m=new ZoomMotion();m.reset(1,1000);m.observe(4,1000);
        float last=1;
        for(int t=1033;t<1600;t+=33)last=m.step(t);
        assertEquals(last,m.step(3000),.00001);
        m.observe(4,3000);
        assertTrue(m.step(3033)/last<1.001);
    }
    @Test public void smallDetectionJitterDoesNotHunt(){
        ZoomMotion m=new ZoomMotion();m.reset(2,1000);
        for(int t=1000;t<5000;t+=33){m.observe(t%2==0?2.01f:1.99f,t);assertEquals(2,m.step(t),.00001);}
    }
}
