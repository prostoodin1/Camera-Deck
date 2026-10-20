package io.github.prostoodin1.cameradeck;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.SystemClock;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** One microphone shared by live monitoring and the file encoder. */
final class AudioEngine implements AutoCloseable {
    static final int RATE = 48000, BYTES_PER_CHUNK = 1920;
    final Context context;
    final boolean foregroundMicrophone;
    final Set<Listener> listeners = ConcurrentHashMap.newKeySet();
    volatile AvRecorder recording;
    volatile boolean running = true, capturing;
    volatile String error = "";
    volatile double levelDb=-96;
    final Thread worker;
    AudioRecord mic;
    long framesRead;
    AudioEngine(Context c) { context=c;foregroundMicrophone=c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED; worker=new Thread(this::loop,"CameraDeck-microphone"); worker.start(); }
    boolean available() { return foregroundMicrophone && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED; }
    boolean wanted() { return recording!=null || !listeners.isEmpty(); }
    synchronized Listener subscribe() throws IOException {
        if(!available()) throw new IOException("Разрешите микрофон на телефоне и перезапустите камеру");
        if(listeners.size()>=2) throw new IOException("Не более двух слушателей микрофона");
        Listener listener=new Listener(); listeners.add(listener); return listener;
    }
    void loop() {
        while(running) {
            try {
                if(!wanted()) { releaseMic(); Thread.sleep(100); continue; }
                if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) throw new IOException("Microphone permission missing");
                if(mic==null) {
                    int size=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                    if(size<=0) throw new IOException("48kHz mono microphone unavailable");
                    mic=new AudioRecord(MediaRecorder.AudioSource.CAMCORDER,RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(size*2,BYTES_PER_CHUNK*4));
                    if(mic.getState()!=AudioRecord.STATE_INITIALIZED) throw new IOException("Microphone initialization failed");
                    mic.startRecording(); framesRead=0; capturing=true; error="";
                }
                byte[] pcm=new byte[BYTES_PER_CHUNK]; int offset=0;
                while(offset<pcm.length && running && wanted()) {
                    int n=mic.read(pcm,offset,pcm.length-offset,AudioRecord.READ_BLOCKING);
                    if(n<=0) throw new IOException("Microphone read failed: "+n);
                    offset+=n;
                }
                if(offset!=pcm.length) continue;
                double energy=0;for(int i=0;i<pcm.length;i+=2){short sample=(short)((pcm[i]&255)|(pcm[i+1]<<8));energy+=(double)sample*sample;}
                levelDb=Math.max(-96,20*Math.log10(Math.max(1e-8,Math.sqrt(energy/(pcm.length/2))/32768.0)));
                long now=SystemClock.elapsedRealtimeNanos();
                framesRead+=pcm.length/2;
                AudioTimestamp timestamp=new AudioTimestamp();
                if(mic.getTimestamp(timestamp,AudioTimestamp.TIMEBASE_BOOTTIME)==AudioRecord.SUCCESS && timestamp.nanoTime>0)
                    now=timestamp.nanoTime+(framesRead-timestamp.framePosition)*1_000_000_000L/RATE;
                AvRecorder target=recording;
                if(target!=null) target.offerAudio(pcm,now);
                for(Listener listener:listeners) {
                    if(SystemClock.elapsedRealtime()-listener.lastRead>5000) { listener.close(); continue; }
                    if(!listener.queue.offer(pcm)) { listener.queue.poll(); listener.queue.offer(pcm); }
                }
            } catch(Exception e) {
                error=e.getMessage()==null?e.toString():e.getMessage();
                AvRecorder target=recording; if(target!=null) target.failAudio(error);
                for(Listener listener:listeners) listener.close();
                releaseMic();
                try { Thread.sleep(250); } catch(InterruptedException ignored) {}
            }
        }
        releaseMic();
    }
    void releaseMic() {
        capturing=false;
        if(mic!=null) { try { mic.stop(); } catch(Exception ignored) {} mic.release(); mic=null; }
    }
    final class Listener extends InputStream {
        final ArrayBlockingQueue<byte[]> queue=new ArrayBlockingQueue<>(5);
        volatile long lastRead=SystemClock.elapsedRealtime();
        volatile boolean closed;
        byte[] current; int offset;
        @Override public int read() throws IOException { byte[] one=new byte[1]; return read(one,0,1)<0?-1:one[0]&255; }
        @Override public int read(byte[] data,int start,int length) throws IOException {
            if(length==0) return 0;
            while(!closed && running) {
                lastRead=SystemClock.elapsedRealtime();
                if(current!=null && offset<current.length) { int n=Math.min(length,current.length-offset); System.arraycopy(current,offset,data,start,n); offset+=n; return n; }
                try { current=queue.poll(1,TimeUnit.SECONDS); offset=0; } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            }
            return -1;
        }
        @Override public void close() { closed=true; listeners.remove(this); queue.clear(); }
    }
    @Override public void close() {
        running=false; recording=null; for(Listener listener:listeners) listener.close();
        worker.interrupt(); try { worker.join(2000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
