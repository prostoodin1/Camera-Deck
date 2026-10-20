package io.github.prostoodin1.cameradeck;

final class Framing {
    static float[] rotate(float x,float y,int rotation){return switch(rotation){case 90->new float[]{1-y,x};case 180->new float[]{1-x,1-y};case 270->new float[]{y,1-x};default->new float[]{x,y};};}
    static float target(String preset){return switch(preset){case "close"->.45f;case "portrait"->.28f;case "medium"->.17f;case "wide"->.10f;default->0;};}
    static float smooth(float current,float faceHeight,String preset,float min,float max){
        float target=target(preset);if(target==0||faceHeight<.015f)return current;
        float desired=Math.max(min,Math.min(max,current*target/faceHeight));
        if(Math.abs(desired-current)<Math.max(.025f,current*.025f))return current;
        return Math.max(min,Math.min(max,current+Math.max(-current*.05f,Math.min(current*.05f,(desired-current)*.18f))));
    }
}
