package io.github.prostoodin1.cameradeck;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

final class TrustedClients {
    final SharedPreferences preferences;
    final SecureRandom random=new SecureRandom();
    TrustedClients(Context c){preferences=c.getSharedPreferences("trusted-clients",0);}
    String digest(String secret)throws Exception{return CameraService.hex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));}
    synchronized JSONObject add(String name)throws Exception{
        if(preferences.getAll().size()>=8)throw new IllegalStateException("Максимум 8 доверенных компьютеров. Удалите старый доступ на телефоне.");
        byte[] bytes=new byte[32];random.nextBytes(bytes);String secret=CameraService.hex(bytes),id=UUID.randomUUID().toString();
        JSONObject record=new JSONObject().put("name",name.substring(0,Math.min(60,name.length()))).put("hash",digest(secret));
        if(!preferences.edit().putString(id,record.toString()).commit())throw new IllegalStateException("Не удалось сохранить доступ");
        return new JSONObject().put("trustedId",id).put("rememberToken",secret);
    }
    synchronized String authenticate(String secret)throws Exception{
        if(!secret.matches("[0-9a-f]{64}"))return null;
        byte[] supplied=digest(secret).getBytes(StandardCharsets.US_ASCII);
        for(Map.Entry<String,?> entry:preferences.getAll().entrySet()){
            JSONObject record=new JSONObject(entry.getValue().toString());
            if(MessageDigest.isEqual(supplied,record.getString("hash").getBytes(StandardCharsets.US_ASCII)))return entry.getKey();
        }return null;
    }
    synchronized JSONArray list()throws Exception{
        JSONArray out=new JSONArray();for(Map.Entry<String,?> entry:preferences.getAll().entrySet())out.put(new JSONObject().put("id",entry.getKey()).put("name",new JSONObject(entry.getValue().toString()).getString("name")));return out;
    }
    synchronized void remove(String id){preferences.edit().remove(id).commit();}
    synchronized void clear(){preferences.edit().clear().commit();}
}
