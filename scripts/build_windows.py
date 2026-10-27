"""Build both Windows distributions with an explicit, private VC runtime."""
from pathlib import Path
import subprocess
import sys
import zipfile
import json
import os

ROOT = Path(__file__).resolve().parents[1]


def main():
    if sys.platform != "win32":
        raise SystemExit("Windows packaging requires Windows")
    import PyQt6
    # Qt may require newer MSVC entry points than the Python installation.
    # Ship the complete compatible upstream runtime supplied with this Qt wheel.
    runtime = Path(PyQt6.__file__).parent/'Qt6/bin'
    required = [runtime / name for name in ("vcruntime140.dll", "vcruntime140_1.dll")]
    for dll in required:
        if not dll.is_file():
            raise SystemExit(f"Missing Python runtime dependency: {dll.name}")
    from PyInstaller.utils.win32.versioninfo import VSVersionInfo,FixedFileInfo,StringFileInfo,StringTable,StringStruct,VarFileInfo,VarStruct
    version=json.loads((ROOT/'version.json').read_text())['version']
    number=tuple(int(n) for n in version.split('.'))+(0,)
    info=VSVersionInfo(ffi=FixedFileInfo(filevers=number,prodvers=number,fileType=1),kids=[
        StringFileInfo([StringTable('040904b0',[
            StringStruct('CompanyName','Camera Deck contributors'),
            StringStruct('FileDescription','Camera Deck Android camera control desk'),
            StringStruct('FileVersion',version),StringStruct('ProductVersion',version),
            StringStruct('ProductName','Camera Deck'),StringStruct('OriginalFilename','CameraDeck.exe'),
            StringStruct('LegalCopyright','Camera Deck contributors - GPL-3.0')])]),
        VarFileInfo([VarStruct('Translation',[1033,1200])])])
    metadata=ROOT/'desktop/build/camera-deck-version.txt';metadata.parent.mkdir(exist_ok=True,parents=True);metadata.write_text(str(info),encoding='utf-8')
    # Stable build inputs make package comparison and investigation meaningful.
    build_env=dict(os.environ,PYTHONHASHSEED='1',SOURCE_DATE_EPOCH=subprocess.check_output(['git','show','-s','--format=%ct','HEAD'],cwd=ROOT,text=True).strip())
    common = [sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean", "--noupx",
              "--version-file",str(metadata),
              "--windowed", "--add-data", "update-public.pem;.",
              "--add-data", "aurora-update-public.pem;.", "--add-data", "client-update-public.pem;.", "--collect-all", "av",
              "--add-data", "../third_party_licenses;third_party_licenses",
              "--add-data", "../THIRD_PARTY_NOTICES.md;."]
    for dll in sorted(runtime.glob('*140*.dll')):
        common += ["--add-binary", f"{dll};."]
    sys.path.insert(0,str(ROOT/'desktop'))
    from usb_transport import adb_path
    platform_tools=Path(adb_path()).parent
    for name in ('adb.exe','AdbWinApi.dll','AdbWinUsbApi.dll'):
        source=platform_tools/name
        if not source.is_file():raise SystemExit('Incomplete USB runtime: '+name)
        common += ['--add-binary',f'{source};platform-tools']
    notice=platform_tools/'NOTICE.txt'
    if not notice.is_file():raise SystemExit('Missing Android Platform Tools NOTICE.txt')
    common += ['--add-data',f'{notice};platform-tools']
    subprocess.run(common + ["--onefile", "--name", "CameraDeck", "main.py"], cwd=ROOT / "desktop", env=build_env,check=True)
    if "--client-only" in sys.argv:return
    subprocess.run(common + ["--onedir", "--name", "CameraDeck-Portable", "main.py"], cwd=ROOT / "desktop", env=build_env,check=True)
    folder = ROOT / "desktop/dist/CameraDeck-Portable"
    for name in ("python313.dll", "vcruntime140.dll", "vcruntime140_1.dll"):
        if not (folder / "_internal" / name).is_file():
            raise SystemExit(f"Incomplete portable bundle: {name}")
    with zipfile.ZipFile(ROOT / "desktop/dist/CameraDeck-Portable.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(folder.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(folder.parent))


if __name__ == "__main__":
    main()
