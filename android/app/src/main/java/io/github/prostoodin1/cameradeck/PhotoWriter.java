package io.github.prostoodin1.cameradeck;

import android.graphics.*;
import android.hardware.camera2.*;
import android.media.*;
import java.io.*;
import java.nio.ByteBuffer;

final class PhotoWriter {
    static void write(Image image,TotalCaptureResult result,CameraCharacteristics characteristics,File file,String format,int rotation) throws Exception {
        File partial=new File(file.getAbsolutePath()+".partial");
        try(FileOutputStream out=new FileOutputStream(partial)) {
            if(format.equals("dng")) {
                if(result==null || result.get(CaptureResult.SENSOR_TIMESTAMP)==null || result.get(CaptureResult.SENSOR_TIMESTAMP)!=image.getTimestamp()) throw new IOException("RAW metadata/image timestamp mismatch");
                try(DngCreator dng=new DngCreator(characteristics,result)) {
                    dng.setOrientation(rotation==90?6:rotation==180?3:rotation==270?8:1); dng.writeImage(out,image);
                }
            } else {
                ByteBuffer buffer=image.getPlanes()[0].getBuffer(); byte[] bytes=new byte[buffer.remaining()]; buffer.get(bytes);
                if(format.equals("jpg")) out.write(bytes);
                else {
                    Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
                    if(bitmap==null) throw new IOException("JPEG decode failed");
                    ExifInterface exif=new ExifInterface(new ByteArrayInputStream(bytes));
                    int orientation=exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,1);
                    Matrix matrix=new Matrix();
                    if(orientation==6)matrix.postRotate(90); else if(orientation==3)matrix.postRotate(180); else if(orientation==8)matrix.postRotate(270);
                    else if(orientation==2)matrix.postScale(-1,1); else if(orientation==4)matrix.postScale(1,-1);
                    else if(orientation==5) { matrix.postRotate(90); matrix.postScale(-1,1); }
                    else if(orientation==7) { matrix.postRotate(270); matrix.postScale(-1,1); }
                    Bitmap oriented=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);
                    try {
                        if(!oriented.compress(format.equals("png")?Bitmap.CompressFormat.PNG:Bitmap.CompressFormat.WEBP,format.equals("png")?100:95,out)) throw new IOException("Photo encoding failed");
                    } finally { if(oriented!=bitmap)oriented.recycle(); bitmap.recycle(); }
                }
            }
        }
        if(!partial.renameTo(file)) throw new IOException("Cannot finalize photo");
    }
}
