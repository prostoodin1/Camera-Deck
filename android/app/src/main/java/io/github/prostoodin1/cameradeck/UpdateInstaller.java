package io.github.prostoodin1.cameradeck;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Verified self-updates only. Never asks Android to bypass its installer policy. */
final class UpdateInstaller {
    static final AtomicBoolean working=new AtomicBoolean();
    static volatile boolean committing;
    static volatile Intent confirmation;
    static final Object CAPTURE_GATE=new Object();
    static boolean busy(){
        CameraService s=CameraService.active;
        if(s!=null&&(s.engine==null||s.engine.recording||s.engine.photoPending||s.engine.busy||!"standby".equals(s.engine.powerState)||s.viewers.get()>0||s.audio!=null&&s.audio.wanted()))return true;
        synchronized(GalleryPublisher.inFlight){return PortraitService.active!=null||!GalleryPublisher.inFlight.isEmpty();}
    }
    static void notifyAction(Context c,Intent action,String message){
        NotificationManager manager=c.getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("updates","Обновления Camera Deck",NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open=PendingIntent.getActivity(c,33,action.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        manager.notify(33,new Notification.Builder(c,"updates").setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Camera Deck · обновление").setContentText(message).setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build());
    }
    static void install(Context context,boolean explicit){
        Context c=context.getApplicationContext();
        if(!explicit&&!c.getSharedPreferences("deck",0).getBoolean("autoUpdates",true))return;
        File apk=new File(c.getFilesDir(),"update.apk"),manifest=new File(c.getFilesDir(),"update-manifest.json");
        if(!apk.isFile()||!manifest.isFile()||!working.compareAndSet(false,true))return;
        new Thread(()->{
            PackageInstaller installer=c.getPackageManager().getPackageInstaller();int sessionId=-1;boolean committed=false;
            try{
                if(!explicit&&System.currentTimeMillis()<c.getSharedPreferences("deck",0).getLong("updateSnoozeUntil",0))return;
                int prior=c.getSharedPreferences("deck",0).getInt("updateSession",-1);
                if(prior>=0&&installer.getSessionInfo(prior)!=null){
                    if(!explicit||committing)return;
                    // Recover a confirmation session whose in-memory Intent was lost on process death.
                    installer.abandonSession(prior);
                }
                if(prior>=0)c.getSharedPreferences("deck",0).edit().remove("updateSession").apply();
                if(busy()){UpdateClient.message="Патч загружен. Установка после завершения работы камеры";return;}
                if(!c.getPackageManager().canRequestPackageInstalls()){
                    UpdateClient.message="Один раз разрешите Camera Deck устанавливать обновления";
                    Intent settings=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+c.getPackageName()));notifyAction(c,settings,UpdateClient.message);
                    if(explicit&&context instanceof Activity)context.startActivity(settings);return;
                }
                String envelope=new String(java.nio.file.Files.readAllBytes(manifest.toPath()),java.nio.charset.StandardCharsets.UTF_8);
                // Recheck the signed payload, APK hash, package and current signing certificate.
                PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);params.setAppPackageName(c.getPackageName());params.setSize(apk.length());
                if(Build.VERSION.SDK_INT>=31)params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                sessionId=installer.createSession(params);
                synchronized(UpdateVerifier.class){
                    UpdateVerifier.verifyApk(c,envelope,apk);
                    try(PackageInstaller.Session session=installer.openSession(sessionId);InputStream in=new FileInputStream(apk);OutputStream out=session.openWrite("base.apk",0,apk.length())){
                        byte[] block=new byte[256*1024];int count;while((count=in.read(block))!=-1)out.write(block,0,count);session.fsync(out);
                    }
                }
                synchronized(CAPTURE_GATE){if(busy()){UpdateClient.message="Патч проверен. Установка отложена до простоя";return;}committing=true;}
                // No recording can begin once commit is armed; capture commands check this flag.
                c.getSharedPreferences("deck",0).edit().putInt("updateSession",sessionId).commit();
                Intent status=new Intent(c,UpdateInstallReceiver.class).setAction(c.getPackageName()+".INSTALL_RESULT");
                PendingIntent result=PendingIntent.getBroadcast(c,sessionId,status,PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0));
                try(PackageInstaller.Session session=installer.openSession(sessionId)){session.commit(result.getIntentSender());}
                committed=true;UpdateClient.message="Устанавливаем проверенный патч…";
            }catch(Exception failure){UpdateClient.message="Установка: "+failure.getMessage();}
            finally{if(!committed){committing=false;if(sessionId!=-1)try{installer.abandonSession(sessionId);}catch(Exception ignored){}}working.set(false);}
        },"CameraDeck-self-update").start();
    }
}
