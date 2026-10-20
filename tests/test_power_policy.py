import os
from pathlib import Path
import shutil
import subprocess
import pytest


def test_android_power_policy_transitions(tmp_path):
    root=Path(__file__).resolve().parents[1]
    java_home=Path(os.environ.get("JAVA_HOME",""))
    suffix=".exe" if os.name=="nt" else ""
    javac=str(java_home/"bin"/("javac"+suffix)) if (java_home/"bin"/("javac"+suffix)).is_file() else shutil.which("javac")
    java=str(java_home/"bin"/("java"+suffix)) if (java_home/"bin"/("java"+suffix)).is_file() else shutil.which("java")
    if not javac or not java: pytest.skip("JDK required for pure Java power policy test")
    source=root/"android/app/src/main/java/io/github/prostoodin1/cameradeck/PowerPolicy.java"
    subprocess.run([javac,"--release","17","-d",str(tmp_path),str(source),str(root/"tests/PowerPolicyTest.java")],check=True,capture_output=True)
    result=subprocess.run([java,"-cp",str(tmp_path),"io.github.prostoodin1.cameradeck.PowerPolicyTest"],check=True,capture_output=True,text=True)
    assert "transitions passed" in result.stdout
