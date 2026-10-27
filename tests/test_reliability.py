import os
os.environ['QT_QPA_PLATFORM']='offscreen'
import io,json,socket,struct,sys,threading,hashlib,itertools,shutil
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'desktop'))
import protocol,updates,recording


class Response(io.BytesIO):
    status=200
    def getheader(self,name,default=None):
        return {'Content-Length':str(len(self.getvalue())),'Content-Type':'application/x-cameradeck-recording'}.get(name,default)
class Connection:
    def __init__(self,data=b'photo'):self.response=Response(data);self.sock=self
    def settimeout(self,*args):pass
    def request(self,*args,**kwargs):pass
    def getresponse(self):return self.response
    def close(self):pass


def test_failed_download_preserves_another_transfers_partial(tmp_path,monkeypatch):
    client=protocol.CameraClient('192.168.1.2');monkeypatch.setattr(client,'connection',lambda:Connection())
    name='CD_123_ab12cd34.jpg';partial=tmp_path/(name+'.partial');partial.write_bytes(b'owned by other transfer')
    with pytest.raises(FileExistsError):client.download(name,tmp_path)
    assert partial.read_bytes()==b'owned by other transfer'


def test_discovery_continues_after_nonobject_json(monkeypatch):
    valid=dict(protocol=1,port=39812,id='phone',fingerprint='a'*64)
    packets=[[],None,42,'str',{**valid,'fingerprint':None},valid]
    class Sock:
        def __enter__(self):return self
        def __exit__(self,*args):pass
        def setsockopt(self,*args):pass
        def bind(self,*args):pass
        def settimeout(self,*args):pass
        def sendto(self,*args):pass
        def recvfrom(self,*args):
            if packets:return json.dumps(packets.pop(0)).encode(),('192.168.1.2',39811)
            raise socket.timeout()
    import subprocess
    monkeypatch.setattr(protocol.socket,'socket',lambda *args:Sock())
    monkeypatch.setattr(subprocess,'run',lambda *args,**kw:type('Result',(),{'stdout':'[]'})())
    clock=itertools.count();monkeypatch.setattr(protocol.time,'monotonic',lambda:next(clock))
    assert len(protocol.discover(10))==1


def test_update_downloads_do_not_share_temporary_files(tmp_path,monkeypatch):
    payload=b'verified executable'
    signed={'code':14,'windows':{'name':'CameraDeck.exe','size':len(payload),'sha256':hashlib.sha256(payload).hexdigest()}}
    update={**signed,'envelope':b'test','windows':{**signed['windows'],'assetId':1}}
    monkeypatch.setattr(updates,'verify_envelope',lambda *args,**kwargs:signed)
    monkeypatch.setattr(updates,'DATA',tmp_path)
    barrier=threading.Barrier(2)
    class Download(io.BytesIO):
        def read(self,size=-1):
            if self.tell()==0:
                barrier.wait(timeout=5);assert len(list(tmp_path.rglob('*.partial')))==2;barrier.wait(timeout=5)
            return super().read(size)
    monkeypatch.setattr(updates,'github_open',lambda *args:Download(payload))
    with ThreadPoolExecutor(2) as pool:
        futures=[pool.submit(updates.download_update,update,'windows') for _ in range(2)]
        paths=[f.result(timeout=10) for f in futures]
    assert paths[0]==paths[1] and paths[0].read_bytes()==payload
    assert list(paths[0].parent.iterdir())==[paths[0]]


def test_audio_reconnect_preserves_user_intent_without_view(tmp_path,monkeypatch):
    from test_studio import make_window
    from main import CameraTile
    app,window=make_window(tmp_path,monkeypatch)
    class Client:host='192.168.1.2';fingerprint='a'*64
    tile=CameraTile(window,Client(),'Phone');events=[]
    tile.monitor.start=lambda:events.append('start');tile.monitor.stop=lambda:events.append('stop')
    tile.listen.setChecked(True);tile.view_toggle.setChecked(False);tile.set_preview_active(True)
    assert events[-1]=='start' and not tile.preview_enabled.is_set()
    tile.audio_failed('temporary network failure')
    assert tile.listen.isChecked() and tile.audio_retry.isActive()
    tile.sync_audio();assert events[-1]=='start'
    tile.set_preview_active(False);assert events[-1]=='stop'
    tile.listen.setChecked(False);assert not tile.audio_retry.isActive()
    tile.stopped.set();tile.audio_retry.stop();window.close()


def test_recovery_job_blocks_disconnect_and_updates(tmp_path,monkeypatch):
    from test_studio import make_window
    from main import CameraTile
    app,window=make_window(tmp_path,monkeypatch)
    class Client:host='192.168.1.2';fingerprint='a'*64
    tile=CameraTile(window,Client(),'Phone');tile.status={'ready':True,'recording':False};window.tiles.append(tile)
    window.message=lambda *args:None
    window.studio.pc_jobs[id(tile)]={'state':'recovering'}
    assert window.is_recording()
    window.remove(tile);assert tile in window.tiles
    window.studio.pc_jobs.clear();window.close()


def test_broken_pc_stream_recovers_exact_phone_backup_without_restarting(tmp_path):
    from test_recording import make_recording
    original=make_recording(tmp_path);expected=original.read_bytes();name='CD_123_ab12cd34.mp4'
    hello=b'\x00'+json.dumps({'protocol':1,'sessionId':'take-1'}).encode()
    class Client:
        token='test';commands=[]
        def connection(self):return Connection(struct.pack('!I',len(hello))+hello)
        def command(self,action):self.commands.append(action)
        def json(self,path):
            if path=='/status':return dict(recordingSessionId='take-1',recordingBackupName=name,recordingBackupState='complete')
            return {'files':[{'name':name,'size':len(expected),'complete':True}]}
        def download(self,filename,folder):
            assert filename==name
            target=folder/filename;target.write_bytes(expected);return target
    client=Client();events=[];destination=tmp_path/'recovered.mp4'
    recording.receive_original(client,destination,lambda state,data:events.append((state,data)))
    assert client.commands==['record_start']
    assert destination.read_bytes()==expected and events[-1][1]['backupRecovered']
    assert json.loads(destination.with_suffix('.recording.json').read_text())['state']=='recovered'


