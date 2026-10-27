"""Build both Windows distributions with an explicit, private VC runtime."""
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    if sys.platform != "win32":
        raise SystemExit("Windows packaging requires Windows")
    runtime = Path(sys.base_prefix)
    required = [runtime / name for name in ("vcruntime140.dll", "vcruntime140_1.dll")]
    for dll in required:
        if not dll.is_file():
            raise SystemExit(f"Missing Python runtime dependency: {dll.name}")
    common = [sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean", "--noupx",
              "--windowed", "--add-data", "update-public.pem;.",
              "--add-data", "aurora-update-public.pem;.", "--add-data", "client-update-public.pem;.", "--collect-all", "av",
              "--add-data", "../third_party_licenses;third_party_licenses",
              "--add-data", "../THIRD_PARTY_NOTICES.md;."]
    for dll in required:
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
    subprocess.run(common + ["--onefile", "--name", "CameraDeck", "main.py"], cwd=ROOT / "desktop", check=True)
    if "--client-only" in sys.argv:return
    subprocess.run(common + ["--onedir", "--name", "CameraDeck-Portable", "main.py"], cwd=ROOT / "desktop", check=True)
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
