"""Sign only the separate Aurora test channel with a persistent local identity.

Never creates/replaces the original Camera Deck APK or updater identities.
"""
import argparse
import os
from pathlib import Path
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/"desktop"))
from updates import _protect


def main():
    parser=argparse.ArgumentParser();parser.add_argument("source",type=Path);parser.add_argument("output",type=Path);args=parser.parse_args()
    sdk=Path(os.environ["ANDROID_HOME"]);java=Path(os.environ["JAVA_HOME"])/"bin"
    build=sorted((sdk/"build-tools").iterdir())[-1]
    package=subprocess.check_output([str(build/"aapt2.exe"),"dump","badging",str(args.source)],text=True,encoding="utf-8")
    if "package: name='io.github.prostoodin1.cameradeck.aurora'" not in package:
        raise RuntimeError("Refusing to sign anything except the separate Aurora preview package")
    folder=Path(os.environ["LOCALAPPDATA"])/"CameraDeck"/"aurora-preview-signing"
    store=folder/"aurora-preview.jks";secret=folder/"password.dpapi"
    if not store.is_file() or not secret.is_file():
        raise RuntimeError("Restore the existing Aurora signing identity; refusing to create a replacement key")
    identity=subprocess.check_output(["whoami"],text=True).strip()
    subprocess.run(["icacls",str(folder),"/inheritance:r","/grant:r",identity+":(OI)(CI)F","SYSTEM:(OI)(CI)F"],check=True,stdout=subprocess.DEVNULL)
    password=_protect(secret.read_bytes(),True).decode()
    env=dict(os.environ,AURORA_PREVIEW_PASSWORD=password)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    subprocess.run([str(java/"java.exe"),"-jar",str(build/"lib"/"apksigner.jar"),"sign","--ks",str(store),"--ks-key-alias","aurora-preview","--ks-pass","env:AURORA_PREVIEW_PASSWORD","--key-pass","env:AURORA_PREVIEW_PASSWORD","--out",str(args.output),str(args.source)],env=env,check=True)
    subprocess.run([str(java/"java.exe"),"-jar",str(build/"lib"/"apksigner.jar"),"verify",str(args.output)],check=True)
    print("Signed Aurora preview with its persistent local identity; original release keys unchanged.")


if __name__=="__main__":main()
