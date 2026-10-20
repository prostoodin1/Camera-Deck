package io.github.prostoodin1.cameradeck;

/** Time based, acceleration limited zoom; face detection only updates the goal. */
final class ZoomMotion {
    private double position, goal, velocity;
    private long previous, observed;
    void reset(float zoom,long now){position=goal=Math.log(Math.max(.01,zoom));velocity=0;previous=now;observed=0;}
    void observe(float desired,long now){
        double next=Math.log(Math.max(.01,desired));
        if(observed==0)goal=next;
        else goal+=(next-goal)*.25;
        observed=now;
    }
    float step(long now){
        double dt=Math.min(.05,Math.max(0,(now-previous)/1000.0));previous=now;
        if(observed==0||now-observed>600){velocity=0;goal=position;return (float)Math.exp(position);}
        double distance=goal-position;
        double wanted=Math.abs(distance)<.008?0:Math.max(-.10,Math.min(.10,distance/.9));
        velocity+=Math.max(-.16*dt,Math.min(.16*dt,wanted-velocity));
        double movement=velocity*dt;
        if(Math.signum(movement)==Math.signum(distance)&&Math.abs(movement)>Math.abs(distance)){position=goal;velocity=0;}
        else position+=movement;
        return (float)Math.exp(position);
    }
}
