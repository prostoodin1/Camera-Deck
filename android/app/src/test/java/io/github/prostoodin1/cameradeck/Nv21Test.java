package io.github.prostoodin1.cameradeck;
import org.junit.Test;
import static org.junit.Assert.*;

public class Nv21Test {
    @Test public void convertsNeutralBlackAndWhite(){
        int[] out=new int[4];Nv21.argb(new byte[]{16,16,16,16,(byte)128,(byte)128},2,2,out);for(int pixel:out)assertEquals(0xff000000,pixel);
        Nv21.argb(new byte[]{(byte)235,(byte)235,(byte)235,(byte)235,(byte)128,(byte)128},2,2,out);for(int pixel:out)assertEquals(0xffffffff,pixel);
    }
    @Test public void nv21UsesVUOrder(){int[] out=new int[4];Nv21.argb(new byte[]{82,82,82,82,(byte)240,90},2,2,out);assertTrue((out[0]>>16&255)>240);assertTrue((out[0]&255)<10);}
    @Test(expected=IllegalArgumentException.class) public void rejectsTruncatedBuffers(){Nv21.argb(new byte[3],2,2,new int[4]);}
}
