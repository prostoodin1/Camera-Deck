from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'desktop'))
from face_motion import FaceMotion
def face(i,x):return dict(id=i,left=x,top=.1,right=x+.2,bottom=.4,eyes=[])
def test_ids_survive_reordering_and_motion_is_not_a_jump():
    m=FaceMotion();m.observe([face(1,0),face(2,.6)],1,0);m.sample(.1);m.observe([face(2,.65),face(1,.5)],2,.1);m.sample(.116)
    assert 0<m.tracks[1]['box'][0]<.5 and m.tracks[2]['box'][0]>.6
def test_old_metadata_does_not_keep_a_lost_face_alive():
    m=FaceMotion();m.observe([face(1,.1)],1,0);m.observe([face(1,.1)],1,.7);assert m.sample(.851)==[]
def test_fade_and_landmarks_are_real():
    m=FaceMotion();m.observe([face(1,.1)],1,0);assert m.sample(.2)[0]['alpha']==1
    assert m.sample(.7)[0]['alpha']<1 and not m.sample(.7)[0]['eyes']
def test_invalid_geometry_is_ignored():
    m=FaceMotion();f=face(1,.1);f['left']=float('nan');m.observe([f],1,0);assert m.sample(.1)==[]
