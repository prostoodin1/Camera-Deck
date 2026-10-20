import sys
from pathlib import Path
import zipfile
import pytest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"desktop"))
import updates


@pytest.mark.parametrize("name",["../evil.dll","CameraDeck-Portable/../evil.dll","CameraDeck-Portable/_internal/../../evil.dll","CameraDeck-Portable/C:/evil.dll","CameraDeck-Portable/_internal\\evil.dll"])
def test_portable_rejects_escaping_paths(tmp_path,name):
    archive=tmp_path/"update.zip"
    with zipfile.ZipFile(archive,"w") as z:z.writestr(name,b"bad")
    with pytest.raises(ValueError):updates.extract_portable(archive,tmp_path/"out")
    assert not (tmp_path/"evil.dll").exists()


def test_portable_extract_and_platform_selection(tmp_path,monkeypatch):
    archive=tmp_path/"update.zip"
    with zipfile.ZipFile(archive,"w") as z:
        z.writestr("CameraDeck-Portable/CameraDeck-Portable.exe",b"exe")
        z.writestr("CameraDeck-Portable/_internal/python313.dll",b"dll")
    root=updates.extract_portable(archive,tmp_path/"out")
    monkeypatch.setattr(sys,"frozen",True,raising=False)
    monkeypatch.setattr(sys,"executable",str(root/"CameraDeck-Portable.exe"))
    monkeypatch.setattr(sys,"_MEIPASS",str(root/"_internal"),raising=False)
    assert updates.windows_platform()=="windowsPortable"
    monkeypatch.setattr(sys,"_MEIPASS",str(tmp_path/"_MEI1234"))
    assert updates.windows_platform()=="windows"
