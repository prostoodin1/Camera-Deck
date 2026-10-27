package io.github.prostoodin1.cameradeck;
import android.content.*;
import android.content.pm.PackageInstaller;
import java.io.File;

/** Explicit private PackageInstaller callback. Pending OS confirmation remains visible. */
public final class UpdateInstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent intent){
        if(!((c.getPackageName()+".INSTALL_RESULT").equals(intent.getAction())))return;
        int expected=c.getSharedPreferences("deck",0).getInt("updateSession",-1);
        if(expected<0||intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID,-2)!=expected)return;
        int status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION){
            Intent confirm=intent.getParcelableExtra(Intent.EXTRA_INTENT);
            UpdateInstaller.committing=false;
            UpdateClient.message="Android просит подтвердить установку патча";
            if(confirm!=null){UpdateInstaller.confirmation=confirm;UpdateInstaller.notifyAction(c,confirm,UpdateClient.message);}return;
        }
        UpdateInstaller.committing=false;UpdateInstaller.confirmation=null;c.getSharedPreferences("deck",0).edit().remove("updateSession").apply();
        if(status==PackageInstaller.STATUS_SUCCESS){
            new File(c.getFilesDir(),"update.apk").delete();new File(c.getFilesDir(),"update-manifest.json").delete();UpdateClient.message="Патч установлен";
        }else{c.getSharedPreferences("deck",0).edit().putLong("updateSnoozeUntil",System.currentTimeMillis()+6*3600000L).apply();UpdateClient.message="Патч не установлен: "+intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)+". Текущая версия и данные сохранены";}
    }
}
