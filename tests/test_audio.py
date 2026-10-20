import os
os.environ["QT_QPA_PLATFORM"]="offscreen"
from pathlib import Path
import sys
import threading
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"desktop"))
from PyQt6.QtWidgets import QApplication
from audio import AudioMonitor


def test_live_pcm_queue_is_bounded_and_validates_format():
    app=QApplication.instance() or QApplication([])
    class Response:
        status=200
        count=0
        def getheader(self,name):return {"X-Audio-Encoding":"pcm-s16le","X-Audio-Rate":"48000","X-Audio-Channels":"1"}[name]
        def read(self,size):
            self.count+=1
            return bytes([self.count])*1920 if self.count<=8 else b""
    class Connection:
        def request(self,*args,**kwargs):assert kwargs["headers"]["Authorization"]=="Bearer test-session"
        def getresponse(self):return Response()
        def close(self):pass
    class Client:
        token="test-session"
        def connection(self):return Connection()
    monitor=AudioMonitor(Client())
    failures=[];monitor.failed.connect(failures.append)
    monitor.receive(0,threading.Event())
    assert monitor.chunks.qsize()==5
    assert monitor.chunks.get_nowait()[1]==bytes([4])*1920  # Older backlog dropped, not accumulated.
    assert failures
    monitor.stop()
    assert monitor.chunks.empty()


def test_malformed_audio_is_rejected_before_playback():
    app=QApplication.instance() or QApplication([])
    class Response:
        status=200
        def getheader(self,name):return "wrong"
        def read(self,size):raise AssertionError("Must not consume unknown PCM format")
    class Connection:
        def request(self,*a,**kw):pass
        def getresponse(self):return Response()
        def close(self):pass
    class Client:
        token="test"
        def connection(self):return Connection()
    monitor=AudioMonitor(Client()); failures=[];monitor.failed.connect(failures.append)
    monitor.receive(0,threading.Event())
    assert failures and monitor.chunks.empty()
