package io.github.prostoodin1.cameradeck;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.ByteBuffer;
import org.json.JSONObject;

public class RecordingRelayTest {
    @Test public void completeStreamAlwaysEndsWithExplicitConfirmation()throws Exception{
        RecordingRelay relay=new RecordingRelay(()->0L);
        relay.json(1,new JSONObject().put("video","test"));
        for(int i=0;i<100;i++)relay.sample(false,ByteBuffer.wrap(new byte[]{1,2,3}),i*33333L,i==0?1:0);
        relay.finish("");DataInputStream in=new DataInputStream(relay);int samples=0;boolean end=false;
        while(true){int first=in.read();if(first<0)break;int length=(first<<24)|(in.readUnsignedByte()<<16)|(in.readUnsignedByte()<<8)|in.readUnsignedByte();byte[] data=new byte[length];in.readFully(data);
            if(data[0]==2)samples++;
            if(data[0]==3){assertFalse(end);end=true;assertEquals("",new JSONObject(new String(data,1,data.length-1,"UTF-8")).getString("error"));}
        }
        assertEquals(100,samples);assertTrue(end);
    }
    @Test public void oversizedPacketTerminatesRemoteWithoutBlockingPhone()throws Exception{
        RecordingRelay relay=new RecordingRelay(()->0L);relay.add(new byte[RecordingRelay.MAX_PACKET+1]);
        assertTrue(relay.ended);assertFalse(relay.error.isEmpty());assertEquals(0,relay.bytes);
        byte[] buffer=new byte[4096];assertTrue(relay.read(buffer)>0);assertEquals(-1,relay.read(buffer));
    }
}
