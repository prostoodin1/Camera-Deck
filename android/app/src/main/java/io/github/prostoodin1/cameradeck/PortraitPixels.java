package io.github.prostoodin1.cameradeck;

/** Bounded separable blur for finished photo copies, never the camera thread. */
final class PortraitPixels {
    static int[] box(int[] input,int width,int height,int radius,boolean horizontal){
        int[] out=new int[input.length];int lines=horizontal?height:width,length=horizontal?width:height;
        for(int line=0;line<lines;line++){
            long a=0,r=0,g=0,b=0;int count=2*radius+1;
            for(int i=-radius;i<=radius;i++){int p=input[horizontal?line*width+Math.max(0,Math.min(length-1,i)):Math.max(0,Math.min(length-1,i))*width+line];a+=p>>>24;r+=p>>16&255;g+=p>>8&255;b+=p&255;}
            for(int i=0;i<length;i++){
                out[horizontal?line*width+i:i*width+line]=(int)(a/count)<<24|(int)(r/count)<<16|(int)(g/count)<<8|(int)(b/count);
                int leave=Math.max(0,i-radius),enter=Math.min(length-1,i+radius+1);
                int old=input[horizontal?line*width+leave:leave*width+line],next=input[horizontal?line*width+enter:enter*width+line];
                a+=(next>>>24)-(old>>>24);r+=(next>>16&255)-(old>>16&255);g+=(next>>8&255)-(old>>8&255);b+=(next&255)-(old&255);
            }
        }return out;
    }
    static float sample(float[] mask,int w,int h,float x,float y){
        x=Math.max(0,Math.min(w-1,x));y=Math.max(0,Math.min(h-1,y));int l=(int)x,t=(int)y,r=Math.min(w-1,l+1),b=Math.min(h-1,t+1);float fx=x-l,fy=y-t;
        float value=(mask[t*w+l]*(1-fx)+mask[t*w+r]*fx)*(1-fy)+(mask[b*w+l]*(1-fx)+mask[b*w+r]*fx)*fy;
        if(!Float.isFinite(value))return 1;float n=Math.max(0,Math.min(1,(value-.25f)/.55f));return n*n*(3-2*n);
    }
    static int[] blend(int[] source,int w,int h,float[] mask,int mw,int mh,float strength){
        if(source.length!=w*h||mask.length!=mw*mh||!Float.isFinite(strength)||strength<0||strength>1)throw new IllegalArgumentException("Invalid portrait image");
        if(strength==0)return source.clone();int radius=Math.max(1,Math.round(strength*Math.min(w,h)*.012f));
        int[] blurred=source;
        for(int i=0;i<3;i++)blurred=box(box(blurred,w,h,radius,true),w,h,radius,false);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int n=y*w+x,original=source[n],soft=blurred[n];float a=sample(mask,mw,mh,(x+.5f)*mw/w-.5f,(y+.5f)*mh/h-.5f);
            int r=Math.round((original>>16&255)*a+(soft>>16&255)*(1-a)),g=Math.round((original>>8&255)*a+(soft>>8&255)*(1-a)),b=Math.round((original&255)*a+(soft&255)*(1-a));
            blurred[n]=original&0xff000000|r<<16|g<<8|b;
        }return blurred;
    }
}
