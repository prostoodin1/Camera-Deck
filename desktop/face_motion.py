"""Identity-preserving, clock-based overlay motion. No synthetic landmarks."""
import math,time

class FaceMotion:
    def __init__(self):self.tracks={};self.stamp=None;self.expiry=.85
    def clear(self):self.tracks.clear();self.stamp=None
    def observe(self,faces,stamp=None,now=None):
        now=time.monotonic() if now is None else now
        if stamp is not None and stamp==self.stamp:return
        self.stamp=stamp
        for index,face in enumerate(faces[:30]):
            try:
                identity=int(face.get('id',index));box=[float(face[k]) for k in ('left','top','right','bottom')]
                eyes=[[float(x),float(y)] for x,y in face.get('eyes',[])[:2]]
                if not all(math.isfinite(v) for v in box+[v for p in eyes for v in p]):continue
                box=[max(0,min(1,v)) for v in box]
                if box[2]<=box[0] or box[3]<=box[1]:continue
            except (ValueError,TypeError,KeyError):continue
            if identity not in self.tracks:self.tracks[identity]={'id':identity,'box':box.copy(),'eyes':[p.copy() for p in eyes],'born':now,'tick':now}
            self.tracks[identity].update(target=box,eye_target=eyes,seen=now)
    def sample(self,now=None):
        now=time.monotonic() if now is None else now
        for identity,track in list(self.tracks.items()):
            if now-track['seen']>self.expiry:del self.tracks[identity];continue
            mix=1-math.exp(-max(0,now-track['tick'])/.085);track['tick']=now
            track['box']=[a+(b-a)*mix for a,b in zip(track['box'],track['target'])]
            if len(track['eyes'])!=len(track['eye_target']):track['eyes']=[p.copy() for p in track['eye_target']]
            else:track['eyes']=[[a+(b-a)*mix for a,b in zip(old,new)] for old,new in zip(track['eyes'],track['eye_target'])]
            track['alpha']=min(1,max(0,(now-track['born'])/.16))*min(1,max(0,(self.expiry-(now-track['seen']))/.3))
        return list(self.tracks.values())
