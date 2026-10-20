"""Persistent, separate Aurora update channel. Original release identities are untouched."""
import argparse,base64,json,os,sys,hashlib
from pathlib import Path
from cryptography.hazmat.primitives import serialization,hashes
from cryptography.hazmat.primitives.asymmetric import rsa,padding
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'desktop'))
import updates


def key():
    folder=Path(os.environ['LOCALAPPDATA'])/'CameraDeck'/'aurora-preview-signing'
    if not folder.is_dir():raise RuntimeError('First restore/create the Aurora APK signing identity')
    path=folder/'aurora-updates.dpapi'
    if path.exists():private=serialization.load_pem_private_key(updates._protect(path.read_bytes(),True),None)
    else:
        if (ROOT/'desktop/aurora-update-public.pem').exists():raise RuntimeError('Missing Aurora private update key; restore backup, do not replace identity')
        private=rsa.generate_private_key(public_exponent=65537,key_size=3072)
        path.write_bytes(updates._protect(private.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption())))
    public=private.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo)
    for name in ('desktop/aurora-update-public.pem','android/app/src/main/assets/aurora-update-public.pem'):
        target=ROOT/name
        if target.exists() and serialization.load_pem_public_key(target.read_bytes()).public_numbers()!=private.public_key().public_numbers():raise RuntimeError('Aurora identity mismatch')
        target.write_bytes(public)
    return private


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--init',action='store_true');parser.add_argument('folder',nargs='?',type=Path);args=parser.parse_args();private=key()
    if args.init:return
    if not args.folder:parser.error('folder is required')
    version=json.loads((ROOT/'version.json').read_text());manifest=dict(product='camera-deck-aurora',version=version['version'],code=version['code'],protocol=1)
    for platform,ext in [('windows','exe'),('android','apk')]:
        path=args.folder/('CameraDeck-Aurora-'+('Windows' if platform=='windows' else 'Android')+'.'+ext)
        manifest[platform]=dict(name=path.name,size=path.stat().st_size,sha256=hashlib.sha256(path.read_bytes()).hexdigest())
    payload=json.dumps(manifest,separators=(',',':'),sort_keys=True).encode();signature=private.sign(payload,padding.PKCS1v15(),hashes.SHA256())
    envelope=json.dumps(dict(payload=base64.b64encode(payload).decode(),signature=base64.b64encode(signature).decode())).encode()
    updates.verify_envelope(envelope,current_code=0)
    (args.folder/'camera-deck-update.json').write_bytes(envelope)
    print('Signed and verified Aurora update manifest')


if __name__=='__main__':main()
