import os,sys,threading
from pathlib import Path
os.environ['QT_QPA_PLATFORM']='offscreen'
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'desktop'))
import pytest,audio
from PyQt6.QtWidgets import QApplication

def test_selected_missing_output_never_silently_switches_to_speakers(monkeypatch):
    app=QApplication.instance() or QApplication([])
    monitor=audio.AudioMonitor(object());monitor.device_id=b'headset'
    monkeypatch.setattr(audio.QMediaDevices,'audioOutputs',lambda:[])
    with pytest.raises(RuntimeError,match='отключён'):monitor.start()
    assert monitor.sink is None
    monitor.stop()

def test_switching_output_discards_old_audio_backlog_and_keeps_volume():
    app=QApplication.instance() or QApplication([])
    monitor=audio.AudioMonitor(object())
    monitor.chunks.put((0,b'old'));monitor.pending=b'old'
    monitor.configure_output(b'headset',.3)
    assert monitor.device_id==b'headset' and monitor.volume==.3
    assert monitor.pending==b'' and monitor.chunks.empty()
    monitor.configure_output(b'',2)
    assert monitor.volume==1 and monitor.device_id==b''

def test_phone_mono_pcm_converts_to_native_stereo_without_padding():
    import av,array
    converter=av.AudioResampler(format='s16',layout='stereo',rate=48000)
    output=audio.convert_pcm(array.array('h',[10000]*960).tobytes(),converter)
    assert len(output)==960*2*2
    values=array.array('h');values.frombytes(output)
    assert all(values[i]==values[i+1]>0 for i in range(0,len(values),2))
