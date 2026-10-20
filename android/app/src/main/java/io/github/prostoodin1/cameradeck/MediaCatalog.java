package io.github.prostoodin1.cameradeck;

import android.graphics.*;
import android.media.MediaMetadataRetriever;
import android.os.ParcelFileDescriptor;
import org.json.JSONObject;
import java.io.*;

/** Reads completed originals through the same authorized storage abstraction. */
final class MediaCatalog {
    final CaptureStore store;
    MediaCatalog(CaptureStore store){this.store=store;}
    JSONObject info(String name)throws Exception{
        JSONObject result=new JSONObject().put("name",name);
        try(ParcelFileDescriptor fd=store.descriptor(name)){
            if(name.endsWith(".mp4")){
                try(MediaMetadataRetriever reader=new MediaMetadataRetriever()){
                    reader.setDataSource(fd.getFileDescriptor());
                    number(result,"width",reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                    number(result,"height",reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                    number(result,"durationMs",reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                    number(result,"rotation",reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
                    String fps=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);if(fps!=null)result.put("captureFps",fps);
                }
            }else{
                BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor(),null,options);
                if(options.outWidth>0)result.put("width",options.outWidth).put("height",options.outHeight);
            }
        }return result;
    }
    static void number(JSONObject o,String key,String value)throws Exception{if(value!=null)try{o.put(key,Long.parseLong(value));}catch(NumberFormatException ignored){}}
    byte[] thumbnail(String name)throws Exception{
        Bitmap bitmap;
        if(name.endsWith(".mp4")){
            try(ParcelFileDescriptor fd=store.descriptor(name);MediaMetadataRetriever reader=new MediaMetadataRetriever()){
                reader.setDataSource(fd.getFileDescriptor());bitmap=reader.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,320,180);
            }
        }else{
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            try(ParcelFileDescriptor fd=store.descriptor(name)){BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor(),null,bounds);}
            if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException("Миниатюра этого формата недоступна");
            BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
            while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>640)options.inSampleSize*=2;
            try(ParcelFileDescriptor fd=store.descriptor(name)){bitmap=BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor(),null,options);}
        }
        if(bitmap==null)throw new IOException("Миниатюра недоступна");
        try{ByteArrayOutputStream output=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,80,output);return output.toByteArray();}finally{bitmap.recycle();}
    }
}
