package io.github.prostoodin1.cameradeck;

import java.util.concurrent.ArrayBlockingQueue;

/** Bounded queue: overload drops stale input rather than killing the video recorder. */
final class LatestQueue<T> {
    private final ArrayBlockingQueue<T> queue;
    LatestQueue(int capacity){queue=new ArrayBlockingQueue<>(capacity);}
    synchronized boolean offerLatest(T value){
        boolean dropped=false;
        while(!queue.offer(value)){queue.poll();dropped=true;}
        return dropped;
    }
    T poll(){return queue.poll();}
}
