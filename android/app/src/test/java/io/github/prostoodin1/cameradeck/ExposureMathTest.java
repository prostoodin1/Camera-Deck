package io.github.prostoodin1.cameradeck;
import org.junit.Test;
import static org.junit.Assert.*;
public class ExposureMathTest {
 @Test public void evUsesRealHardwareStep(){assertEquals(6,ExposureMath.evSteps(2,1d/3,-9,9));assertEquals(-3,ExposureMath.evSteps(-1,1d/3,-9,9));}
 @Test(expected=IllegalArgumentException.class) public void invalidEvIsRejected(){ExposureMath.evSteps(Double.NaN,.333,-3,3);}
 @Test(expected=IllegalArgumentException.class) public void unsupportedEvIsRejected(){ExposureMath.evSteps(4,1d/3,-9,9);}
 @Test public void toneStaysMonotonicAndBounded(){for(float c:new float[]{.5f,1,1.8f})for(float g:new float[]{.6f,1,1.8f}){float last=-1;for(int i=0;i<=100;i++){float n=ExposureMath.tone(i/100f,c,g);assertTrue(n>=last&&n>=0&&n<=1);last=n;}}}
}
