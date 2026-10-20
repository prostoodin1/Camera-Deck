import base64
import hashlib
import json
from pathlib import Path
import ssl
import sys
import urllib.request
import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

sys.path.insert(0, str(Path(__file__).resolve().parents[1]/"desktop"))
import protocol
import updates


@pytest.fixture
def identity():
    key = rsa.generate_private_key(public_exponent=65537,key_size=2048)
    public = key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo)
    return key,public


def signed(identity, change=None):
    data={"product":"camera-deck","version":"0.2.0","code":updates.VERSION_CODE+1,"protocol":1,
          "windows":{"name":"CameraDeck.exe","size":3,"sha256":"a"*64},
          "android":{"name":"CameraDeck.apk","size":4,"sha256":"b"*64}}
    if change: change(data)
    payload=json.dumps(data).encode()
    signature=identity[0].sign(payload,padding.PKCS1v15(),hashes.SHA256())
    return json.dumps({"payload":base64.b64encode(payload).decode(),"signature":base64.b64encode(signature).decode()}).encode()


def test_signed_release(identity):
    assert updates.verify_envelope(signed(identity),identity[1])["code"]==updates.VERSION_CODE+1


def test_manifest_tampering(identity):
    document=json.loads(signed(identity))
    document["payload"]=base64.b64encode(b'{"code":999}').decode()
    with pytest.raises(Exception): updates.verify_envelope(json.dumps(document).encode(),identity[1])


@pytest.mark.parametrize("change",[
    lambda d:d.update(code=1), lambda d:d.update(product="other"),lambda d:d.update(protocol=2),
    lambda d:d["android"].update(name="../../evil.apk"),lambda d:d["windows"].update(size=999_000_000),
    lambda d:d["windows"].update(sha256="bad"),
])
def test_wrong_update_metadata(identity,change):
    with pytest.raises(ValueError): updates.verify_envelope(signed(identity,change),identity[1])


def test_certificate_pin_checked_before_request(monkeypatch):
    class Sock:
        def getpeercert(self,binary_form=False): return b"actual-cert"
    class Connection:
        sent=False
        sock=Sock()
        def __init__(self,*a,**kw): pass
        def connect(self): pass
        def close(self): pass
        def request(self,*a,**kw): Connection.sent=True
    monkeypatch.setattr(protocol.http.client,"HTTPSConnection",Connection)
    client=protocol.CameraClient("192.168.1.2","0"*64)
    with pytest.raises(ssl.SSLError): client.pair("1234")
    assert not Connection.sent


@pytest.mark.parametrize("host",["8.8.8.8","127.0.0.1","0.0.0.0","224.0.0.1","example.org","::1"])
def test_nonlocal_addresses_rejected(host):
    with pytest.raises(ValueError): protocol.local_host(host)


def test_redirect_drops_github_credential():
    request=urllib.request.Request(updates.API+"/releases/assets/1",headers={"Authorization":"Bearer secret"})
    redirect=updates.SafeRedirect().redirect_request(request,None,302,"",{},"https://release-assets.githubusercontent.com/example")
    assert not redirect.has_header("Authorization")
    with pytest.raises(ValueError): updates.SafeRedirect().redirect_request(request,None,302,"",{},"https://evil.example/file")


def test_dpapi_round_trip():
    import os
    if os.name!="nt": pytest.skip("Windows DPAPI")
    token=b"test-only-no-real-secret"
    protected=updates._protect(token)
    assert token not in protected
    assert updates._protect(protected,True)==token


def test_versions_agree():
    version=json.loads((Path(__file__).resolve().parents[1]/"version.json").read_text())
    assert version["version"]==updates.VERSION and version["code"]==updates.VERSION_CODE


def test_public_update_check_does_not_request_credentials(monkeypatch):
    import io
    monkeypatch.setattr(updates,"github_token",lambda:(_ for _ in ()).throw(AssertionError("Public updates must not request credentials")))
    calls=[]
    def open_public(path,token,binary=False):
        calls.append((path,token,binary));return io.BytesIO(b"[]")
    monkeypatch.setattr(updates,"github_open",open_public)
    assert updates.check_updates() is None
    assert calls==[("/releases?per_page=100","",False)]


def test_anonymous_requests_omit_authorization(monkeypatch):
    import io
    class Opener:
        def open(self,request,timeout):
            assert not request.has_header("Authorization")
            assert "/prostoodin1/Camera-Deck/" in request.full_url
            return io.BytesIO(b"[]")
    monkeypatch.setattr(updates.urllib.request,"build_opener",lambda *args:Opener())
    assert updates.github_open("/releases","").read()==b"[]"
