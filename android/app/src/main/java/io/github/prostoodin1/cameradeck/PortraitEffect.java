package io.github.prostoodin1.cameradeck;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.os.*;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.*;
import androidx.media3.effect.*;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.*;
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions;
import java.nio.*;
import java.util.concurrent.TimeUnit;

/** Offline, frame-synchronous person masking. Never attached to the capture encoder. */
@UnstableApi
final class PortraitEffect implements GlEffect {
    final float strength;volatile int subjectFrames;
    PortraitEffect(float strength){this.strength=strength;}
    @Override public GlShaderProgram toGlShaderProgram(Context c,boolean hdr)throws VideoFrameProcessingException{
        if(hdr)throw new VideoFrameProcessingException("Экспериментальное размытие поддерживает SDR");
        return new Program(c);
    }
    final class Program extends BaseGlShaderProgram {
        final Context context;final GlProgram copy,blur;
        final Segmenter segmenter=Segmentation.getClient(new SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.STREAM_MODE).enableRawSizeMask().build());
        int width,height,sw,sh,smallTexture,framebuffer,maskTexture;Bitmap bitmap;ByteBuffer rgba,maskBytes;int[] pixels;
        final String vertex="attribute vec2 aPosition; varying vec2 vTex; void main(){vTex=(aPosition+1.0)*0.5;gl_Position=vec4(aPosition,0.0,1.0);}";
        Program(Context c)throws VideoFrameProcessingException{
            super(false,1);context=c.getApplicationContext();
            try{
                copy=new GlProgram(vertex,"precision mediump float; varying vec2 vTex; uniform sampler2D uTexture;void main(){vec4 c=texture2D(uTexture,vTex);gl_FragColor=vec4(pow(max(c.rgb,vec3(0.0)),vec3(0.454545)),1.0);}");
                blur=new GlProgram(vertex,"precision mediump float; varying vec2 vTex; uniform sampler2D uTexture; uniform sampler2D uMask; uniform vec2 uStep;void main(){vec4 original=texture2D(uTexture,vTex);vec4 soft=vec4(0.0);float total=0.0;for(int y=-2;y<=2;y++){for(int x=-2;x<=2;x++){float w=exp(-0.35*float(x*x+y*y));soft+=texture2D(uTexture,clamp(vTex+vec2(float(x),float(y))*uStep,0.0,1.0))*w;total+=w;}}float foreground=smoothstep(0.25,0.8,texture2D(uMask,vTex).r);gl_FragColor=mix(soft/total,original,foreground);}");
                float[] quad={-1,-1,1,-1,-1,1,1,1};copy.setBufferAttribute("aPosition",quad,2);blur.setBufferAttribute("aPosition",quad,2);
            }catch(GlUtil.GlException error){segmenter.close();throw new VideoFrameProcessingException(error);}
        }
        int texture(int w,int h,int format){int[] ids=new int[1];GLES20.glGenTextures(1,ids,0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,ids[0]);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,format,w,h,0,format,GLES20.GL_UNSIGNED_BYTE,null);return ids[0];}
        void deleteBuffers(){if(framebuffer!=0)GLES20.glDeleteFramebuffers(1,new int[]{framebuffer},0);if(smallTexture!=0)GLES20.glDeleteTextures(1,new int[]{smallTexture},0);if(maskTexture!=0)GLES20.glDeleteTextures(1,new int[]{maskTexture},0);framebuffer=smallTexture=maskTexture=0;if(bitmap!=null){bitmap.recycle();bitmap=null;}}
        @Override public Size configure(int w,int h){
            deleteBuffers();width=w;height=h;float scale=Math.min(1,512f/Math.max(w,h));sw=Math.max(2,Math.round(w*scale));sh=Math.max(2,Math.round(h*scale));
            rgba=ByteBuffer.allocateDirect(sw*sh*4);pixels=new int[sw*sh];bitmap=Bitmap.createBitmap(sw,sh,Bitmap.Config.ARGB_8888);smallTexture=texture(sw,sh,GLES20.GL_RGBA);maskTexture=texture(1,1,GLES20.GL_LUMINANCE);
            int[] id=new int[1];GLES20.glGenFramebuffers(1,id,0);framebuffer=id[0];GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffer);GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,smallTexture,0);GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);
            float output=Math.min(1,1920f/Math.max(w,h));return new Size(Math.max(2,Math.round(w*output/2)*2),Math.max(2,Math.round(h*output/2)*2));
        }
        @Override public void drawFrame(int texture,long pts)throws VideoFrameProcessingException{
            int[] old=new int[1],viewport=new int[4],unpack=new int[1];GLES20.glGetIntegerv(GLES20.GL_UNPACK_ALIGNMENT,unpack,0);GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING,old,0);GLES20.glGetIntegerv(GLES20.GL_VIEWPORT,viewport,0);
            try{
                if(Build.VERSION.SDK_INT>=29&&context.getSystemService(PowerManager.class).getCurrentThermalStatus()>=3)throw new IllegalStateException("Обработка остановлена из-за нагрева; оригинал сохранён");
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffer);GLES20.glViewport(0,0,sw,sh);copy.use();copy.setSamplerTexIdUniform("uTexture",texture,0);copy.bindAttributesAndUniforms();GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);rgba.clear();GLES20.glReadPixels(0,0,sw,sh,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,rgba);
                for(int y=0;y<sh;y++)for(int x=0;x<sw;x++){int n=((sh-1-y)*sw+x)*4;pixels[y*sw+x]=0xff000000|(rgba.get(n)&255)<<16|(rgba.get(n+1)&255)<<8|(rgba.get(n+2)&255);}
                bitmap.setPixels(pixels,0,sw,0,0,sw,sh);
                SegmentationMask mask=Tasks.await(segmenter.process(InputImage.fromBitmap(bitmap,0)),10,TimeUnit.SECONDS);ByteBuffer confidence=mask.getBuffer().order(ByteOrder.nativeOrder());int mw=mask.getWidth(),mh=mask.getHeight(),size=mw*mh;
                if(maskBytes==null||maskBytes.capacity()!=size)maskBytes=ByteBuffer.allocateDirect(size);maskBytes.clear();double sum=0;
                for(int y=0;y<mh;y++)for(int x=0;x<mw;x++){float value=confidence.getFloat(((mh-1-y)*mw+x)*4);if(!Float.isFinite(value))throw new IllegalStateException("Некорректная маска человека");sum+=value;maskBytes.put((byte)Math.round(Math.max(0,Math.min(1,value))*255));}
                if(sum/size>.01&&sum/size<.99)subjectFrames++;else{maskBytes.clear();for(int i=0;i<size;i++)maskBytes.put((byte)255);}maskBytes.flip();
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,maskTexture);GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT,1);GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_LUMINANCE,mw,mh,0,GLES20.GL_LUMINANCE,GLES20.GL_UNSIGNED_BYTE,maskBytes);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,old[0]);GLES20.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);blur.use();blur.setSamplerTexIdUniform("uTexture",texture,0);blur.setSamplerTexIdUniform("uMask",maskTexture,1);blur.setFloatsUniform("uStep",new float[]{strength*.006f*height/width,strength*.006f});blur.bindAttributesAndUniforms();GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GlUtil.checkGlError();
            }catch(Exception error){throw new VideoFrameProcessingException(error,pts);}
            finally{GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT,unpack[0]);GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,old[0]);GLES20.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);}
        }
        @Override public void release()throws VideoFrameProcessingException{try{deleteBuffers();copy.delete();blur.delete();segmenter.close();}catch(Exception error){throw new VideoFrameProcessingException(error);}finally{super.release();}}
    }
}
