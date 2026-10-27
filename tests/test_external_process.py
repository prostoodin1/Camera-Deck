import sys,os,subprocess
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'desktop'))
import external_process

def test_new_instances_reset_pyinstaller_and_drop_private_path(monkeypatch,tmp_path):
    bundle=tmp_path/'bundle';bundle.mkdir()
    monkeypatch.setattr(sys,'_MEIPASS',str(bundle),raising=False)
    monkeypatch.setenv('PATH',os.pathsep.join([str(bundle),str(bundle/'Qt6/bin'),'C:/Windows/System32']))
    env=external_process.environment()
    assert env['PYINSTALLER_RESET_ENVIRONMENT']=='1'
    assert env['PATH']=='C:/Windows/System32'

def test_frozen_launch_clears_and_restores_dll_directory(monkeypatch):
    calls=[]
    class Kernel:
        def GetDllDirectoryW(self,size,buffer):
            if buffer is not None:buffer.value='C:/private-runtime'
            return 18
        def SetDllDirectoryW(self,value):calls.append(value);return 1
    from types import SimpleNamespace
    monkeypatch.setattr(sys,'frozen',True,raising=False)
    monkeypatch.setattr(sys,'platform','win32')
    monkeypatch.setattr(external_process.ctypes,'windll',SimpleNamespace(kernel32=Kernel()),raising=False)
    def launch(args,**kwargs):
        assert calls==[None]
        assert kwargs['env']['PYINSTALLER_RESET_ENVIRONMENT']=='1'
        return 'child'
    monkeypatch.setattr(subprocess,'Popen',launch)
    assert external_process.popen(['system-program'])=='child'
    assert calls==[None,'C:/private-runtime']
