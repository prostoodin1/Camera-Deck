"""Automatic phone delivery must preserve capture and existing trust boundaries."""
import os,sys
from pathlib import Path
from types import SimpleNamespace
os.environ['QT_QPA_PLATFORM']='offscreen'
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'desktop'))
import updates
from main import Window


def desk(status,recording=False,enabled=True):
    sent=[]
    client=SimpleNamespace(fingerprint='a'*64,send_update=lambda apk,envelope:sent.append((apk,envelope)))
    tile=SimpleNamespace(status=status,client=client,failures=0,stopped=SimpleNamespace(is_set=lambda:False))
    window=SimpleNamespace(auto=SimpleNamespace(isChecked=lambda:enabled),is_recording=lambda:recording,tiles=[tile],run=lambda work,done,fail:done(work()))
    return window,sent


def test_automatic_delivery_uses_signed_envelope_and_stages_once(monkeypatch,tmp_path):
    apk=tmp_path/'verified.apk';apk.write_bytes(b'fixture')
    monkeypatch.setattr(updates,'download_update',lambda result,platform:apk)
    window,sent=desk(dict(clientChannel=True,versionCode=16,stagedUpdateCode=0))
    result=dict(code=17,envelope=b'signed')
    Window.patch_phones(window,result)
    assert sent==[(apk,b'signed')]
    Window.patch_phones(window,result)
    assert len(sent)==1


def test_automatic_delivery_waits_for_capture_and_respects_phone_preference(monkeypatch):
    monkeypatch.setattr(updates,'download_update',lambda *_:(_ for _ in ()).throw(AssertionError('Unexpected download')))
    for status,recording,enabled in [
        (dict(clientChannel=True,versionCode=16),True,True),
        (dict(clientChannel=True,versionCode=16),False,False),
        (dict(clientChannel=True,versionCode=16,autoUpdates=False),False,True),
        (dict(clientChannel=False,versionCode=15),False,True),
        (dict(clientChannel=True,versionCode=16,stagedUpdateCode=17),False,True),
    ]:
        window,sent=desk(status,recording,enabled)
        Window.patch_phones(window,dict(code=17,envelope=b'signed'))
        assert not sent
