package io.github.prostoodin1.cameradeck;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.net.*;
import java.util.*;

public class MainActivity extends Activity {
    final Handler ui = new Handler(Looper.getMainLooper());
    TextView state, code, fingerprint, detail;
    ImageView preview;
    EditText name;
    Button start, install;
    CheckBox localPreview, eco;
    boolean resumed;
    long sequence = -1;
    OrientationEventListener orientation;
    final Runnable refresh = new Runnable() { public void run() { update(); ui.postDelayed(this, 500); } };
    TextView text(String value, int size) { TextView t = new TextView(this); t.setText(value); t.setTextColor(Color.rgb(225,233,242)); t.setTextSize(size); t.setPadding(0,12,0,12); return t; }
    Button button(String label, Runnable action) { Button b = new Button(this); b.setText(label); b.setOnClickListener(v -> action.run()); return b; }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(28,30,28,24); root.setBackgroundColor(Color.rgb(14,20,30));
        ScrollView scroll = new ScrollView(this); scroll.addView(root); setContentView(scroll);
        root.addView(text("CAMERA DECK", 28)); root.addView(text("Камера для Windows · " + BuildConfig.VERSION_NAME, 14));
        name = new EditText(this); name.setTextColor(Color.WHITE); name.setSingleLine(); name.setHint("Имя камеры"); name.setText(getSharedPreferences("deck",0).getString("name", Build.MODEL)); root.addView(name);
        root.addView(button("Сохранить имя", () -> { String value = name.getText().toString().trim(); if (!value.isEmpty() && value.length() <= 60) getSharedPreferences("deck",0).edit().putString("name",value).apply(); }));
        start = button("Включить камеру", this::toggle); root.addView(start);
        eco = new CheckBox(this); eco.setText("Экономный предпросмотр · до 5 кадров/с"); eco.setTextColor(Color.WHITE);
        eco.setChecked(getSharedPreferences("deck",0).getBoolean("eco",true));
        eco.setOnCheckedChangeListener((v,checked) -> { getSharedPreferences("deck",0).edit().putBoolean("eco",checked).apply(); CameraService s=CameraService.active; if(s!=null && s.engine!=null) s.engine.setEco(checked); }); root.addView(eco);
        localPreview = new CheckBox(this); localPreview.setText("Показывать изображение на телефоне"); localPreview.setTextColor(Color.WHITE);
        localPreview.setOnCheckedChangeListener((v,checked) -> { CameraService s=CameraService.active; if(!checked && s!=null && s.engine!=null) s.engine.hideLocal(); if(!checked) preview.setImageDrawable(null); }); root.addView(localPreview);
        root.addView(button("Продолжить в фоне", () -> { moveTaskToBack(true); }));
        root.addView(text("Экран можно выключить. Без просмотра и записи камера переходит в ожидание. Для остановки используйте уведомление Camera Deck.",13));
        state = text("Камера выключена", 16); root.addView(state);
        code = text("", 36); root.addView(code);
        fingerprint = text("", 13); fingerprint.setTextIsSelectable(true); root.addView(fingerprint);
        root.addView(button("Новый PIN", () -> { CameraService s = CameraService.active; if(s != null) s.rotatePin(); }));
        root.addView(button("Отключить все компьютеры", () -> { CameraService s = CameraService.active; if(s != null) s.revoke(); }));
        preview = new ImageView(this); preview.setScaleType(ImageView.ScaleType.FIT_CENTER); root.addView(preview, new LinearLayout.LayoutParams(-1,500));
        detail = text("Откройте Camera Deck на Windows в той же Wi-Fi сети. При первом подключении сравните отпечаток сертификата и введите PIN.", 14); root.addView(detail);
        install = button("Установить обновление", this::installUpdate); root.addView(install);
        root.addView(text("Для звука разрешите микрофон. Режим фото/видео, разрешение, FPS и формат фото выбираются на Windows. Оригиналы сохраняются на телефоне.\nПри удалении приложения локальные записи удаляются — сначала скачайте их.", 13));
        orientation = new OrientationEventListener(this) { public void onOrientationChanged(int degrees) {
            CameraService s = CameraService.active;
            if (degrees >= 0 && s != null && s.engine != null && !s.engine.recording) {
                s.engine.deviceRotation = ((degrees + 45) / 90 * 90) % 360;
                try {
                    int sensor = s.engine.characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION);
                    boolean front = s.engine.characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT;
                    s.engine.rotation = (sensor + (front ? s.engine.deviceRotation : -s.engine.deviceRotation) + 360) % 360;
                } catch (Exception ignored) {}
            }
        } };
    }
    void toggle() {
        if (CameraService.active != null) { stopService(new Intent(this, CameraService.class)); return; }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO},10); return; }
        startCameraService();
    }
    void startCameraService() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},11);
        startForegroundService(new Intent(this, CameraService.class));
    }
    @Override public void onRequestPermissionsResult(int req, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(req,permissions,results);
        if (req == 10 && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCameraService();
    }
    void update() {
        CameraService s = CameraService.active;
        start.setText(s == null ? "Включить камеру" : "Выключить камеру");
        install.setEnabled(new File(getFilesDir(),"update.apk").isFile() && (s == null || (s.engine!=null && !s.engine.recording && !s.engine.photoPending)));
        if (s == null || s.engine == null) { state.setText(CameraService.lastStartupError.isEmpty() ? "Камера выключена" : CameraService.lastStartupError); code.setText(""); preview.setImageDrawable(null); sequence = -1; return; }
        if (resumed && localPreview.isChecked()) s.engine.demandLocal();
        if (System.currentTimeMillis() > s.pinExpires) s.rotatePin();
        state.setText((s.engine.recording ? "● ЗАПИСЬ · " : "") + (s.engine.powerState.equals("standby") ? "Экономное ожидание" : s.engine.ready ? "Камера доступна" : "Открываем камеру…") + " · подключений: " + s.tokens.size());
        code.setText("PIN  " + s.pin);
        String fp = s.fingerprint;
        fingerprint.setText("Адрес: " + localAddresses() + ":39812\nSHA-256 TLS:\n" + fp.replaceAll("(.{8})", "$1 "));
        String message = s.engine.error.isEmpty() ? s.error : s.engine.error;
        detail.setText((message.isEmpty() ? "ISO " + s.engine.measuredIso + " · выдержка " + String.format(Locale.US,"%.2f",s.engine.measuredExposure/1000000.0) + " мс\n" + s.engine.lastFile : message) + "\n" + s.updateMessage);
        if (localPreview.isChecked() && s.engine.frameSequence != sequence) {
            sequence = s.engine.frameSequence; byte[] bytes = s.engine.frame;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes,0,bytes.length);
            if (bitmap != null) { Matrix matrix = new Matrix(); matrix.postRotate(s.engine.rotation); preview.setImageBitmap(Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true)); }
        }
    }
    String localAddresses() {
        List<String> ips = new ArrayList<>();
        try { for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) for (InetAddress a : Collections.list(n.getInetAddresses())) if (a instanceof Inet4Address && a.isSiteLocalAddress()) ips.add(a.getHostAddress()); } catch(Exception ignored) {}
        return String.join(", ",ips);
    }
    void installUpdate() {
        CameraService s = CameraService.active;
        if (s != null && s.engine!=null && (s.engine.recording || s.engine.photoPending)) return;
        if (!getPackageManager().canRequestPackageInstalls()) { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:"+getPackageName()))); return; }
        stopService(new Intent(this, CameraService.class));
        Intent installIntent = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://"+getPackageName()+".updates/update.apk"),"application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(installIntent);
    }
    @Override protected void onResume() { super.onResume(); resumed=true; orientation.enable(); ui.post(refresh); }
    @Override protected void onPause() { resumed=false; CameraService s=CameraService.active; if(s!=null && s.engine!=null) s.engine.hideLocal(); ui.removeCallbacks(refresh); orientation.disable(); super.onPause(); }
}
