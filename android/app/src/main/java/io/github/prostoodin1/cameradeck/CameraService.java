package io.github.prostoodin1.cameradeck;

import android.app.*;
import android.content.*;
import android.os.*;
import android.security.keystore.*;
import org.json.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import java.util.concurrent.*;
import fi.iki.elonen.NanoHTTPD;

public class CameraService extends Service {
    static volatile CameraService active;
    static volatile String lastStartupError = "";
    static final int PORT = 39812, DISCOVERY = 39811;
    CameraEngine engine;
    AudioEngine audio;
    Api api;
    DatagramSocket discovery;
    PowerManager.WakeLock wake;
    final Handler maintenance = new Handler(Looper.getMainLooper());
    final java.util.concurrent.atomic.AtomicInteger transfers = new java.util.concurrent.atomic.AtomicInteger();
    long wakeRenewed;
    String notifiedState = "";
    final Runnable powerTick = new Runnable() {
        public void run() {
            if (!running) return;
            tokens.entrySet().removeIf(e -> e.getValue() < System.currentTimeMillis());
            updatePower();
            String state = engine.powerState+(audio!=null && audio.wanted()?"|mic":"");
            if (!state.equals(notifiedState)) {
                notifiedState = state;
                NotificationManager nm = getSystemService(NotificationManager.class);
                nm.notify(1, notification(state));
            }
            maintenance.postDelayed(this, 5000);
        }
    };
    volatile boolean running;
    volatile String pin, fingerprint = "", error = "", updateMessage = "";
    volatile long pinExpires;
    final Map<String, Long> tokens = new ConcurrentHashMap<>();
    final Deque<Long> attempts = new ArrayDeque<>();
    final SecureRandom random = new SecureRandom();
    String id;
    private String name() { return getSharedPreferences("deck", 0).getString("name", Build.MODEL); }
    void revoke() { tokens.clear(); rotatePin(); }
    void rotatePin() { pin = String.format(Locale.US, "%04d", random.nextInt(10000)); pinExpires = System.currentTimeMillis() + 300_000; }

    synchronized void updatePower() {
        if (wake == null || engine == null) return;
        long now = SystemClock.elapsedRealtime();
        boolean needed = running && (engine.recording || engine.busy || engine.photoPending || engine.power.preview(now) || (audio!=null && audio.wanted()) || transfers.get() > 0);
        if (needed && (!wake.isHeld() || now - wakeRenewed > 30000)) { wake.acquire(90000); wakeRenewed = now; }
        else if (!needed && wake.isHeld()) wake.release();
    }

