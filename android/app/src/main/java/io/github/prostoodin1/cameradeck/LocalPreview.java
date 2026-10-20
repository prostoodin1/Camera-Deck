package io.github.prostoodin1.cameradeck;

import android.graphics.Bitmap;
import android.os.*;
import java.util.concurrent.atomic.AtomicReference;

/** One latest input and one UI handoff. No JPEG, polling, or UI-thread decoding. */
final class LocalPreview implements AutoCloseable {
    record Frame(byte[] pixels,int width,int height,int rotation,long received){}
    interface Display {void show(Bitmap bitmap,int rotation,double conversionMs,long received);}
    final HandlerThread thread=new HandlerThread("CameraDeck-local-display");
    final Handler worker,ui=new Handler(Looper.getMainLooper());
    final AtomicReference<Frame> latest=new AtomicReference<>();
    final Display display;
    boolean scheduled;
    volatile boolean active,closed;
    int generation;
    int[] colors=new int[0];
    LocalPreview(Display display){this.display=display;thread.start();worker=new Handler(thread.getLooper());}
    synchronized void active(boolean value){active=value;generation++;latest.set(null);}
    synchronized void offer(byte[] data,int w,int h,int rotation){
        if(closed||!active)return;
        latest.set(new Frame(data,w,h,rotation,SystemClock.elapsedRealtimeNanos()));
        schedule();
    }
    synchronized void schedule(){if(!scheduled&&active&&!closed){scheduled=true;int epoch=generation;worker.post(()->convert(epoch));}}
    void convert(int epoch){
        Frame frame=latest.getAndSet(null);
        if(frame==null||closed||!active){complete();return;}
        long start=SystemClock.elapsedRealtimeNanos();
        try{
            if(colors.length!=frame.width*frame.height)colors=new int[frame.width*frame.height];
            Nv21.argb(frame.pixels,frame.width,frame.height,colors);
            Bitmap bitmap=Bitmap.createBitmap(colors,frame.width,frame.height,Bitmap.Config.ARGB_8888);
            double ms=(SystemClock.elapsedRealtimeNanos()-start)/1e6;
            ui.post(()->{try{if(!closed&&active&&generation==epoch)display.show(bitmap,frame.rotation,ms,frame.received);}finally{complete();}});
        }catch(RuntimeException|OutOfMemoryError error){complete();}
    }
    synchronized void complete(){scheduled=false;if(latest.get()!=null)schedule();}
    public synchronized void close(){closed=true;active=false;generation++;latest.set(null);thread.quitSafely();}
}
