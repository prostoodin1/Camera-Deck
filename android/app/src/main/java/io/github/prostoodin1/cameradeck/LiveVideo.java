package io.github.prostoodin1.cameradeck;

import android.media.*;
import android.os.*;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.*;

/** Bounded AVC access units. Slow consumers resume on an IDR with SPS/PPS. */
final class LiveVideo implements AutoCloseable {
    record Packet(long sequence,long pts,boolean key,byte[] bytes){}
    final MediaCodec codec;
    final Surface surface;
    final int width,height,fps;
    final ArrayDeque<Packet> packets=new ArrayDeque<>();
    volatile boolean closed;
    volatile String error="";
    byte[] config=new byte[0];long sequence,lastSync;int buffered;
    final Thread worker;
    LiveVideo(int width,int height,int fps)throws Exception{
        this.width=width;this.height=height;this.fps=fps;
        MediaFormat f=AvRecorder.basicFormat(MediaFormat.MIMETYPE_VIDEO_AVC,width,height,fps,width>=1920?12_000_000:6_000_000);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);f.setInteger(MediaFormat.KEY_LATENCY,0);
        f.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER,fps);
        if(Build.VERSION.SDK_INT>=29)f.setInteger(MediaFormat.KEY_MAX_B_FRAMES,0);
        String name=new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(f);
        if(name==null)throw new IllegalStateException("Нет H.264 кодека для выбранного предпросмотра");
        codec=MediaCodec.createByCodecName(name);
        Surface output=null;
        try{codec.configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);output=codec.createInputSurface();codec.start();}
        catch(Exception e){if(output!=null)output.release();codec.release();throw e;}
        surface=output;worker=new Thread(this::drain,"CameraDeck-live-AVC");worker.start();
    }
    void drain(){
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        try{while(!closed){int index=codec.dequeueOutputBuffer(info,10000);
            if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat f=codec.getOutputFormat();java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();for(String key:new String[]{"csd-0","csd-1"}){ByteBuffer b=f.getByteBuffer(key);if(b!=null){byte[] bytes=new byte[b.remaining()];b.get(bytes);out.write(bytes);}}config=out.toByteArray();}
            else if(index>=0){try{
                if(info.size>0){ByteBuffer b=codec.getOutputBuffer(index);b.position(info.offset);b.limit(info.offset+info.size);byte[] bytes=new byte[info.size];b.get(bytes);
                    if((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0){config=bytes;continue;}
                    boolean key=(info.flags&MediaCodec.BUFFER_FLAG_KEY_FRAME)!=0;
                    if(key){byte[] combined=new byte[config.length+bytes.length];System.arraycopy(config,0,combined,0,config.length);System.arraycopy(bytes,0,combined,config.length,bytes.length);bytes=combined;}
                    synchronized(this){if(bytes.length>2_000_000)continue;Packet p=new Packet(++sequence,info.presentationTimeUs,key,bytes);packets.add(p);buffered+=bytes.length;while(packets.size()>16||buffered>4_000_000){buffered-=packets.removeFirst().bytes.length;}notifyAll();}
                }
            }finally{codec.releaseOutputBuffer(index,false);}}
        }}catch(Exception e){if(!closed)error=e.toString();}finally{synchronized(this){notifyAll();}}
    }
    synchronized void sync(){long now=SystemClock.elapsedRealtime();if(closed||now-lastSync<250)return;lastSync=now;Bundle b=new Bundle();b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0);try{codec.setParameters(b);}catch(Exception ignored){}}
    synchronized Packet next(long previous)throws InterruptedException{
        if(closed||!error.isEmpty())return null;
        boolean behind=packets.isEmpty()||previous<packets.peekFirst().sequence-1;
        Packet selected=null;
        for(Packet p:packets){if(behind||previous<0){if(p.key)selected=p;}else if(p.sequence>previous){selected=p;break;}}
        if(selected==null){sync();wait(200);}return selected;
    }
    public void close(){closed=true;synchronized(this){notifyAll();}try{worker.join(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();}try{codec.stop();}catch(Exception ignored){}codec.release();surface.release();}
}
