package io.github.prostoodin1.cameradeck;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Build;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.SeekBar;

/** Shared visual roles; yellow is selection, red is recording, white is content. */
final class CameraDesign {
    static final int BLACK=0xff090c10,PANEL=0xff19232f,LINE=0xff435063,TEXT=0xfff0f3f7,MUTED=0xffb3bdcb,ACCENT=0xfff1d578,RECORD=0xffff625e;
    static int dp(Context c,int v){return Math.round(v*c.getResources().getDisplayMetrics().density);}
    static GradientDrawable surface(Context c,int fill,int line,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(c,radius));if(line!=0)d.setStroke(dp(c,1),line);return d;}
    static GradientDrawable glass(Context c,int radius,boolean transparent){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{transparent?0xed344253:0xff293646,transparent?0xe51b2531:0xff18222d});d.setCornerRadius(dp(c,radius));d.setStroke(dp(c,1),0x665e6f83);return d;}
    static void sheet(Window w,boolean glass){
        w.setWindowAnimations(R.style.GlassSheetMotion);
        Context c=w.getContext();boolean blur=glass&&Build.VERSION.SDK_INT>=31&&c.getSystemService(WindowManager.class).isCrossWindowBlurEnabled();
        w.setBackgroundDrawable(glass(c,30,blur));w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setDimAmount(.20f);
        if(Build.VERSION.SDK_INT>=31&&blur){w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);w.setBackgroundBlurRadius(dp(c,18));WindowManager.LayoutParams p=w.getAttributes();p.setBlurBehindRadius(dp(c,14));w.setAttributes(p);}
    }
    static void button(Button b,boolean primary){
        b.setAlpha(b.isEnabled()?1:.38f);
        if(Boolean.valueOf(primary).equals(b.getTag()))return;
        b.setTag(primary);
        b.setTextColor(primary?BLACK:TEXT);
        Drawable fill=primary?surface(b.getContext(),ACCENT,0,20):glass(b.getContext(),20,false);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x335c7692),fill,null));b.setAlpha(b.isEnabled()?1:.38f);
    }
    @android.annotation.SuppressLint("AppCompatCustomView")
    static final class Icon extends Button {
        final String kind;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Icon(Context c,String kind,String description){super(c);this.kind=kind;setContentDescription(description);button(this,false);}
        @Override protected void onDraw(Canvas canvas){
            canvas.save();float size=dp(getContext(),23);canvas.translate((getWidth()-size)/2,(getHeight()-size)/2);canvas.scale(size/24,size/24);p.setColor(isEnabled()?TEXT:MUTED);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.5f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            if(kind.equals("gallery")){canvas.drawRoundRect(3,3,21,21,4,4,p);canvas.drawCircle(8,8,1.5f,p);Path path=new Path();path.moveTo(4,18);path.lineTo(10,12);path.lineTo(14,16);path.lineTo(18,11);path.lineTo(21,15);canvas.drawPath(path,p);}
            else if(kind.equals("lens")){canvas.drawRoundRect(3,5,21,19,4,4,p);canvas.drawCircle(12,12,4,p);canvas.drawLine(9,3,15,3,p);}
            else if(kind.equals("view")){canvas.drawLine(3,8,3,3,p);canvas.drawLine(3,3,8,3,p);canvas.drawLine(16,3,21,3,p);canvas.drawLine(21,3,21,8,p);canvas.drawLine(3,16,3,21,p);canvas.drawLine(3,21,8,21,p);canvas.drawLine(16,21,21,21,p);canvas.drawLine(21,21,21,16,p);}
            else{p.setStyle(Paint.Style.FILL);for(int x=5;x<=19;x+=7)canvas.drawCircle(x,12,1.7f,p);}
            canvas.restore();
        }
    }
    @android.annotation.SuppressLint("AppCompatCustomView")
    static final class Ruler extends SeekBar {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Ruler(Context c){super(c);}
        @Override protected synchronized void onDraw(Canvas canvas){
            paint.setColor(isEnabled()?0xff8d9db1:0xff485361);paint.setStrokeWidth(dp(getContext(),1));float left=dp(getContext(),16),span=Math.max(1,getWidth()-2*left),top=dp(getContext(),5);
            for(int i=0;i<=32;i++){float x=left+span*i/32;canvas.drawLine(x,top,x,top+dp(getContext(),i%4==0?8:4),paint);}super.onDraw(canvas);
        }
    }
    // This application deliberately uses platform Activity/widgets, not AppCompat.
    @android.annotation.SuppressLint("AppCompatCustomView")
    static final class Shutter extends Button {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean recording,photo=true;
        Shutter(Context c){super(c);setBackgroundColor(Color.TRANSPARENT);setContentDescription("Снять фотографию");}
        void state(boolean photo,boolean recording){if(this.photo==photo&&this.recording==recording)return;this.photo=photo;this.recording=recording;setContentDescription(photo?"Снять фотографию":recording?"Остановить запись":"Начать запись");invalidate();}
        @Override protected void onDraw(Canvas canvas){
            float x=getWidth()/2f,y=getHeight()/2f,r=Math.min(getWidth(),getHeight())*.42f;
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(getContext(),2));paint.setColor(isEnabled()?TEXT:MUTED);canvas.drawCircle(x,y,r,paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(photo?TEXT:RECORD);
            if(recording){float s=r*.43f;canvas.drawRoundRect(x-s,y-s,x+s,y+s,dp(getContext(),3),dp(getContext(),3),paint);}
            else canvas.drawCircle(x,y,r*.78f,paint);
        }
    }
}
