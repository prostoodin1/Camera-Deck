package io.github.prostoodin1.cameradeck;

import java.util.*;

/** Time-based presentation only. Missing landmarks are never invented. */
final class FaceMotion {
    static final class Observation {
        final int id;final float[] box,eyes;
        Observation(int id,float[] box,float[] eyes){this.id=id;this.box=box;this.eyes=eyes;}
    }
    static final class Track {
        final int id;final float[] box=new float[4],target=new float[4];
        float[] eyes=new float[0],eyeTarget=new float[0];
        long born,seen,tick;float alpha;
        Track(Observation o,long now){id=o.id;System.arraycopy(o.box,0,box,0,4);System.arraycopy(o.box,0,target,0,4);eyes=o.eyes.clone();eyeTarget=o.eyes.clone();born=seen=tick=now;}
    }
    final Map<Integer,Track> tracks=new LinkedHashMap<>();
    void clear(){tracks.clear();}
    void observe(List<Observation> observations,long now){
        for(Observation o:observations){
            if(o.box.length!=4)continue;
            Track t=tracks.get(o.id);
            if(t==null){t=new Track(o,now);tracks.put(o.id,t);}
            System.arraycopy(o.box,0,t.target,0,4);t.eyeTarget=o.eyes.clone();t.seen=now;
        }
    }
    List<Track> sample(long now){
        Iterator<Track> it=tracks.values().iterator();
        while(it.hasNext()){
            Track t=it.next();if(now-t.seen>850){it.remove();continue;}
            float mix=(float)(1-Math.exp(-Math.max(0,now-t.tick)/85.0));t.tick=now;
            for(int i=0;i<4;i++)t.box[i]+=(t.target[i]-t.box[i])*mix;
            if(t.eyes.length!=t.eyeTarget.length)t.eyes=t.eyeTarget.clone();
            else for(int i=0;i<t.eyes.length;i++)t.eyes[i]+=(t.eyeTarget[i]-t.eyes[i])*mix;
            float enter=Math.min(1,Math.max(0,(now-t.born)/160f));
            float exit=Math.min(1,Math.max(0,(850-(now-t.seen))/300f));
            t.alpha=enter*exit;
        }
        return new ArrayList<>(tracks.values());
    }
}
