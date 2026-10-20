package io.github.prostoodin1.cameradeck;

/** Fractional cadence: 30 input / 24 target must not collapse to every other frame. */
final class PreviewCadence {
    long next;int previousFps;
    boolean allow(long now,int fps){
        if(fps<=0)throw new IllegalArgumentException("fps");
        long interval=1_000_000_000L/fps;
        if(previousFps!=fps){previousFps=fps;next=now;}
        if(now<next)return false;
        next=next==0||now-next>interval*2?now+interval:next+interval;
        return true;
    }
}
