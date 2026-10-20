package io.github.prostoodin1.cameradeck;

import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Tee of the ORIGINAL recording encoder. Never blocks the phone's recorder. */
final class RecordingRelay extends InputStream {
    static final int MAX_BYTES=64_000_000,MAX_PACKET=8_000_000;
    final ArrayDeque<byte[]> queue=new ArrayDeque<>();
    int bytes,offset;byte[] current=new byte[0],terminal;
    final long created=SystemClock.elapsedRealtime();
    volatile boolean ended,closed,begun;
    volatile String error="";
    RecordingRelay()throws Exception{json(0,new JSONObject().put("protocol",1).put("phoneBackup",true));}
    synchronized void add(byte[] message){
        if(ended||closed)return;
        if(message.length>MAX_PACKET||bytes+message.length>MAX_BYTES){finish("Сеть не успевает. Запись на телефоне продолжается.");return;}
        queue.add(message);bytes+=message.length;notifyAll();
    }
    static byte[] envelope(int type,byte[] data){ByteBuffer b=ByteBuffer.allocate(data.length+5);b.putInt(data.length+1).put((byte)type).put(data);return b.array();}
    void json(int type,JSONObject object){add(envelope(type,object.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    void format(MediaFormat video,MediaFormat audio,int rotation)throws Exception{
        begun=true;JSONObject object=new JSONObject().put("video",describe(video)).put("rotation",rotation);
        if(audio!=null)object.put("audio",describe(audio));json(1,object);
    }
    static JSONObject describe(MediaFormat f)throws Exception{
        JSONObject o=new JSONObject().put("mime",f.getString(MediaFormat.KEY_MIME));
        for(String key:new String[]{MediaFormat.KEY_WIDTH,MediaFormat.KEY_HEIGHT,MediaFormat.KEY_FRAME_RATE,MediaFormat.KEY_SAMPLE_RATE,MediaFormat.KEY_CHANNEL_COUNT})if(f.containsKey(key))o.put(key,f.getInteger(key));
        ByteArrayOutputStream config=new ByteArrayOutputStream();
        for(String key:new String[]{"csd-0","csd-1","csd-2"})if(f.containsKey(key)){ByteBuffer b=f.getByteBuffer(key).duplicate();byte[] bytes=new byte[b.remaining()];b.get(bytes);config.write(bytes);}
        return o.put("csd",Base64.encodeToString(config.toByteArray(),Base64.NO_WRAP));
    }
    void sample(boolean audio,ByteBuffer buffer,long pts,int flags){
        if(ended||closed)return;
        if(buffer.remaining()>MAX_PACKET-19){finish("Пакет оригинала слишком большой. Резервная запись остаётся на телефоне.");return;}
        ByteBuffer sample=ByteBuffer.allocate(13+buffer.remaining());sample.put((byte)(audio?1:0)).putLong(pts).putInt(flags).put(buffer.duplicate());add(envelope(2,sample.array()));
    }
    synchronized void finish(String failure){
        if(ended||closed)return;ended=true;error=failure==null?"Ошибка записи":failure;
        if(!error.isEmpty()){queue.clear();bytes=0;}
        try{terminal=envelope(3,new JSONObject().put("error",error).put("phoneBackup",true).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}
        notifyAll();
    }
    @Override public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}
    @Override public synchronized int read(byte[] b,int off,int length)throws IOException{
        if(length==0)return 0;
        while(offset==current.length){
            if(closed)return -1;
            if(!queue.isEmpty()){current=queue.removeFirst();bytes-=current.length;offset=0;break;}
            if(ended){if(terminal==null)return -1;current=terminal;terminal=null;offset=0;break;}
            if(!begun&&SystemClock.elapsedRealtime()-created>30000){finish("Не дождались начала записи");continue;}
            try{wait(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
        }
        int n=Math.min(length,current.length-offset);System.arraycopy(current,offset,b,off,n);offset+=n;return n;
    }
    @Override public synchronized void close(){closed=true;queue.clear();bytes=0;notifyAll();}
}
