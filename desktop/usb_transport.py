"""Private ADB cable forwards; distinct module name avoids PyUSB packaging hooks."""
from pathlib import Path
import os,shutil,subprocess,sys,threading

def adb_path():
    candidates=[os.environ.get('CAMERA_DECK_ADB'),str(Path(getattr(sys,'_MEIPASS',Path(__file__).parent))/'platform-tools/adb.exe'),shutil.which('adb')]
    for key in ('ANDROID_SDK_ROOT','ANDROID_HOME'):
        if os.environ.get(key):candidates.append(str(Path(os.environ[key])/'platform-tools/adb.exe'))
    for value in candidates:
        if value and Path(value).is_file():return str(Path(value).resolve())
    raise RuntimeError('USB: не найден Android Platform Tools (adb). Установите комплект USB или укажите CAMERA_DECK_ADB.')

class UsbConnections:
    def __init__(self):self.forwards={};self.lock=threading.Lock();self.closed=False;self.adb=None
    def call(self,*args):
        if self.adb is None:self.adb=adb_path()
        result=subprocess.run([self.adb,*args],capture_output=True,text=True,timeout=15,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        if result.returncode:raise RuntimeError('USB: '+(result.stderr.strip() or 'ADB не выполнил команду')[:500])
        return result.stdout.strip()
    def discover(self):
        with self.lock:
            if self.closed:return []
            rows=self.call('devices','-l').splitlines();devices=[];unauthorized=False
            existing={tuple(row.split()) for row in self.call('forward','--list').splitlines()}
            for row in rows:
                parts=row.split()
                if len(parts)<2 or parts[0]=='List':continue
                serial,state=parts[:2]
                # Network ADB endpoints are not a cable connection.
                if ':' in serial or serial.startswith('emulator-') or serial.endswith('._adb-tls-connect._tcp'):continue
                if state=='unauthorized':unauthorized=True;continue
                if state!='device':continue
                port=self.forwards.get(serial)
                if port is not None and (serial,'tcp:'+str(port),'tcp:39812') not in existing:
                    self.forwards.pop(serial,None);port=None
                if port is None:
                    answer=self.call('-s',serial,'forward','tcp:0','tcp:39812')
                    if not answer.isdecimal() or not 1<=int(answer)<=65535:raise RuntimeError('USB: неверный порт туннеля')
                    port=int(answer);self.forwards[serial]=port
                model=next((p[6:].replace('_',' ') for p in parts if p.startswith('model:')),'Android')
                devices.append(dict(host='127.0.0.1',port=port,transport='usb',serial=serial,name=model+' · USB',fingerprint=''))
            if not devices:raise RuntimeError('USB: '+('разблокируйте телефон и разрешите USB-отладку для этого компьютера.' if unauthorized else 'подключите кабель передачи данных, включите USB-отладку и Camera Deck на телефоне. Wi-Fi не требуется.'))
            return devices
    def close(self):
        with self.lock:
            self.closed=True
            for serial,port in self.forwards.items():
                try:self.call('-s',serial,'forward','--remove','tcp:'+str(port))
                except (OSError,RuntimeError,subprocess.TimeoutExpired):pass
            self.forwards.clear()
