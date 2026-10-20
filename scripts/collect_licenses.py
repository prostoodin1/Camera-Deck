"""Collect installed dependency license texts for reproducible public bundles."""
from importlib import metadata
from pathlib import Path
import shutil
import sys

root=Path(__file__).resolve().parents[1]
out=root/"third_party_licenses";out.mkdir(exist_ok=True)
for name in ("PyQt6","PyQt6-Qt6","PyQt6-sip","cryptography","cffi","pycparser"):
    distribution=metadata.distribution(name)
    destination=out/(name+"-"+distribution.version);destination.mkdir(exist_ok=True)
    for item in distribution.files or []:
        text=str(item).replace("\\","/")
        if "/licenses/" not in text and "LICENSE" not in item.name.upper() and "COPYING" not in item.name.upper():continue
        source=Path(distribution.locate_file(item))
        if not source.is_file():continue
        relative=text.split("/licenses/",1)[-1] if "/licenses/" in text else item.name
        target=destination/relative
        if not target.resolve().is_relative_to(destination.resolve()):raise RuntimeError("Invalid license path")
        target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
python_license=Path(sys.base_prefix)/"LICENSE.txt"
if python_license.is_file():shutil.copy2(python_license,out/"CPython-LICENSE.txt")
shutil.copy2(root/"LICENSE",out/"CameraDeck-GPL-3.0.txt")
print("Collected",sum(p.is_file() for p in out.rglob("*")),"license files")
