package io.github.prostoodin1.cameradeck;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.widget.Button;

/** Shared visual roles; yellow is selection, red is recording, white is content. */
final class CameraDesign {
    static final int BLACK=0xff080a0c,PANEL=0xff15181c,LINE=0xff30353c,TEXT=0xffeef0f3,MUTED=0xff969da7,ACCENT=0xffffd76b,RECORD=0xfff05252;
    static int dp(Context c,int v){return Math.round(v*c.getResources().getDisplayMetrics().density);}
    static GradientDrawable surface(Context c,int fill,int line,int radius){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(c,radius));if(line!=0)d.setStroke(dp(c,1),line);return d;}
    static void button(Button b,boolean primary){
        b.setTextColor(primary?BLACK:TEXT);
        StateListDrawable states=new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},surface(b.getContext(),primary?0xffffe8a5:0xff343a43,0,8));
        states.addState(new int[]{},surface(b.getContext(),primary?ACCENT:PANEL,primary?0:LINE,8));
        b.setBackground(states);b.setAlpha(b.isEnabled()?1:.38f);
    }
    static final class Shutter extends Button {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean recording,photo=true;
        Shutter(Context c){super(c);setBackgroundColor(Color.TRANSPARENT);setContentDescription("Снять фотографию");}
        void state(boolean photo,boolean recording){this.photo=photo;this.recording=recording;setContentDescription(photo?"Снять фотографию":recording?"Остановить запись":"Начать запись");invalidate();}
        @Override protected void onDraw(Canvas canvas){
            float x=getWidth()/2f,y=getHeight()/2f,r=Math.min(getWidth(),getHeight())*.42f;
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(getContext(),2));paint.setColor(isEnabled()?TEXT:MUTED);canvas.drawCircle(x,y,r,paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(photo?TEXT:RECORD);
            if(recording){float s=r*.43f;canvas.drawRoundRect(x-s,y-s,x+s,y+s,dp(getContext(),3),dp(getContext(),3),paint);}
            else canvas.drawCircle(x,y,r*.78f,paint);
        }
    }
}
