"""One persistent Windows client path; updates replace code, never the user profile."""
from pathlib import Path
import hashlib,json,os,shutil,subprocess,sys,time
import updates

def target_path():return updates.DATA/'Client'/'CameraDeck.exe'
def quote(value):return "'"+str(value).replace("'","''")+"'"
def ensure_installed():
    if not getattr(sys,'frozen',False) or '--self-test' in sys.argv:return False
    source=Path(sys.executable).resolve();target=target_path()
    if source==target.resolve():return False
    target.parent.mkdir(parents=True,exist_ok=True);version=target.parent/'client-version.json'
    try:code=int(json.loads(version.read_text()).get('code',0))
    except (OSError,ValueError,TypeError):code=0
    if not target.exists() or code<updates.VERSION_CODE:
        candidate=target.with_suffix('.installing')
        shutil.copy2(source,candidate)
        with source.open('rb') as a,candidate.open('rb') as b:
            if hashlib.file_digest(a,'sha256').digest()!=hashlib.file_digest(b,'sha256').digest():raise OSError('Incomplete installation copy')
        try:
            if target.exists():shutil.copy2(target,target.with_suffix('.exe.previous'))
            candidate.replace(target);version.write_text(json.dumps({'version':updates.VERSION,'code':updates.VERSION_CODE}))
        except PermissionError:
            candidate.unlink(missing_ok=True)  # An existing client owns the running image.
    script="$w=New-Object -ComObject WScript.Shell; $p=Join-Path $w.SpecialFolders('Programs') 'Camera Deck'; New-Item -ItemType Directory -Path $p -Force | Out-Null; $s=$w.CreateShortcut((Join-Path $p 'Camera Deck.lnk')); $s.TargetPath="+quote(target)+"; $s.WorkingDirectory="+quote(target.parent)+"; $s.Save()"
    subprocess.run(['powershell','-NoProfile','-NonInteractive','-Command',script],capture_output=True,timeout=15,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
    subprocess.Popen([str(target)],cwd=target.parent,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
    return True

def acknowledge_boot():
    if not getattr(sys,'frozen',False) or Path(sys.executable).resolve()!=target_path().resolve():return
    folder=target_path().parent
    (folder/'client-version.json').write_text(json.dumps({'version':updates.VERSION,'code':updates.VERSION_CODE}))
    temp=folder/'boot-ack.next';temp.write_text(json.dumps({'code':updates.VERSION_CODE,'pid':os.getpid(),'time':time.time()}));temp.replace(folder/'boot-ack.json')

def single_instance(app):
    from PyQt6.QtNetwork import QLocalServer,QLocalSocket
    name='CameraDeckClient-'+hashlib.sha256(str(updates.DATA.resolve()).encode()).hexdigest()[:20]
    server=QLocalServer(app);server.setSocketOptions(QLocalServer.SocketOption.UserAccessOption)
    if server.listen(name):return server
    socket=QLocalSocket();socket.connectToServer(name)
    if socket.waitForConnected(1000):socket.write(b'raise');socket.waitForBytesWritten(1000);socket.close();return None
    QLocalServer.removeServer(name)
    if not server.listen(name):raise RuntimeError('Не удалось открыть постоянный клиент')
    return server

def activate_on_message(server,window):
    def receive():
        while server.hasPendingConnections():
            socket=server.nextPendingConnection();socket.close();socket.deleteLater()
            window.showNormal();window.raise_();window.activateWindow()
    server.newConnection.connect(receive)
    if server.hasPendingConnections():receive()

def auto_update_allowed(recording,enabled,frozen,downloaded,idle_seconds):
    return bool(enabled and frozen and downloaded and not recording and idle_seconds>=30)

def failed_code():
    try:return int(json.loads((target_path().parent/'update-state.json').read_text(encoding='utf-8-sig')).get('failedCode',0))
    except (OSError,ValueError,TypeError):return 0
