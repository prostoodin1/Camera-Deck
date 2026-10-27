package io.github.prostoodin1.cameradeck;
import org.junit.Test;
import static org.junit.Assert.*;
public class LatestQueueTest {
    @Test public void overloadKeepsRecentAudioWithoutGrowingOrThrowing(){
        LatestQueue<Integer> queue=new LatestQueue<>(2);
        assertFalse(queue.offerLatest(1));assertFalse(queue.offerLatest(2));
        assertTrue(queue.offerLatest(3));assertEquals(Integer.valueOf(2),queue.poll());assertEquals(Integer.valueOf(3),queue.poll());assertNull(queue.poll());
    }
    @Test public void inputCanResumeAfterGap(){
        LatestQueue<byte[]> queue=new LatestQueue<>(2);byte[] silence=new byte[1920],audio={1,2};
        queue.offerLatest(silence);queue.offerLatest(audio);assertSame(silence,queue.poll());assertSame(audio,queue.poll());
    }
}
