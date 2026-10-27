package io.github.prostoodin1.cameradeck;
import android.app.job.*;
import android.content.*;
/** OS-scheduled checks while the application is closed; no permanent polling service. */
public final class UpdateSchedule extends JobService {
    volatile boolean stopped;
    static void register(Context c){
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);
        if(!c.getSharedPreferences("deck",0).getBoolean("autoUpdates",true)){scheduler.cancel(39813);return;}
        scheduler.schedule(new JobInfo.Builder(39813,new ComponentName(c,UpdateSchedule.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setPeriodic(6*60*60*1000L).setPersisted(true).build());
    }
    @Override public boolean onStartJob(JobParameters p){
        stopped=false;
        new Thread(()->{try{
            UpdateClient.check(this,false);long deadline=android.os.SystemClock.elapsedRealtime()+240000;
            while(!stopped&&UpdateClient.working.get()&&android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(250);
            if(!stopped){UpdateInstaller.install(this,false);while(UpdateInstaller.working.get()&&android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(250);}
        }catch(InterruptedException ignored){Thread.currentThread().interrupt();}finally{jobFinished(p,false);}},"CameraDeck-update-job").start();return true;
    }
    @Override public boolean onStopJob(JobParameters p){stopped=true;return true;}
}