    Notification notification(String state) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, CameraService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        String description = state.startsWith("record") ? "Идёт запись на телефон" : state.startsWith("standby") ? "Экономное ожидание · камера не снимает" : "Предпросмотр камеры по Wi-Fi";
        if(audio!=null && audio.wanted())description+=" · микрофон включён";
        return new Notification.Builder(this, "camera").setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Camera Deck").setContentText(description).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null, "Остановить", stop).build()).build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("camera", "Camera Deck camera", NotificationManager.IMPORTANCE_LOW));
        if(android.os.Build.VERSION.SDK_INT>=29) {
            int types=android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED)types|=android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(1,notification("opening"),types);
        } else startForeground(1,notification("opening"));
        if (running) return START_NOT_STICKY;
        active = this; lastStartupError = "";
        try {
            id = getSharedPreferences("deck", 0).getString("id", "");
            if (id.isEmpty()) { id = UUID.randomUUID().toString(); getSharedPreferences("deck", 0).edit().putString("id", id).apply(); }
            rotatePin(); audio=new AudioEngine(this); engine = new CameraEngine(this,audio);
            api = new Api(tls()); api.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
            running = true;
            discovery = new DatagramSocket(DISCOVERY);
            new Thread(this::discover, "CameraDeck-discovery").start();
            wake = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CameraDeck:camera");
            wake.setReferenceCounted(false);
            maintenance.post(powerTick);
        } catch (Exception e) { error = e.toString(); lastStartupError = error; stopSelf(); }
        return START_NOT_STICKY;
    }

    SSLServerSocketFactory tls() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        // Conscrypt passes an already hashed TLS transcript to NONEwithECDSA.
        // Keystore authorizations cannot be edited; retain v1 and migrate once.
        String alias = "camera-deck-tls-v2";
        if (!store.containsAlias(alias)) {
            if(store.containsAlias("camera-deck-tls-v1"))updateMessage="Защищённое подключение обновлено. Сравните новый отпечаток на компьютере и телефоне.";
            KeyPairGenerator gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            gen.initialize(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(new javax.security.auth.x500.X500Principal("CN=CameraDeck"))
                .setCertificateSerialNumber(java.math.BigInteger.valueOf(System.currentTimeMillis()))
                .setCertificateNotBefore(new Date(System.currentTimeMillis() - 86400000))
                .setCertificateNotAfter(new Date(System.currentTimeMillis() + 315360000000L)).build());
            gen.generateKeyPair();
        }
        X509Certificate cert = (X509Certificate)store.getCertificate(alias);
        fingerprint = hex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        // Let Android select aliases and signature algorithms for its own keystore.
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(store, null);
        SSLContext ssl = SSLContext.getInstance("TLS"); ssl.init(managers.getKeyManagers(), null, random);
        return ssl.getServerSocketFactory();
    }
    static String hex(byte[] bytes) { StringBuilder s = new StringBuilder(); for (byte b : bytes) s.append(String.format(Locale.US, "%02x", b & 255)); return s.toString(); }

    void discover() {
        while (running) {
            try {
                byte[] buf = new byte[256]; DatagramPacket p = new DatagramPacket(buf, buf.length); discovery.receive(p);
                if (!"CAMERA_DECK_DISCOVER_V1".equals(new String(p.getData(), 0, p.getLength(), java.nio.charset.StandardCharsets.UTF_8))) continue;
                if (!p.getAddress().isSiteLocalAddress() && !p.getAddress().isLinkLocalAddress()) continue;
                byte[] reply = new JSONObject().put("protocol", 1).put("id", id).put("name", name()).put("port", PORT)
                    .put("fingerprint", fingerprint).put("version", BuildConfig.VERSION_NAME).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                discovery.send(new DatagramPacket(reply, reply.length, p.getAddress(), p.getPort()));
            } catch (Exception e) { if (running) error = "Discovery: " + e.getMessage(); }
        }
    }

    final class Api extends NanoHTTPD {
        Api(SSLServerSocketFactory ssl) { super(PORT); makeSecure(ssl, new String[]{"TLSv1.2"}); }
        Response json(Response.Status code, JSONObject body) { Response r = newFixedLengthResponse(code, "application/json", body.toString()); r.addHeader("Cache-Control", "no-store"); return r; }
        Response error(Response.Status code, String message) { try { return json(code, new JSONObject().put("error", message)); } catch (Exception e) { return newFixedLengthResponse(code, "text/plain", "Error"); } }
        JSONObject body(IHTTPSession s) throws Exception {
            long length = Long.parseLong(s.getHeaders().getOrDefault("content-length", "0"));
            if (length < 2 || length > 16384) throw new IllegalArgumentException("Invalid request size");
            Map<String, String> files = new HashMap<>(); s.parseBody(files); return new JSONObject(files.getOrDefault("postData", "{}"));
        }
        @Override public Response serve(IHTTPSession s) {
            try {
                InetAddress remote = InetAddress.getByName(s.getRemoteIpAddress());
                if (!remote.isSiteLocalAddress() && !remote.isLinkLocalAddress()) return error(Response.Status.FORBIDDEN, "Local network only");
                String uri = s.getUri();
                if (uri.equals("/pair") && s.getMethod() == Method.POST) {
                    synchronized (attempts) {
                        long now = System.currentTimeMillis();
                        while (!attempts.isEmpty() && attempts.peekFirst() < now - 60000) attempts.removeFirst();
                        if (attempts.size() >= 5) return error(Response.Status.FORBIDDEN, "Too many attempts; wait one minute");
                        attempts.addLast(now);
                        if (now > pinExpires) { rotatePin(); return error(Response.Status.FORBIDDEN, "PIN expired; read the new PIN on phone"); }
                        if (!MessageDigest.isEqual(pin.getBytes(), body(s).optString("pin").getBytes())) return error(Response.Status.FORBIDDEN, "Wrong PIN");
                        byte[] bytes = new byte[32]; random.nextBytes(bytes); String token = hex(bytes);
                        tokens.entrySet().removeIf(e -> e.getValue() < now);
                        if (tokens.size() >= 4) return error(Response.Status.FORBIDDEN, "Maximum four paired sessions; revoke on phone");
                        tokens.put(token, now + 8*3600000L); rotatePin();
                        return json(Response.Status.OK, new JSONObject().put("token", token).put("expiresIn", 28800).put("name", name()));
                    }
                }
                String auth = s.getHeaders().getOrDefault("authorization", "");
                Long expires = auth.startsWith("Bearer ") ? tokens.get(auth.substring(7)) : null;
                if (expires == null || expires < System.currentTimeMillis()) return error(Response.Status.UNAUTHORIZED, "Pair again on phone");
                if (s.getMethod() == Method.GET && uri.equals("/status")) return json(Response.Status.OK, engine.status().put("name", name()).put("version", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE));
                if (s.getMethod() == Method.GET && uri.equals("/frame")) {
                    engine.demandRemote(); updatePower();
                    byte[] bytes = engine.frame;
                    if (bytes.length == 0 || System.currentTimeMillis() - engine.frameTime > 3000) return error(Response.Status.SERVICE_UNAVAILABLE, "No current camera frame");
                    Response r = newFixedLengthResponse(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(bytes), bytes.length);
                    r.addHeader("Cache-Control", "no-store"); r.addHeader("X-Frame-Sequence", Long.toString(engine.frameSequence)); return r;
                }
                if(s.getMethod()==Method.GET && uri.equals("/audio")) {
                    AudioEngine.Listener listener=audio.subscribe(); updatePower();
                    String sessionToken=auth.substring(7);
                    InputStream stream=new java.io.FilterInputStream(listener) {
                        @Override public int read(byte[] b,int off,int len)throws IOException {
                            Long expiry=tokens.get(sessionToken);
                            if(expiry==null || expiry<System.currentTimeMillis()) { close();return -1; }
                            return super.read(b,off,len);
                        }
                        @Override public void close()throws IOException { try{super.close();}finally{updatePower();} }
                    };
                    Response response=newChunkedResponse(Response.Status.OK,"application/octet-stream",stream);
                    response.addHeader("X-Audio-Encoding","pcm-s16le"); response.addHeader("X-Audio-Rate","48000");response.addHeader("X-Audio-Channels","1");
                    response.addHeader("Cache-Control","no-store");return response;
                }
                if (s.getMethod() == Method.POST && uri.equals("/command")) return json(Response.Status.OK, engine.command(body(s)));
                if (s.getMethod() == Method.GET && uri.equals("/media")) {
                    JSONArray files = new JSONArray(); File[] list = engine.mediaDir.listFiles();
                    if (list != null) { Arrays.sort(list, Comparator.comparingLong(File::lastModified).reversed()); for (File f : list) {
                        if (!f.getName().matches("CD_[0-9]+_[a-f0-9]{8}\\.(jpg|png|webp|dng|mp4)")) continue;
                        if (engine.recordingFile != null && (engine.recording || engine.busy) && f.equals(engine.recordingFile)) continue;
                        files.put(new JSONObject().put("name", f.getName()).put("size", f.length())); if (files.length() >= 100) break;
                    } }
                    return json(Response.Status.OK, new JSONObject().put("files", files));
                }
                if (s.getMethod() == Method.GET && uri.equals("/file")) {
                    String filename = s.getParms().getOrDefault("name", "");
                    if (!filename.matches("CD_[0-9]+_[a-f0-9]{8}\\.(jpg|png|webp|dng|mp4)")) return error(Response.Status.BAD_REQUEST, "Invalid filename");
                    File f = new File(engine.mediaDir, filename);
                    if (!f.isFile() || (f.equals(engine.recordingFile) && (engine.recording || engine.busy))) return error(Response.Status.NOT_FOUND, "File unavailable");
                    FileInputStream source = new FileInputStream(f) {
                        boolean ended;
                        @Override public void close() throws IOException {
                            try { super.close(); } finally { if (!ended) { ended = true; transfers.decrementAndGet(); updatePower(); } }
                        }
                    };
                    transfers.incrementAndGet(); updatePower();
                    String mime=filename.endsWith("jpg")?"image/jpeg":filename.endsWith("png")?"image/png":filename.endsWith("webp")?"image/webp":filename.endsWith("dng")?"image/x-adobe-dng":"video/mp4";
                    return newFixedLengthResponse(Response.Status.OK,mime, source, f.length());
                }
                if (s.getMethod() == Method.PUT && uri.equals("/update")) {
                    if (engine.recording || engine.busy || engine.photoPending) return error(Response.Status.CONFLICT, "Stop recording / wait for photo before update");
                    long length = Long.parseLong(s.getHeaders().getOrDefault("content-length", "0"));
                    if (length <= 0 || length > 200_000_000) return error(Response.Status.BAD_REQUEST, "APK too large");
                    String envelope = new String(android.util.Base64.decode(s.getHeaders().getOrDefault("x-update-manifest", ""), android.util.Base64.NO_WRAP), java.nio.charset.StandardCharsets.UTF_8);
                    UpdateVerifier.validateManifest(CameraService.this, envelope);
                    transfers.incrementAndGet(); updatePower();
                    try {
                        Map<String, String> files = new HashMap<>(); s.parseBody(files);
                        File incoming = new File(files.get("content"));
                        UpdateVerifier.stage(CameraService.this, envelope, incoming);
                    } finally { transfers.decrementAndGet(); updatePower(); }
                    updateMessage = "Обновление готово. Нажмите «Установить обновление» на телефоне.";
                    return json(Response.Status.OK, new JSONObject().put("staged", true).put("userConfirmationRequired", true));
                }
                return error(Response.Status.NOT_FOUND, "Unknown endpoint");
            } catch (Exception e) { Throwable cause = e.getCause() == null ? e : e.getCause(); return error(Response.Status.BAD_REQUEST, String.valueOf(cause.getMessage())); }
        }
    }

    @Override public void onDestroy() {
        running = false; if (active == this) active = null;
        maintenance.removeCallbacksAndMessages(null);
        if (discovery != null) discovery.close(); if (api != null) api.stop(); tokens.clear();
        if (engine != null) engine.close(); if (wake != null && wake.isHeld()) wake.release();
        if(audio!=null)audio.close();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
