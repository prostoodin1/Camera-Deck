package io.github.prostoodin1.cameradeck;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class CaptureCapabilitiesTest {
    JSONObject caps()throws Exception {
        return new JSONObject("{\"videoModes\":[{\"width\":1920,\"height\":1080,\"fps\":[30,60]},{\"width\":3840,\"height\":2160,\"fps\":[30]}],\"photoFormats\":[\"jpg\",\"png\",\"webp\",\"dng\"],\"photoSizes\":[{\"width\":4000,\"height\":3000}],\"rawSizes\":[{\"width\":4032,\"height\":3024}]}");
    }
    @Test public void videoSizeAndRateMustBelongToSameMode()throws Exception {
        assertTrue(CaptureCapabilities.videoAllowed(caps(),1920,1080,60));
        assertTrue(CaptureCapabilities.videoAllowed(caps(),3840,2160,30));
        assertFalse(CaptureCapabilities.videoAllowed(caps(),3840,2160,60));
        assertFalse(CaptureCapabilities.videoAllowed(caps(),1920,1080,120));
    }
    @Test public void rawMustUseRawDimensions()throws Exception {
        assertTrue(CaptureCapabilities.photoAllowed(caps(),"dng",4032,3024));
        assertFalse(CaptureCapabilities.photoAllowed(caps(),"dng",4000,3000));
        assertFalse(CaptureCapabilities.photoAllowed(caps(),"jpg",4032,3024));
    }
    @Test public void unavailablePhotoFormatsAreRejected()throws Exception {
        JSONObject c=caps();c.put("photoFormats",new JSONArray().put("jpg").put("png").put("webp"));
        assertFalse(CaptureCapabilities.photoAllowed(c,"dng",4032,3024));
        assertFalse(CaptureCapabilities.photoAllowed(c,"heic",4000,3000));
        assertTrue(CaptureCapabilities.photoAllowed(c,"png",4000,3000));
        assertTrue(CaptureCapabilities.photoAllowed(c,"webp",4000,3000));
    }
}
