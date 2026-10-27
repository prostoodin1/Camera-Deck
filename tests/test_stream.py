import io
import threading
from pathlib import Path
import sys
import pytest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"desktop"))
from protocol import CameraClient,StreamUnavailable


def stream(monkeypatch,payload,status=200):
    class Response(io.BytesIO):
        def getheader(self,name,default=""):return "multipart/x-mixed-replace; boundary=aurora"
    response=Response(payload);response.status=status
    class Socket:
        def settimeout(self,n):pass
    class Connection:
        sock=Socket();closed=False
        def request(self,method,path,headers):
            assert path=="/stream" and headers["Authorization"]=="Bearer paired-token"
        def getresponse(self):return response
        def close(self):self.closed=True
    conn=Connection();client=CameraClient("192.168.1.2","a"*64);client.token="paired-token"
    monkeypatch.setattr(client,"connection",lambda:conn)
    enabled=threading.Event();enabled.set()
    return client.frames(threading.Event(),enabled),conn


def test_stream_reads_single_bounded_frame_and_closes(monkeypatch):
    frames,conn=stream(monkeypatch,b"--aurora\r\nContent-Length: 4\r\nX-Rotation: 90\r\n\r\nJPEG\r\n")
    assert next(frames)==(b"JPEG",90)
    frames.close();assert conn.closed


@pytest.mark.parametrize("payload",[
    b"--aurora\r\nContent-Length: 2000001\r\n\r\n",
    b"--wrong\r\n",
    b"--aurora\r\nContent-Length: 20\r\n\r\nshort",
])
def test_stream_rejects_unbounded_or_corrupt_frames(monkeypatch,payload):
    frames,conn=stream(monkeypatch,payload)
    with pytest.raises((ValueError,ConnectionError)):next(frames)
    assert conn.closed


def test_older_phone_can_fall_back_without_bypassing_auth(monkeypatch):
    frames,conn=stream(monkeypatch,b"",404)
    with pytest.raises(StreamUnavailable):next(frames)
    assert conn.closed


def test_revoked_session_is_not_treated_as_old_phone(monkeypatch):
    frames,conn=stream(monkeypatch,b"",401)
    with pytest.raises(ConnectionError) as error:next(frames)
    assert not isinstance(error.value,StreamUnavailable)
    assert conn.closed


def test_tracking_metadata_reaches_client_and_missing_header_clears_it(monkeypatch):
    import base64,json
    metadata={'faces':[{'id':4,'left':.1,'top':.2,'right':.4,'bottom':.6}],'trackingStamp':12}
    header=base64.b64encode(json.dumps(metadata).encode())
    frames,conn=stream(monkeypatch,b'--aurora\r\nContent-Length: 1\r\nX-Tracking: '+header+b'\r\n\r\nA\r\n--aurora\r\nContent-Length: 1\r\n\r\nB\r\n')
    assert next(frames)==(b'A',0)
    client=frames.gi_frame.f_locals['self']
    assert client.live_tracking==metadata
    assert next(frames)==(b'B',0)
    assert client.live_tracking=={}
    frames.close();assert conn.closed


@pytest.mark.parametrize('header',[b'!',b'e30='*7000])
def test_tracking_rejects_invalid_or_oversized_metadata(monkeypatch,header):
    frames,conn=stream(monkeypatch,b'--aurora\r\nContent-Length: 1\r\nX-Tracking: '+header+b'\r\n\r\nA')
    with pytest.raises(ValueError):next(frames)
    assert conn.closed
