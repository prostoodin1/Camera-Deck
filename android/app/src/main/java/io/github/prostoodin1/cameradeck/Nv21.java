package io.github.prostoodin1.cameradeck;

/** BT.601 limited-range NV21 conversion for the local monitor, never the recording. */
final class Nv21 {
    static void argb(byte[] input,int width,int height,int[] output){
        if(width<=0||height<=0||(width&1)!=0||(height&1)!=0||input.length<width*height*3/2||output.length<width*height)
            throw new IllegalArgumentException("Invalid NV21 dimensions");
        int chroma=width*height;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int l=Math.max(0,(input[y*width+x]&255)-16),offset=chroma+(y/2)*width+(x&~1);
            int v=(input[offset]&255)-128,u=(input[offset+1]&255)-128;
            int r=(298*l+409*v+128)>>8,g=(298*l-100*u-208*v+128)>>8,b=(298*l+516*u+128)>>8;
            output[y*width+x]=0xff000000|(clamp(r)<<16)|(clamp(g)<<8)|clamp(b);
        }
    }
    static int clamp(int v){return Math.max(0,Math.min(255,v));}
}
