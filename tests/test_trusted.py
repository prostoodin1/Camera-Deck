import sys,json
from pathlib import Path
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'desktop'))
import trusted,updates
from protocol import CameraClient


def test_remembered_token_is_encrypted_and_can_be_forgotten(tmp_path,monkeypatch):
    monkeypatch.setattr(updates,'DATA',tmp_path)
    secret='a'*64;trusted.remember('phone','192.168.1.2','b'*64,'Phone',secret)
    raw=(tmp_path/'trusted.dpapi').read_bytes();assert secret.encode() not in raw
    saved=trusted.load();assert saved['phone']['secret']==secret
    assert trusted.match({'id':'phone','fingerprint':'b'*64},saved)
    assert trusted.match({'id':'phone','fingerprint':'c'*64},saved) is None
    assert trusted.match({'id':'other','fingerprint':'b'*64},saved) is None
    trusted.forget('phone');assert trusted.load()=={}


def test_resume_uses_saved_secret_only_after_pinned_request(monkeypatch):
    client=CameraClient('192.168.1.2','a'*64);calls=[]
    def request(path,body):calls.append((path,body));return {'token':'session'}
    monkeypatch.setattr(client,'json',request)
    client.resume('b'*64);assert client.token=='session' and calls==[('/resume',{'rememberToken':'b'*64})]


def test_pair_requires_pin_even_when_remembering(monkeypatch):
    client=CameraClient('192.168.1.2','a'*64)
    monkeypatch.setattr(client,'json',lambda *args:pytest.fail('Invalid PIN must not reach network'))
    with pytest.raises(ValueError):client.pair('abcd',True)


def test_parallel_status_and_stream_expiry_renews_only_the_old_session(monkeypatch):
    client=CameraClient('192.168.1.2','a'*64);client.token='expired';client.refresh_secret='b'*64;calls=[]
    def resume(secret):calls.append(secret);client.token='new-session'
    monkeypatch.setattr(client,'resume',resume)
    client.renew('expired');client.renew('expired')
    assert calls==['b'*64] and client.force_avc
