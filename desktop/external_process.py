"""Launch system programs independently of PyInstaller's private DLL/runtime state."""
import ctypes,os,subprocess,sys,threading
from pathlib import Path
_launch_lock=threading.Lock()

def environment():
    env=dict(os.environ,PYINSTALLER_RESET_ENVIRONMENT='1')
    bundle=getattr(sys,'_MEIPASS',None)
    if bundle:
        base=Path(bundle).resolve()
        env['PATH']=os.pathsep.join(p for p in env.get('PATH','').split(os.pathsep) if p and not Path(p).resolve().is_relative_to(base))
    return env

def popen(args,**kwargs):
    kwargs.setdefault('env',environment())
    kwargs.setdefault('creationflags',getattr(subprocess,'CREATE_NO_WINDOW',0))
    with _launch_lock:
        frozen=sys.platform=='win32' and getattr(sys,'frozen',False)
        previous=None
        if frozen:
            kernel=ctypes.windll.kernel32
            size=kernel.GetDllDirectoryW(0,None)
            if size:
                buffer=ctypes.create_unicode_buffer(size+1);kernel.GetDllDirectoryW(len(buffer),buffer);previous=buffer.value
            if not kernel.SetDllDirectoryW(None):raise OSError('Cannot restore system DLL search path for updater')
        try:return subprocess.Popen(args,**kwargs)
        finally:
            if frozen:kernel.SetDllDirectoryW(previous)

def run(args,timeout=15):
    process=popen(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    try:out,error=process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:process.kill();process.communicate();raise
    return subprocess.CompletedProcess(args,process.returncode,out,error)
