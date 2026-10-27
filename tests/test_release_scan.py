import datetime,hashlib,importlib.util,json
from pathlib import Path
import pytest

spec=importlib.util.spec_from_file_location('release_scan',Path(__file__).resolve().parents[1]/'scripts/release_scan.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

@pytest.fixture
def scan(tmp_path):
    exe=tmp_path/'CameraDeck-Setup.exe';exe.write_bytes(b'harmless test fixture, not an executable')
    now=datetime.datetime.now(datetime.timezone.utc).isoformat()
    data=dict(schema=1,scanner='Microsoft Defender',result='clean',exitCode=0,sha256=hashlib.sha256(exe.read_bytes()).hexdigest(),sourceCommit='fixture',completedAt=now,signatureUpdatedAt=now,signatureVersion='test',engineVersion='test')
    def save(): (tmp_path/'WINDOWS-DEFENDER-SCAN.json').write_text(json.dumps(data))
    save();return tmp_path,data,save

def test_valid_scan(scan):
    folder,data,save=scan
    assert module.validate_scan(folder,'fixture')['result']=='clean'

@pytest.mark.parametrize('field,value',[('sha256','wrong'),('sourceCommit','other'),('result','detected'),('exitCode',2),('completedAt','2000-01-01T00:00:00Z'),('completedAt','2099-01-01T00:00:00Z'),('signatureUpdatedAt','2000-01-01T00:00:00Z'),('engineVersion','')])
def test_invalid_scan_blocks_publication(scan,field,value):
    folder,data,save=scan;data[field]=value;save()
    with pytest.raises(ValueError):module.validate_scan(folder,'fixture')

def test_scan_missing_or_file_changed_blocks_publication(scan):
    folder,data,save=scan;(folder/'CameraDeck-Setup.exe').write_bytes(b'changed')
    with pytest.raises(ValueError):module.validate_scan(folder,'fixture')
    (folder/'WINDOWS-DEFENDER-SCAN.json').unlink()
    with pytest.raises(FileNotFoundError):module.validate_scan(folder,'fixture')
