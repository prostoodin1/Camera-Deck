"""Adopt the already installed APK certificate; keep release secrets outside the repo.

Only public keys are written into source. Secret files use Windows DPAPI plus an owner ACL.
Never regenerates an identity when its public key already exists.
"""
import argparse,hashlib,json,os,re,subprocess,sys
from pathlib import Path
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'desktop'))
from updates import _protect
HOME=Path.home()/'.camera-deck'/'signing'
CERT='405d66b0f4a5d85e19272a05985155a985dbe4d8bd25fee8718720ec631ac5d9'
def private_key():
    return serialization.load_pem_private_key(_protect((HOME/'client-update-key.dpapi').read_bytes(),True),password=None)
def apk_identity():
    return _protect((HOME/'client-apk-store.dpapi').read_bytes(),True),_protect((HOME/'client-apk-password.dpapi').read_bytes(),True).decode(),'androiddebugkey'
def initialize(expected):
    if expected!=CERT:raise RuntimeError('Installed certificate differs from adopted client identity')
    store=Path.home()/'.android/debug.keystore'
    if not store.is_file():raise RuntimeError('Restore the existing APK keystore; do not create a new one')
    public=ROOT/'desktop/client-update-public.pem'
    if public.exists() and not (HOME/'client-update-key.dpapi').is_file():raise RuntimeError('Restore client update key; identity replacement is forbidden')
    env=dict(os.environ,CLIENT_APK_PASSWORD='android')
    keytool=Path(os.environ['JAVA_HOME'])/'bin/keytool.exe'
    result=subprocess.run([str(keytool),'-list','-v','-alias','androiddebugkey','-keystore',str(store),'-storepass:env','CLIENT_APK_PASSWORD'],capture_output=True,text=True,encoding='utf-8',env=env,check=True)
    match=re.search(r'SHA256:\s*([0-9A-Fa-f:]+)',result.stdout)
    if not match or match.group(1).replace(':','').lower()!=expected:raise RuntimeError('Local APK keystore does not match installed client certificate')
    HOME.mkdir(parents=True,exist_ok=True)
    owner=subprocess.check_output(['whoami'],text=True).strip()
    subprocess.run(['icacls',str(HOME),'/inheritance:r','/grant:r',owner+':(OI)(CI)F','SYSTEM:(OI)(CI)F'],stdout=subprocess.DEVNULL,check=True)
    if (HOME/'client-apk-store.dpapi').exists():
        if apk_identity()[0]!=store.read_bytes():raise RuntimeError('Existing protected APK store differs; refusing replacement')
    else:
        (HOME/'client-apk-store.dpapi').write_bytes(_protect(store.read_bytes()))
        (HOME/'client-apk-password.dpapi').write_bytes(_protect(env['CLIENT_APK_PASSWORD'].encode()))
    if not (HOME/'client-update-key.dpapi').exists():
        key=rsa.generate_private_key(public_exponent=65537,key_size=3072)
        (HOME/'client-update-key.dpapi').write_bytes(_protect(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption())))
    key=private_key();pem=key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo)
    if public.exists() and public.read_bytes()!=pem:raise RuntimeError('Client trust root mismatch')
    for path in [public,ROOT/'android/app/src/main/assets/client-update-public.pem']:path.write_bytes(pem)
    (ROOT/'client-identity.json').write_text(json.dumps({'package':'io.github.prostoodin1.cameradeck.aurora','apkCertificateSha256':expected,'updatePublicKeySha256':hashlib.sha256(pem).hexdigest(),'product':'camera-deck-client'},indent=2)+'\n')
    print('Protected existing APK identity; client update public key established. No private keys written to source.')
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--adopt-installed-certificate',required=True);args=parser.parse_args();initialize(args.adopt_installed_certificate)
