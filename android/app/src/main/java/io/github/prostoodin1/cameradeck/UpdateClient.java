package io.github.prostoodin1.cameradeck;

import android.content.Context;
import org.json.*;
import java.net.*;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

final class UpdateClient {
    static final AtomicBoolean working=new AtomicBoolean();
    static volatile String message="";
    static volatile long lastCheck;
    static final long CHECK_MS=900000;
    static InputStream open(String value)throws Exception{
        URL url=new URL(value);
        for(int redirects=0;redirects<6;redirects++){
            String host=url.getHost();if(!url.getProtocol().equals("https")||!(host.equals("github.com")||host.equals("api.github.com")||host.endsWith(".githubusercontent.com")))throw new SecurityException("Недопустимый адрес обновления");
            HttpsURLConnection c=(HttpsURLConnection)url.openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setRequestProperty("User-Agent","CameraDeck");
            int code=c.getResponseCode();if(code>=300&&code<400){String location=c.getHeaderField("Location");c.disconnect();url=new URL(url,location);continue;}
            if(code!=200){c.disconnect();throw new IOException("GitHub HTTP "+code);}
            return new FilterInputStream(c.getInputStream()){@Override public void close()throws IOException{try{super.close();}finally{c.disconnect();}}};
        }throw new IOException("Слишком много перенаправлений");
    }
    static byte[] read(String url,int max)throws Exception{
        try(InputStream in=open(url);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[16384];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>max)throw new IOException("Слишком большой ответ");out.write(buffer,0,n);}return out.toByteArray();
        }
    }
    static void check(Context context,boolean manual){
        Context c=context.getApplicationContext();long now=android.os.SystemClock.elapsedRealtime();
        if(!manual&&(!c.getSharedPreferences("deck",0).getBoolean("autoUpdates",true)||now-lastCheck<CHECK_MS&&lastCheck!=0))return;
        if(!working.compareAndSet(false,true))return;lastCheck=now;message="Проверяем обновления…";
        new Thread(()->{
            File temp=new File(c.getCacheDir(),"aurora-update.partial");
            try{
                JSONArray releases=new JSONArray(new String(read("https://api.github.com/repos/prostoodin1/Camera-Deck/releases?per_page=30",4_000_000),StandardCharsets.UTF_8));
                JSONObject selected=null,asset=null;String envelope=null;int highest=BuildConfig.VERSION_CODE;
                for(int i=0;i<releases.length();i++){
                    JSONObject release=releases.getJSONObject(i);if(release.optBoolean("draft")||!release.optString("tag_name").startsWith(BuildConfig.CLIENT_CHANNEL?"camera-deck-client-v":BuildConfig.PREVIEW_BUILD?"camera-deck-":"camera-deck-v"))continue;
                    JSONArray assets=release.getJSONArray("assets");JSONObject manifestAsset=null;for(int j=0;j<assets.length();j++)if(assets.getJSONObject(j).optString("name").equals("camera-deck-update.json"))manifestAsset=assets.getJSONObject(j);if(manifestAsset==null)continue;
                    try{
                        String signed=new String(read(manifestAsset.getString("browser_download_url"),65536),StandardCharsets.UTF_8);JSONObject manifest=UpdateVerifier.validateManifest(c,signed);if(manifest.getInt("code")<=highest)continue;
                        JSONObject apk=manifest.getJSONObject("android");for(int j=0;j<assets.length();j++){JSONObject candidate=assets.getJSONObject(j);if(candidate.optString("name").equals(apk.getString("name"))&&candidate.getLong("size")==apk.getLong("size")){selected=manifest;asset=candidate;envelope=signed;highest=manifest.getInt("code");}}
                    }catch(SecurityException ignored){}
                }
                if(selected==null){if(new File(c.getFilesDir(),"update.apk").isFile())UpdateInstaller.install(c,false);else message="Установлена последняя версия";return;}
                CameraService service=CameraService.active;if(service!=null&&(service.engine==null||service.engine.recording||service.engine.photoPending)){message="Обновление доступно. Загрузка после остановки записи.";lastCheck=android.os.SystemClock.elapsedRealtime()-CHECK_MS+60000;return;}
                long expected=selected.getJSONObject("android").getLong("size");if(expected<=0||expected>200_000_000)throw new SecurityException("Недопустимый размер APK");
                message="Загружаем проверенное обновление…";
                try(InputStream in=open(asset.getString("browser_download_url"));OutputStream out=new FileOutputStream(temp)){byte[] buffer=new byte[65536];long total=0;int n;while((n=in.read(buffer))!=-1){total+=n;if(total>expected)throw new SecurityException("APK больше ожидаемого");out.write(buffer,0,n);}if(total!=expected)throw new IOException("Неполная загрузка APK");}
                UpdateVerifier.stage(c,envelope,temp);message="Патч проверен и готов к автоустановке";UpdateInstaller.install(c,false);
            }catch(Exception e){message="Обновления: "+e.getMessage();}finally{temp.delete();working.set(false);}
        },"CameraDeck-updates").start();
    }
}
