package io.github.prostoodin1.cameradeck;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import org.json.*;

final class TrackingOverlay extends View {
    final MainActivity activity;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    float left,top,width,height;int angle;boolean mirror,scaled;
    final ScaleGestureDetector zoomGesture;float gestureZoom;long lastZoom;
    TrackingOverlay(MainActivity activity){super(activity);this.activity=activity;setContentDescription("Видоискатель: нажмите для фокуса, разведите пальцы для зума.");
        zoomGesture=new ScaleGestureDetector(activity,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScaleBegin(ScaleGestureDetector detector){CameraEngine e=activity.engine();if(e==null||!e.ready)return false;gestureZoom=e.zoom;scaled=true;return true;}
            @Override public boolean onScale(ScaleGestureDetector detector){CameraEngine e=activity.engine();if(e==null)return false;gestureZoom=Math.max(e.minZoom,Math.min(e.maxZoom,gestureZoom*detector.getScaleFactor()));long now=SystemClock.elapsedRealtime();if(now-lastZoom>80){lastZoom=now;activity.command("framing","zoom",gestureZoom,"autoZoom",false);}return true;}
            @Override public void onScaleEnd(ScaleGestureDetector detector){activity.command("framing","zoom",gestureZoom,"autoZoom",false);}
        });
    }
    float[] map(float x,float y){float[] p=Framing.rotate(x,y,angle);if(mirror)p[0]=1-p[0];return new float[]{left+p[0]*width,top+p[1]*height};}
    void geometry(CameraEngine e){
        angle=e.rotation;
        if(e.nativePreview&&e.characteristics!=null){int sensor=e.characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION),display=activity.getWindowManager().getDefaultDisplay().getRotation()*90;angle=(sensor+(mirror?display:-display)+360)%360;}
        float rw=angle%180==0?e.previewWidth:e.previewHeight,rh=angle%180==0?e.previewHeight:e.previewWidth;
        float scale=Math.min(getWidth()/rw,getHeight()/rh);width=rw*scale;height=rh*scale;left=(getWidth()-width)/2;top=(getHeight()-height)/2;
    }
    @Override protected void onDraw(Canvas canvas){
        CameraEngine e=activity.engine();if(e==null||!e.ready||e.previewWidth==0||!activity.showPreview)return;
        mirror=e.characteristics!=null&&Integer.valueOf(0).equals(e.characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING));geometry(e);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(activity.dp(1));paint.setColor(e.afState==2||e.afState==4?0xff84e6a4:MainActivity.YELLOW);
        paint.setColor(0x66ffffff);
        if(activity.compositionGrid)for(float f:new float[]{1f/3,2f/3}){canvas.drawLine(left+width*f,top,left+width*f,top+height,paint);canvas.drawLine(left,top+height*f,left+width,top+height*f,paint);}
        if(activity.safeArea)canvas.drawRect(left+width*.05f,top+height*.05f,left+width*.95f,top+height*.95f,paint);
        paint.setColor(e.afState==2||e.afState==4?0xff84e6a4:MainActivity.YELLOW);
        try{
            if(e.tracking&&SystemClock.elapsedRealtime()-e.facesAt<800)for(int i=0;i<e.faces.length();i++){JSONObject face=e.faces.getJSONObject(i);float[] a=map((float)face.getDouble("left"),(float)face.getDouble("top")),b=map((float)face.getDouble("right"),(float)face.getDouble("bottom"));canvas.drawRoundRect(new RectF(Math.min(a[0],b[0]),Math.min(a[1],b[1]),Math.max(a[0],b[0]),Math.max(a[1],b[1])),activity.dp(5),activity.dp(5),paint);JSONArray eyes=face.getJSONArray("eyes");for(int j=0;j<eyes.length();j++){JSONArray eye=eyes.getJSONArray(j);float[] p=map((float)eye.getDouble(0),(float)eye.getDouble(1));canvas.drawCircle(p[0],p[1],activity.dp(5),paint);}}
            if(e.focusPoint!=null){float[] p=map(e.focusPoint[0],e.focusPoint[1]);float r=activity.dp(12);canvas.drawRect(p[0]-r,p[1]-r,p[0]+r,p[1]+r,paint);}
        }catch(JSONException ignored){}
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(event.getAction()==MotionEvent.ACTION_DOWN)scaled=false;zoomGesture.onTouchEvent(event);
        if(event.getAction()!=MotionEvent.ACTION_UP||scaled)return true;performClick();CameraEngine e=activity.engine();if(e==null||width<=0||height<=0)return true;
        float x=(event.getX()-left)/width,y=(event.getY()-top)/height;if(x<0||x>1||y<0||y>1)return true;if(mirror)x=1-x;float[] p=Framing.rotate(x,y,(360-angle)%360);activity.command("framing","focusX",p[0],"focusY",p[1]);return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
