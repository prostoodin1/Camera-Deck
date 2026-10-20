package io.github.prostoodin1.cameradeck;

final class Framing {
    // OrientationEventListener's angle has the opposite sign to display rotation.
    // Camera2 JPEG_ORIENTATION sample: sensor + device angle, inverted for front.
    static int cameraRotation(int sensor,int device,boolean front){int rounded=((device+45)/90*90)%360;return Math.floorMod(sensor+(front?-rounded:rounded),360);}
    static float[] rotate(float x,float y,int rotation){return switch(rotation){case 90->new float[]{1-y,x};case 180->new float[]{1-x,1-y};case 270->new float[]{y,1-x};default->new float[]{x,y};};}
    static float target(String preset){return switch(preset){case "close"->.45f;case "portrait"->.28f;case "medium"->.17f;case "wide"->.10f;default->0;};}
    static float smooth(float current,float faceHeight,String preset,float min,float max){
        float target=target(preset);if(target==0||faceHeight<.015f)return current;
        float desired=Math.max(min,Math.min(max,current*target/faceHeight));
        if(Math.abs(desired-current)<Math.max(.025f,current*.025f))return current;
        return Math.max(min,Math.min(max,current+Math.max(-current*.05f,Math.min(current*.05f,(desired-current)*.18f))));
    }
}
