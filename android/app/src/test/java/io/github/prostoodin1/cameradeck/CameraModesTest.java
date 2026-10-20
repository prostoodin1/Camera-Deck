package io.github.prostoodin1.cameradeck;

import org.junit.Test;
import static org.junit.Assert.*;

public class CameraModesTest {
    @Test public void fixedFocusDoesNotRequestContinuousAf(){assertEquals(0,CameraModes.autofocus(new int[]{0},4));}
    @Test public void videoFallsBackToSupportedPictureAf(){assertEquals(4,CameraModes.autofocus(new int[]{0,1,4},3));}
    @Test public void videoUsesContinuousModeWhenAvailable(){assertEquals(3,CameraModes.autofocus(new int[]{0,1,3,4},3));}
    @Test public void autoOnlyCamerasRemainUsable(){assertEquals(1,CameraModes.autofocus(new int[]{1},4));}
}
