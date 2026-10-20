package io.github.prostoodin1.cameradeck;

/** Camera2 AF constants: OFF=0, AUTO=1, CONTINUOUS_VIDEO=3, CONTINUOUS_PICTURE=4. */
final class CameraModes {
    static int autofocus(int[] supported,int preferred) {
        if(supported==null||supported.length==0)return 0;
        // Fixed-focus and limited cameras need a mode actually advertised by the device.
        for(int candidate:new int[]{preferred,4,3,0,1})
            for(int mode:supported)if(mode==candidate)return mode;
        return supported[0];
    }
}
