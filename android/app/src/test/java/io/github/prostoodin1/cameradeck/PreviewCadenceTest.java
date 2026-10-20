package io.github.prostoodin1.cameradeck;
import org.junit.Test;
import static org.junit.Assert.*;

public class PreviewCadenceTest {
    @Test public void thirtyToTwentyFourDoesNotHalveTheFrameRate(){
        PreviewCadence gate=new PreviewCadence();int frames=0;
        for(int i=0;i<300;i++)if(gate.allow(i*1_000_000_000L/30,24))frames++;
        assertTrue("Expected 24 FPS, got "+frames/10.0,frames>=239&&frames<=241);
    }
    @Test public void inactivityAndRateChangesDoNotCauseBursts(){
        PreviewCadence gate=new PreviewCadence();assertTrue(gate.allow(1000000000L,24));assertFalse(gate.allow(1000000000L,24));
        assertTrue(gate.allow(100000000000L,12));assertFalse(gate.allow(100000000000L,12));
        assertTrue(gate.allow(100000000001L,24));assertFalse(gate.allow(100000000001L,24));
    }
}
