from pathlib import Path
import sys,json
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'desktop'))
from client_install import auto_update_allowed
def test_auto_update_waits_for_capture_and_user_idle():
    assert not auto_update_allowed(True,True,True,True,1000)
    assert not auto_update_allowed(False,True,True,True,2)
    assert not auto_update_allowed(False,False,True,True,1000)
    assert not auto_update_allowed(False,True,False,True,1000)
    assert not auto_update_allowed(False,True,True,False,1000)
    assert auto_update_allowed(False,True,True,True,31)
def test_rollback_blocks_failed_patch_until_a_newer_one(tmp_path,monkeypatch):
    import client_install,updates
    monkeypatch.setattr(updates,'DATA',tmp_path)
    folder=client_install.target_path().parent;folder.mkdir()
    (folder/'update-state.json').write_text(json.dumps({'failedCode':17,'rolledBack':True}),encoding='utf-8-sig')
    assert client_install.failed_code()==17
