import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'desktop'))
from production import operate


class Camera:
    def __init__(self,mode='video',fail=False):self.mode=mode;self.recording=False;self.fail=fail;self.calls=[]
    def json(self,path):
        if self.fail:raise ConnectionError('offline')
        return dict(ready=True,recording=self.recording,mode=self.mode)
    def command(self,action):self.calls.append(action);self.recording=action=='record_start'


def test_group_failure_does_not_stop_other_cameras():
    good=Camera();bad=Camera(fail=True);result=[]
    operate([(1,good),(2,bad)],True,result.append)
    by_id={r[0]:r for r in result}
    assert by_id[1][1] and not by_id[2][1]
    assert good.recording and good.calls==['record_start']
    assert by_id[1][3]['recording']  # Only reported state confirms success.


def test_group_preflight_and_already_recording_are_safe():
    photo=Camera('photo');active=Camera();active.recording=True;result=[]
    operate([(1,photo),(2,active)],True,result.append)
    assert not photo.calls and not active.calls
    assert not next(r for r in result if r[0]==1)[1]
    assert next(r for r in result if r[0]==2)[1]


def test_stop_only_sends_command_to_active_camera():
    active=Camera();active.recording=True;idle=Camera();result=[]
    operate([(1,active),(2,idle)],False,result.append)
    assert active.calls==['record_stop'] and not idle.calls
    assert all(r[1] and not r[3]['recording'] for r in result)


def test_clock_estimate_uses_lowest_round_trip_and_reports_uncertainty():
    from production import estimate_clock
    class Clock:
        def __init__(self):self.values=iter([1000100,1000300])
        def json(self,path):assert path=='/clock';return {'monotonicNs':next(self.values)}
    host=iter([0,200,290,310])
    estimate=estimate_clock(Clock(),samples=2,now=lambda:next(host))
    assert estimate=={'offsetNs':1000000,'uncertaintyNs':10,'roundTripNs':20}
