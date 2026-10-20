"""Create persistent local signing identities, outside the repository."""
import os
from pathlib import Path
import secrets
import subprocess
import sys
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / "desktop"))
from updates import _protect


def main():
    folder = Path(os.environ["LOCALAPPDATA"]) / "CameraDeck" / "signing"
    folder.mkdir(parents=True,exist_ok=True)
    # Only the current Windows identity and SYSTEM may read release signing material.
    identity = subprocess.check_output(["whoami"], text=True).strip()
    subprocess.run(["icacls", str(folder), "/inheritance:r", "/grant:r", identity+":(OI)(CI)F", "SYSTEM:(OI)(CI)F"],check=True,stdout=subprocess.DEVNULL)
    private_file=folder/"update-private.pem"
    if not private_file.exists():
        key=rsa.generate_private_key(public_exponent=65537,key_size=3072)
        private_file.write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
    key=serialization.load_pem_private_key(private_file.read_bytes(),password=None)
    public=key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo)
    for path in (PROJECT/"desktop"/"update-public.pem",PROJECT/"android"/"app"/"src"/"main"/"assets"/"update-public.pem"):
        path.parent.mkdir(parents=True,exist_ok=True); path.write_bytes(public)
    store=folder/"camera-deck.jks"
    password_file=folder/"keystore-password.dpapi"
    if store.exists() != password_file.exists():
        raise RuntimeError("Incomplete signing identity; restore its backup instead of replacing it")
    if not store.exists():
        password=secrets.token_urlsafe(36)
        env=dict(os.environ,CAMERA_DECK_STORE_PASSWORD=password)
        java=Path(os.environ["JAVA_HOME"])/"bin"/"keytool.exe"
        subprocess.run([str(java),"-genkeypair","-keystore",str(store),"-storetype","JKS","-alias","camera-deck",
            "-keyalg","RSA","-keysize","3072","-validity","10000","-dname","CN=Camera Deck, O=Ideas Projects",
            "-storepass:env","CAMERA_DECK_STORE_PASSWORD","-keypass:env","CAMERA_DECK_STORE_PASSWORD"],check=True,env=env,capture_output=True)
        password_file.write_bytes(_protect(password.encode()))
    print("Signing identities ready; private keys remain outside Git.")


if __name__ == "__main__": main()
