package io.github.prostoodin1.cameradeck;
final class ExposureMath {
    static int evSteps(double ev,double step,int min,int max){
        if(!Double.isFinite(ev)||!Double.isFinite(step)||step<=0)throw new IllegalArgumentException("Invalid EV");
        long n=Math.round(ev/step);if(n<min||n>max)throw new IllegalArgumentException("EV вне диапазона камеры");return (int)n;
    }
    static float tone(float x,float contrast,float gamma){return Math.max(0,Math.min(1,((float)Math.pow(Math.max(0,x),1/gamma)-.5f)*contrast+.5f));}
}
