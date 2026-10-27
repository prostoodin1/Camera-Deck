package io.github.prostoodin1.cameradeck;
import android.content.*;
import java.io.File;
public final class UpdateBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent intent){
        UpdateSchedule.register(c);
        if(Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())){
            c.getSharedPreferences("deck",0).edit().remove("updateSession").apply();UpdateInstaller.committing=false;
            File apk=new File(c.getFilesDir(),"update.apk");android.content.pm.PackageInfo info=c.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(),0);
            if(info!=null&&info.getLongVersionCode()<=BuildConfig.VERSION_CODE){apk.delete();new File(c.getFilesDir(),"update-manifest.json").delete();}
            UpdateClient.message="Camera Deck обновлён до "+BuildConfig.VERSION_NAME;
        }
    }
}
