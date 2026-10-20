package io.github.prostoodin1.cameradeck;

import android.media.*;
import android.os.SystemClock;
import android.view.Surface;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

/** Surface H.264 + shared-microphone AAC with a single monotonic timestamp origin. */
final class AvRecorder implements AutoCloseable {
    final MediaCodec video, audio;
    final MediaMuxer muxer;
    final Surface surface;
    final AudioEngine microphone;
    final boolean withAudio;
    final boolean realtimeTimestamps;
    final ArrayBlockingQueue<AudioPacket> pcm=new ArrayBlockingQueue<>(30);
    final List<Sample> pending=new ArrayList<>();
    volatile boolean finishing, started, released;
    volatile String error="";
    volatile long originNs;
    Thread videoThread,audioThread;
    int videoTrack=-1,audioTrack=-1,pendingBytes;
    boolean muxing;
    record AudioPacket(byte[] data,long timeNs) {}
    record Sample(boolean audio,byte[] data,long pts,int flags) {}

    AvRecorder(File output,int width,int height,int fps,int rotation,boolean sound,AudioEngine source,boolean realtime) throws Exception {
        this(output,null,width,height,fps,rotation,sound,source,realtime);
    }
    AvRecorder(FileDescriptor output,int width,int height,int fps,int rotation,boolean sound,AudioEngine source,boolean realtime) throws Exception {
        this(null,output,width,height,fps,rotation,sound,source,realtime);
    }
    private AvRecorder(File output,FileDescriptor fd,int width,int height,int fps,int rotation,boolean sound,AudioEngine source,boolean realtime) throws Exception {
        withAudio=sound; microphone=source; realtimeTimestamps=realtime;
        MediaCodec v=null,a=null; MediaMuxer m=null; Surface s=null;
        try {
            MediaFormat format=videoFormat(width,height,fps);
            String codec=new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format);
            if(codec==null) throw new IOException("H.264 encoder does not support this size/FPS");
            v=MediaCodec.createByCodecName(codec); v.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
            s=v.createInputSurface();
            if(sound) {
                if(!source.available()) throw new IOException("Microphone permission missing");
                MediaFormat af=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,AudioEngine.RATE,1);
                af.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                af.setInteger(MediaFormat.KEY_BIT_RATE,128000); af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,8192);
                a=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC); a.configure(af,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
            m=fd!=null?new MediaMuxer(fd,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4):new MediaMuxer(output.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4); m.setOrientationHint(rotation);
        } catch(Exception e) {
            if(s!=null)s.release(); if(v!=null)v.release(); if(a!=null)a.release(); if(m!=null)m.release(); throw e;
        }
        video=v; audio=a; muxer=m; surface=s;
    }
    static MediaFormat basicFormat(String mime,int w,int h,int fps,int bitrate) {
        MediaFormat f=MediaFormat.createVideoFormat(mime,w,h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_FRAME_RATE,fps);
        f.setInteger(MediaFormat.KEY_BIT_RATE,bitrate);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1); return f;
    }
    static MediaFormat videoFormat(int w,int h,int fps) {
        int target=(int)Math.min(Integer.MAX_VALUE,Math.max(2_000_000L,(long)w*h*fps/5));
        for(String mime:new String[]{MediaFormat.MIMETYPE_VIDEO_AVC,MediaFormat.MIMETYPE_VIDEO_HEVC}){
            for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()){
                if(!info.isEncoder()||!Arrays.asList(info.getSupportedTypes()).contains(mime))continue;
                try{
                    MediaCodecInfo.CodecCapabilities caps=info.getCapabilitiesForType(mime);MediaCodecInfo.VideoCapabilities video=caps.getVideoCapabilities();
                    if(video==null||!video.areSizeAndRateSupported(w,h,fps))continue;
                    android.util.Range<Integer> range=video.getBitrateRange();MediaFormat format=basicFormat(mime,w,h,fps,Math.max(range.getLower(),Math.min(target,range.getUpper())));
                    if(caps.isFormatSupported(format))return format;
                }catch(IllegalArgumentException ignored){}
            }
        }
        return basicFormat(MediaFormat.MIMETYPE_VIDEO_AVC,w,h,fps,target);
    }
    Surface getSurface() { return surface; }
    void start() {
        originNs=SystemClock.elapsedRealtimeNanos(); video.start();
        if(withAudio) audio.start();
        started=true;
        videoThread=new Thread(()->drainVideo(),"CameraDeck-H264"); videoThread.start();
        if(withAudio) { audioThread=new Thread(this::encodeAudio,"CameraDeck-AAC"); audioThread.start(); microphone.recording=this; }
    }
    void offerAudio(byte[] data,long endNs) {
        if(finishing || !started) return;
        if(!pcm.offer(new AudioPacket(data,endNs))) failAudio("Audio encoder cannot keep up");
    }
    void failAudio(String message) { if(error.isEmpty()) error="Audio: "+message; }
    void drainVideo() {
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        long deadline=Long.MAX_VALUE;
        long offsetUs=Long.MIN_VALUE,lastPts=-1;
        try {
            while(true) {
                if(finishing && deadline==Long.MAX_VALUE) deadline=System.nanoTime()+4_000_000_000L;
                if(System.nanoTime()>deadline) throw new IOException("Video encoder EOS timeout");
                int index=video.dequeueOutputBuffer(info,10000);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) track(false,video.getOutputFormat());
                else if(index>=0) {
                    try {
                        if(info.size>0 && (info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0) {
                            ByteBuffer buf=video.getOutputBuffer(index); buf.position(info.offset); buf.limit(info.offset+info.size);
                            if(offsetUs==Long.MIN_VALUE) offsetUs=realtimeTimestamps?originNs/1000:
                                info.presentationTimeUs-(SystemClock.elapsedRealtimeNanos()-originNs)/1000;
                            long pts=Math.max(lastPts+1,info.presentationTimeUs-offsetUs); lastPts=pts;
                            sample(false,buf,pts,info.flags);
                        }
                    } finally { video.releaseOutputBuffer(index,false); }
                    if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0) break;
                }
            }
        } catch(Exception e) { error="Video: "+e.getMessage(); }
    }
    void encodeAudio() {
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo(); boolean eos=false,outputEnded=false;
        long lastPts=0,deadline=Long.MAX_VALUE;
        AudioPacket next=null;
        try {
            while(!outputEnded) {
                if(finishing && deadline==Long.MAX_VALUE) deadline=System.nanoTime()+4_000_000_000L;
                if(System.nanoTime()>deadline) throw new IOException("Audio encoder EOS timeout");
                if(!eos) {
                    if(next==null) next=pcm.poll();
                    if(next!=null || finishing) {
                        int index=audio.dequeueInputBuffer(10000);
                        if(index>=0) {
                            if(next==null) { audio.queueInputBuffer(index,0,0,lastPts+20000,MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos=true; }
                            else {
                                ByteBuffer buffer=audio.getInputBuffer(index); buffer.clear(); buffer.put(next.data());
                                long durationNs=(long)next.data().length*1_000_000_000L/(AudioEngine.RATE*2);
                                long pts=Math.max(lastPts+1,(next.timeNs()-durationNs-originNs)/1000); lastPts=pts;
                                audio.queueInputBuffer(index,0,next.data().length,pts,0); next=null;
                            }
                        }
                    }
                }
                int index=audio.dequeueOutputBuffer(info,10000);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) track(true,audio.getOutputFormat());
                else if(index>=0) {
                    try {
                        if(info.size>0 && (info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0) {
                            ByteBuffer buf=audio.getOutputBuffer(index); buf.position(info.offset); buf.limit(info.offset+info.size);
                            sample(true,buf,info.presentationTimeUs,info.flags);
                        }
                    } finally { audio.releaseOutputBuffer(index,false); }
                    outputEnded=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                }
            }
        } catch(Exception e) { failAudio(e.getMessage()); }
    }
    synchronized void track(boolean isAudio,MediaFormat format) throws IOException {
        if(isAudio) audioTrack=muxer.addTrack(format); else videoTrack=muxer.addTrack(format);
        if(!muxing && videoTrack>=0 && (!withAudio || audioTrack>=0)) {
            muxer.start(); muxing=true;
            for(Sample sample:pending) write(sample.audio(),ByteBuffer.wrap(sample.data()),sample.pts(),sample.flags());
            pending.clear(); pendingBytes=0;
        }
    }
    synchronized void sample(boolean isAudio,ByteBuffer buffer,long pts,int flags) throws IOException {
        if(muxing) write(isAudio,buffer,pts,flags);
        else {
            if(pendingBytes+buffer.remaining()>16_000_000) throw new IOException("Muxer did not receive both track formats");
            byte[] bytes=new byte[buffer.remaining()]; buffer.get(bytes); pending.add(new Sample(isAudio,bytes,pts,flags)); pendingBytes+=bytes.length;
        }
    }
    void write(boolean isAudio,ByteBuffer buffer,long pts,int flags) {
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo(); info.set(buffer.position(),buffer.remaining(),pts,flags);
        muxer.writeSampleData(isAudio?audioTrack:videoTrack,buffer,info);
    }
    void stop() throws Exception {
        if(!started) return;
        if(microphone.recording==this) microphone.recording=null;
        finishing=true; video.signalEndOfInputStream();
        videoThread.join(5000); if(audioThread!=null)audioThread.join(5000);
        if(videoThread.isAlive() || (audioThread!=null && audioThread.isAlive())) throw new IOException("Encoder did not stop");
        if(!muxing) throw new IOException("Recording contains no complete tracks");
        if(!error.isEmpty()) throw new IOException(error);
    }
    @Override public void close() {
        if(released) return; released=true; finishing=true;
        if(microphone.recording==this) microphone.recording=null;
        try { if(started)video.stop(); } catch(Exception ignored) {}
        try { if(started && audio!=null)audio.stop(); } catch(Exception ignored) {}
        video.release(); if(audio!=null)audio.release(); surface.release();
        try { if(muxing)muxer.stop(); } catch(Exception e) { if(error.isEmpty())error="MP4 finalization failed"; }
        muxer.release();
    }
}
