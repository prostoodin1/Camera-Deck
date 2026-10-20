import os
from pathlib import Path
import subprocess
import sys
PROJECT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(PROJECT/"desktop"))
from updates import _protect
folder=Path(os.environ["LOCALAPPDATA"])/"CameraDeck"/"signing"
env=dict(os.environ, CAMERA_DECK_KEYSTORE=str(folder/"camera-deck.jks"),
    CAMERA_DECK_STORE_PASSWORD=_protect((folder/"keystore-password.dpapi").read_bytes(),True).decode())
gradle=Path(os.environ["CAMERA_DECK_GRADLE"])
result=subprocess.run([str(gradle),"--no-daemon",":app:assembleRelease",":app:testReleaseUnitTest",":app:lintRelease"],cwd=PROJECT/"android",env=env)
raise SystemExit(result.returncode)
