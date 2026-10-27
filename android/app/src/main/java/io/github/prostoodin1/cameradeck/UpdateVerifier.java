package io.github.prostoodin1.cameradeck;

import android.content.Context;
import android.content.pm.*;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

final class UpdateVerifier {
    static JSONObject validateManifest(Context c, String envelope) throws Exception {
        if(envelope.length()>65536)throw new SecurityException("Manifest too large");
        JSONObject signed = new JSONObject(envelope);
        byte[] payload = Base64.decode(signed.getString("payload"), Base64.NO_WRAP);
        String pem;
        try (InputStream in = c.getAssets().open(BuildConfig.CLIENT_CHANNEL?"client-update-public.pem":BuildConfig.PREVIEW_BUILD?"aurora-update-public.pem":"update-public.pem"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            pem = new String(out.toByteArray(), StandardCharsets.US_ASCII);
        }
        byte[] der = Base64.decode(pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", ""), Base64.DEFAULT);
        java.security.Signature verifier = java.security.Signature.getInstance("SHA256withRSA");
        verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der))); verifier.update(payload);
        if (!verifier.verify(Base64.decode(signed.getString("signature"), Base64.NO_WRAP))) throw new SecurityException("Update signature invalid");
        JSONObject manifest = new JSONObject(new String(payload, StandardCharsets.UTF_8));
        if (!manifest.getString("product").equals(BuildConfig.CLIENT_CHANNEL?"camera-deck-client":BuildConfig.PREVIEW_BUILD?"camera-deck-aurora":"camera-deck") || manifest.getInt("code") <= BuildConfig.VERSION_CODE || manifest.getInt("protocol") != 1) throw new SecurityException("Wrong product, protocol or old version");
        return manifest;
    }
    static void verifyApk(Context c, String envelope, File apk) throws Exception {
        JSONObject manifest = validateManifest(c, envelope);
        JSONObject asset = manifest.getJSONObject("android");
        if (apk.length() != asset.getLong("size")) throw new SecurityException("APK size mismatch");
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(apk)) { byte[] buffer = new byte[65536]; int n; while ((n = in.read(buffer)) != -1) hash.update(buffer, 0, n); }
        if (!CameraService.hex(hash.digest()).equals(asset.getString("sha256"))) throw new SecurityException("APK hash mismatch");
        PackageManager pm = c.getPackageManager();
        PackageInfo next = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo current = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (next == null || !next.packageName.equals(c.getPackageName()) || next.getLongVersionCode() != manifest.getLong("code") || next.signingInfo == null) throw new SecurityException("Wrong APK package/version");
        if (!Arrays.equals(next.signingInfo.getApkContentsSigners(), current.signingInfo.getApkContentsSigners())) throw new SecurityException("APK signing certificate changed");

    }
    static synchronized void stage(Context c,String envelope,File apk)throws Exception{
        verifyApk(c,envelope,apk);JSONObject incoming=validateManifest(c,envelope);File meta=new File(c.getFilesDir(),"update-manifest.json");
        if(meta.isFile())try{JSONObject old=validateManifest(c,new String(Files.readAllBytes(meta.toPath()),StandardCharsets.UTF_8));if(old.getLong("code")>=incoming.getLong("code")){verifyApk(c,new String(Files.readAllBytes(meta.toPath()),StandardCharsets.UTF_8),new File(c.getFilesDir(),"update.apk"));return;}}catch(Exception ignored){}
        File next=new File(c.getFilesDir(),"update.apk.next");Files.copy(apk.toPath(),next.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.move(next.toPath(),new File(c.getFilesDir(),"update.apk").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        File nextMeta=new File(c.getFilesDir(),"update-manifest.next");Files.write(nextMeta.toPath(),envelope.getBytes(StandardCharsets.UTF_8));Files.move(nextMeta.toPath(),meta.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        c.getSharedPreferences("deck",0).edit().putInt("stagedUpdateCode",incoming.getInt("code")).apply();
    }

}
