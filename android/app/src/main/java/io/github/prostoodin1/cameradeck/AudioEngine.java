package io.github.prostoodin1.cameradeck;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.SystemClock;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

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
    volatile int preferredInput;
    volatile int routedInput;
    volatile String routedName="",routeWarning="";
    volatile long routeVersion;
    long framesRead;
    AudioEngine(Context c) { context=c;foregroundMicrophone=c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED; worker=new Thread(this::loop,"CameraDeck-microphone"); worker.start(); }
    boolean available() { return foregroundMicrophone && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED; }
    boolean wanted() { return recording!=null || !listeners.isEmpty(); }
    AudioDeviceInfo input(int id) {
        for(AudioDeviceInfo device:context.getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_INPUTS))if(device.getId()==id)return device;
        return null;
    }
    static String inputName(AudioDeviceInfo device) {
        String kind;
        switch(device.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:kind="Микрофон телефона";break;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:kind="Проводная гарнитура";break;
            case AudioDeviceInfo.TYPE_USB_DEVICE:case AudioDeviceInfo.TYPE_USB_HEADSET:kind="USB-микрофон";break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:case AudioDeviceInfo.TYPE_BLE_HEADSET:kind="Bluetooth-гарнитура";break;
            default:kind="Аудиовход";
        }
        return kind+" · "+device.getProductName();
    }
    JSONArray inputs() throws JSONException {
        JSONArray result=new JSONArray();
        for(AudioDeviceInfo device:context.getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            int type=device.getType();
            if(type!=AudioDeviceInfo.TYPE_BUILTIN_MIC&&type!=AudioDeviceInfo.TYPE_WIRED_HEADSET&&type!=AudioDeviceInfo.TYPE_USB_DEVICE&&type!=AudioDeviceInfo.TYPE_USB_HEADSET&&type!=AudioDeviceInfo.TYPE_BLUETOOTH_SCO&&type!=AudioDeviceInfo.TYPE_BLE_HEADSET)continue;
            result.put(new JSONObject().put("id",device.getId()).put("name",inputName(device)).put("type",type));
        }
        return result;
    }
    void selectInput(int id) {
        if(recording!=null)throw new IllegalStateException("Остановите запись перед сменой микрофона");
        if(id!=0&&input(id)==null)throw new IllegalArgumentException("Микрофон отключён; обновите список");
        preferredInput=id;routeWarning="";routeVersion++;
    }
    synchronized Listener subscribe() throws IOException {
        if(!available()) throw new IOException("Разрешите микрофон на телефоне и перезапустите камеру");
        if(listeners.size()>=2) throw new IOException("Не более двух слушателей микрофона");
        Listener listener=new Listener(); listeners.add(listener); return listener;
    }
    void loop() {
        long appliedVersion=-1;
        while(running) {
            try {
                if(!wanted()) { releaseMic(); Thread.sleep(100); continue; }
                if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) throw new IOException("Microphone permission missing");
                if(mic!=null&&appliedVersion!=routeVersion)releaseMic();
                if(mic==null) {
                    long requestedVersion=routeVersion;
                    int size=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                    if(size<=0) throw new IOException("48kHz mono microphone unavailable");
                    mic=new AudioRecord(MediaRecorder.AudioSource.CAMCORDER,RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(size*2,BYTES_PER_CHUNK*4));
                    if(mic.getState()!=AudioRecord.STATE_INITIALIZED) throw new IOException("Microphone initialization failed");
                    int selected=preferredInput;AudioDeviceInfo preferred=selected==0?null:input(selected);
                    if(selected!=0&&preferred==null)routeWarning="Выбранный микрофон отключён; используется системный вход";
                    if(!mic.setPreferredDevice(preferred))routeWarning="Android не принял выбранный микрофон";
                    appliedVersion=requestedVersion;
                    mic.startRecording(); framesRead=0; capturing=true; error="";
                }
                byte[] pcm=new byte[BYTES_PER_CHUNK]; int offset=0;
                while(offset<pcm.length && running && wanted()) {
                    int n=mic.read(pcm,offset,pcm.length-offset,AudioRecord.READ_BLOCKING);
                    if(n<=0) throw new IOException("Microphone read failed: "+n);
                    offset+=n;
                }
                if(offset!=pcm.length) continue;
                AudioDeviceInfo actual=mic.getRoutedDevice();routedInput=actual==null?0:actual.getId();routedName=actual==null?"":inputName(actual);
                if(preferredInput!=0&&routedInput!=0&&routedInput!=preferredInput)routeWarning="Android использует другой вход: "+routedName;
                else if(routedInput==preferredInput||preferredInput==0)routeWarning="";
                double energy=0;for(int i=0;i<pcm.length;i+=2){short sample=(short)((pcm[i]&255)|(pcm[i+1]<<8));energy+=(double)sample*sample;}
                levelDb=Math.max(-96,20*Math.log10(Math.max(1e-8,Math.sqrt(energy/(pcm.length/2))/32768.0)));
                long now=SystemClock.elapsedRealtimeNanos();
                framesRead+=pcm.length/2;
                AudioTimestamp timestamp=new AudioTimestamp();
                if(mic.getTimestamp(timestamp,AudioTimestamp.TIMEBASE_BOOTTIME)==AudioRecord.SUCCESS && timestamp.nanoTime>0)
                    now=timestamp.nanoTime+(framesRead-timestamp.framePosition)*1_000_000_000L/RATE;
                AvRecorder target=recording;
                if(target!=null){target.microphoneRecovered();target.offerAudio(pcm,now);}
                for(Listener listener:listeners) {
                    if(SystemClock.elapsedRealtime()-listener.lastRead>5000) { listener.close(); continue; }
                    if(!listener.queue.offer(pcm)) { listener.queue.poll(); listener.queue.offer(pcm); }
                }
            } catch(Exception e) {
                error=e.getMessage()==null?e.toString():e.getMessage();
                AvRecorder target=recording; if(target!=null) target.microphoneGap(error);
                for(Listener listener:listeners) listener.close();
                releaseMic();
                // Keep the existing AAC timeline alive during a transient microphone failure.
                // Silence is explicit in telemetry; this never fabricates a successful mic signal.
                for(int i=0;i<12&&running;i++) {
                    try { Thread.sleep(20); } catch(InterruptedException ignored) { break; }
                    target=recording;
                    if(target!=null)target.offerAudio(new byte[BYTES_PER_CHUNK],SystemClock.elapsedRealtimeNanos());
                }
            }
        }
        releaseMic();
    }
    void releaseMic() {
        capturing=false;routedInput=0;routedName="";
        AudioRecord previous=mic;mic=null;
        if(previous!=null){try{previous.stop();}catch(Exception ignored){}try{previous.release();}catch(Exception ignored){}}
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
