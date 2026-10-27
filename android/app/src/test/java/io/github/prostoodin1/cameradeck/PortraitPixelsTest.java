package io.github.prostoodin1.cameradeck;
import org.junit.Test;import static org.junit.Assert.*;import java.util.Arrays;
public class PortraitPixelsTest {
 @Test public void detectedSubjectIsPreservedAndSourceIsNotMutated(){int[] p={0xff000000,0xffffffff,0xff000000,0xffffffff};int[] copy=p.clone();assertArrayEquals(p,PortraitPixels.blend(p,2,2,new float[]{1},1,1,1));assertArrayEquals(copy,p);}
 @Test public void backgroundIsActuallyBlurred(){int[] p=new int[25];Arrays.fill(p,0xff000000);p[12]=0xffffffff;int[] out=PortraitPixels.blend(p,5,5,new float[]{0},1,1,1);assertNotEquals(p[12],out[12]);assertTrue((out[11]&255)>0);}
 @Test public void noBlurReturnsIndependentCopy(){int[] p={0xff112233};int[] out=PortraitPixels.blend(p,1,1,new float[]{0},1,1,0);assertArrayEquals(p,out);assertNotSame(p,out);}
}