def test_backup_recovery_waits_for_same_take_to_stop_and_does_not_use_another_take(tmp_path):
    clock=[0];name='CD_123_ab12cd34.mp4';statuses=iter([OSError('offline'),dict(recordingSessionId='take',recordingBackupState='recording'),dict(recordingSessionId='take',recordingBackupName=name,recordingBackupState='failed')])
    class Client:
        def json(self,path):
            state=next(statuses)
            if isinstance(state,Exception):raise state
            return state
        def command(self,*args):raise AssertionError('Recovery must not issue camera commands')
        def download(self,*args):raise AssertionError('Incomplete take must not be downloaded')
    def wait(seconds):clock[0]+=seconds
    with pytest.raises(RuntimeError,match='ошибку сохранения'):
        recording.recover_phone_backup(Client(),tmp_path/'out.mp4','take','',lambda *args:None,wait,lambda:clock[0])
    assert clock[0]>=3


def test_backup_recovery_times_out_without_restarting_phone(tmp_path):
    clock=[0]
    class Client:
        def json(self,path):raise ConnectionError('offline')
    def wait(seconds):clock[0]+=seconds
    with pytest.raises(ConnectionError,match='5 минут'):
        recording.recover_phone_backup(Client(),tmp_path/'out.mp4','take','',lambda *args:None,wait,lambda:clock[0])
    assert clock[0]==300


@pytest.mark.parametrize('value',[[],None,{'columns':'bad','geometry':123,'cameraGroups':None},{'cameraGroups':{'ok':'Group','bad':[]}}])
def test_invalid_preferences_cannot_crash_startup(tmp_path,monkeypatch,value):
    from test_studio import make_window
    (tmp_path/'preferences.json').write_text(json.dumps(value))
    app,window=make_window(tmp_path,monkeypatch)
    assert isinstance(window.load_preferences(),dict)
    window.close()


def test_expired_audio_session_renews_without_sending_unpinned_credentials():
    from PyQt6.QtWidgets import QApplication
    from audio import AudioMonitor
    app=QApplication.instance() or QApplication([])
    conn=Connection();conn.response.status=401
    class Client:
        token='old';refresh_secret='remembered';renewed=[]
        def connection(self):return conn
        def renew(self,token):self.renewed.append(token);self.token='new'
    client=Client();monitor=AudioMonitor(client);failures=[];monitor.failed.connect(failures.append)
    monitor.receive(0,threading.Event())
    assert client.renewed==['old'] and failures
    monitor.stop()


def test_unknown_backup_identity_never_downloads_a_different_take(tmp_path):
    class Client:
        def json(self,path):return {'recordingSessionId':'different','recordingBackupName':'CD_999_ab12cd34.mp4','recordingBackupState':'complete'}
        def download(self,*args):raise AssertionError('Must not take another recording')
    with pytest.raises(RuntimeError,match='однозначно'):
        recording.recover_phone_backup(Client(),tmp_path/'out.mp4','expected','',lambda *args:None)


def test_older_phone_interruption_is_not_blindly_restarted(tmp_path):
    hello=b'\x00'+b'{"protocol":1}'
    class Client:
        token='test';commands=[]
        def connection(self):return Connection(struct.pack('!I',len(hello))+hello)
        def command(self,action):self.commands.append(action)
    client=Client()
    with pytest.raises(ConnectionError):recording.receive_original(client,tmp_path/'out.mp4',lambda *args:None)
    assert client.commands==['record_start']


def test_signing_missing_identity_cannot_create_a_replacement(tmp_path,monkeypatch):
    sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
    import sign_preview
    sdk=tmp_path/'sdk';(sdk/'build-tools/35').mkdir(parents=True)
    monkeypatch.setenv('ANDROID_HOME',str(sdk));monkeypatch.setenv('JAVA_HOME',str(tmp_path/'java'));monkeypatch.setenv('LOCALAPPDATA',str(tmp_path/'private'))
    monkeypatch.setattr(sys,'argv',['sign_preview.py',str(tmp_path/'input.apk'),str(tmp_path/'output.apk')])
    monkeypatch.setattr(sign_preview.subprocess,'check_output',lambda *args,**kwargs:"package: name='io.github.prostoodin1.cameradeck.aurora'")
    monkeypatch.setattr(sign_preview.subprocess,'run',lambda *args,**kwargs:(_ for _ in ()).throw(AssertionError('No key generation or signing without existing identity')))
    with pytest.raises(RuntimeError,match='existing Aurora'):sign_preview.main()
    assert not (tmp_path/'private').exists() and not (tmp_path/'output.apk').exists()


def test_normal_audio_underrun_does_not_restart_the_output():
    from PyQt6.QtWidgets import QApplication
    from PyQt6.QtMultimedia import QAudio
    from audio import AudioMonitor
    app=QApplication.instance() or QApplication([])
    class Sink:
        def error(self):return QAudio.Error.UnderrunError
    monitor=AudioMonitor(object());failures=[];monitor.failed.connect(failures.append)
    monitor.sink=Sink();monitor.output=object();monitor.pump()
    assert not failures
    monitor.sink=None;monitor.output=None;monitor.stop()
