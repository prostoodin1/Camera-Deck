import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'desktop'))
import sensor_policy as policy

def camera(**overrides):
    return dict(manualSensor=True,isoMin=80,isoMax=5000,iso=200,exposureNs=60000000,exposureMinNs=100000,exposureMaxNs=1_000_000_000,mode='video',videoFps=60,focus=-1,focusMax=5,**overrides)

def test_presets_follow_real_limits_including_nonstandard_endpoints():
    choices=policy.presets('iso',camera())
    assert choices[0]==80 and choices[-1]==5000 and 50 not in choices and 6400 not in choices
    assert policy.bounds('shutter',camera())[1]==16666666
    assert all(100000<=v<=16666666 for v in policy.presets('shutter',camera()))

def test_entering_manual_pairs_exposure_with_frame_period_without_changing_focus():
    action,values=policy.command('iso',False,800,camera())
    assert action=='settings' and values=={'iso':800,'exposureNs':16666666}

def test_adjusting_iso_preserves_existing_manual_shutter_on_phone():
    action,values=policy.command('iso',False,400,camera(manualIso=100,manualExposureNs=10000000))
    assert values=={'iso':400}
    assert policy.command('focus',False,2,camera(manualIso=100,manualExposureNs=10000000))==('settings',{'focus':2})

def test_auto_is_paired_and_ev_is_quantized_to_camera_step():
    assert policy.command('shutter',True,1,camera())==('settings',{'iso':0,'exposureNs':0})
    s=camera(imageControls=dict(evMin=-2,evMax=2,evStep=1/3))
    assert policy.command('ev',False,.5,s)[1]['ev']==2/3
    s['manualIso']=100
    assert not policy.supported('ev',s)

def test_lens_or_fps_switch_invalidates_open_sheet():
    a=camera();b=camera();b['videoFps']=30
    assert policy.context(a)!=policy.context(b)
    b=dict(a,isoMax=6400)
    assert policy.context(a)!=policy.context(b)

def test_preset_keeps_exact_iso_at_high_sensitivity():
    from appearance import SliderField
    field=SliderField(50,102400,50,0,True)
    field.set_value(51200)
    assert field.value()==51200
    field.slider.setValue(field.slider.value()+20)
    assert field.value()!=51200
