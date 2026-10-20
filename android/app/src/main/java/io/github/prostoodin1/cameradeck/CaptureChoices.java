package io.github.prostoodin1.cameradeck;

final class CaptureChoices {
    static String videoLabel(int w,int h){
        if(w==1280&&h==720)return "HD";
        if(w==1920&&h==1080)return "Full HD";
        if(w==2560&&h==1440)return "QHD";
        if(w==3840&&h==2160)return "4K";
        if(w==7680&&h==4320)return "8K";
        return "";
    }
}
