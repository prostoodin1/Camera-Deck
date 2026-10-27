package io.github.prostoodin1.cameradeck;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class FaceMotionTest {
    FaceMotion.Observation face(int id,float x){return new FaceMotion.Observation(id,new float[]{x,.1f,x+.2f,.4f},new float[0]);}
    @Test public void identitiesDoNotSwapWhenOrderChanges(){FaceMotion m=new FaceMotion();m.observe(List.of(face(1,.1f),face(2,.6f)),0);m.observe(List.of(face(2,.65f),face(1,.15f)),180);m.sample(200);assertTrue(m.tracks.get(1).box[0]<m.tracks.get(2).box[0]);}
    @Test public void disappearanceFadesThenExpires(){FaceMotion m=new FaceMotion();m.observe(List.of(face(1,.1f)),0);assertEquals(1,m.sample(200).get(0).alpha,.001);assertTrue(m.sample(700).get(0).alpha<1);assertTrue(m.sample(851).isEmpty());}
    @Test public void smoothingDoesNotJumpAndNoSyntheticEyes(){FaceMotion m=new FaceMotion();m.observe(List.of(face(1,0)),0);m.sample(100);m.observe(List.of(face(1,.7f)),100);FaceMotion.Track t=m.sample(116).get(0);assertTrue(t.box[0]>0&&t.box[0]<.7f);assertEquals(0,t.eyes.length);}
}
