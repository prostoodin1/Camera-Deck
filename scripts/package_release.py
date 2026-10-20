"""Package both built apps and sign the update envelope with the persistent key."""
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import padding

project=Path(__file__).resolve().parents[1]
version=json.loads((project/"version.json").read_text())
out=project/"release"/version["version"]; out.mkdir(parents=True,exist_ok=True)
manifest={"product":"camera-deck","version":version["version"],"code":version["code"],"protocol":1}
for platform,source,name in (
    ("windows",project/"desktop"/"dist"/"CameraDeck.exe",f"CameraDeck-Windows-{version['version']}.exe"),
    ("android",project/"android"/"app"/"build"/"outputs"/"apk"/"release"/"app-release.apk",f"CameraDeck-Android-{version['version']}.apk")):
    target=out/name; shutil.copy2(source,target)
    with target.open("rb") as stream: digest=hashlib.file_digest(stream,"sha256").hexdigest()
    manifest[platform]={"name":name,"size":target.stat().st_size,"sha256":digest}
payload=json.dumps(manifest,sort_keys=True,separators=(",",":")).encode()
private=Path(os.environ["LOCALAPPDATA"])/"CameraDeck"/"signing"/"update-private.pem"
key=serialization.load_pem_private_key(private.read_bytes(),password=None)
signature=key.sign(payload,padding.PKCS1v15(),hashes.SHA256())
(out/"camera-deck-update.json").write_text(json.dumps({"payload":base64.b64encode(payload).decode(),"signature":base64.b64encode(signature).decode()},indent=2))
checksums=[]
for path in sorted(out.iterdir()):
    if path.is_file() and path.name != "SHA256SUMS.txt":
        with path.open("rb") as stream: digest=hashlib.file_digest(stream,"sha256").hexdigest()
        checksums.append(digest+"  "+path.name)
(out/"SHA256SUMS.txt").write_text("\n".join(checksums)+"\n")
print(json.dumps(manifest,indent=2))
