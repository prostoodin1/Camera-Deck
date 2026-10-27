"""Publish a single signed client channel. Secret key stays in protected storage/CI."""
import base64,hashlib,json,os,re,subprocess,sys,urllib.request,urllib.error
from pathlib import Path
from cryptography.hazmat.primitives import serialization,hashes
from cryptography.hazmat.primitives.asymmetric import padding
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'desktop'))
import updates
def digest(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
def manifest(folder):
    version=json.loads((ROOT/'version.json').read_text());identity=json.loads((ROOT/'client-identity.json').read_text())
    sdk=Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0';apk=folder/'CameraDeck-Android.apk'
    signer=sdk/('apksigner.bat' if os.name=='nt' else 'apksigner')
    result=subprocess.run([str(signer),'verify','--print-certs',str(apk)],capture_output=True,text=True,check=True)
    match=re.search(r'SHA-256 digest:\s*([0-9a-fA-F]+)',result.stdout)
    if not match or match.group(1).lower()!=identity['apkCertificateSha256']:raise RuntimeError('APK identity changed; release blocked')
    aapt=sdk/('aapt.exe' if os.name=='nt' else 'aapt')
    badging=subprocess.check_output([str(aapt),'dump','badging',str(apk)],text=True)
    if "name='"+identity['package']+"'" not in badging or "versionCode='"+str(version['code'])+"'" not in badging:raise RuntimeError('APK package/version does not match client channel')
    if os.environ.get('CAMERA_DECK_CLIENT_UPDATE_PRIVATE_PEM'):key=serialization.load_pem_private_key(os.environ['CAMERA_DECK_CLIENT_UPDATE_PRIVATE_PEM'].encode(),None)
    else:
        import client_identity
        key=client_identity.private_key()
    public=key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo)
    if public!=(ROOT/'desktop/client-update-public.pem').read_bytes():raise RuntimeError('Release signing trust root mismatch')
    data={'product':'camera-deck-client','version':version['version'],'code':version['code'],'protocol':1,'channel':'client','sourceCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),'apkCertificateSha256':identity['apkCertificateSha256']}
    for platform,name in [('windows','CameraDeck-Setup.exe'),('android','CameraDeck-Android.apk')]:
        path=folder/name;data[platform]={'name':name,'size':path.stat().st_size,'sha256':digest(path)}
    payload=json.dumps(data,sort_keys=True,separators=(',',':')).encode();signature=key.sign(payload,padding.PKCS1v15(),hashes.SHA256())
    envelope=json.dumps({'payload':base64.b64encode(payload).decode(),'signature':base64.b64encode(signature).decode()}).encode()
    updates.verify_envelope(envelope,current_code=0)
    (folder/'camera-deck-update.json').write_bytes(envelope)
    return data
def main():
    folder=Path(sys.argv[1]).resolve();version=json.loads((ROOT/'version.json').read_text());tag='camera-deck-client-v'+version['version']
    if subprocess.check_output(['git','status','--porcelain','--untracked-files=no'],cwd=ROOT,text=True).strip():raise RuntimeError('Commit validated sources before release')
    if os.environ.get('GITHUB_REF_TYPE')=='tag' and os.environ.get('GITHUB_REF_NAME')!=tag:raise RuntimeError('Tag/version mismatch')
    from release_scan import validate_scan
    validate_scan(folder,subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip())
    data=manifest(folder);subprocess.run(['git','archive','--format=zip','--output',str(folder/'CameraDeck-Source.zip'),'HEAD'],cwd=ROOT,check=True)
    (folder/'SHA256SUMS.txt').write_text(''.join(digest(p)+'  '+p.name+'\n' for p in sorted(folder.iterdir()) if p.is_file() and p.name!='SHA256SUMS.txt'))
    token=os.environ.get('GH_TOKEN') or updates.github_token();base=updates.API
    def api(path,method='GET',body=None):
        req=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),method=method,headers={'Authorization':'Bearer '+token,'Accept':'application/vnd.github+json','Content-Type':'application/json','User-Agent':'CameraDeck-Client-Release'})
        with urllib.request.urlopen(req,timeout=60) as response:return json.load(response)
    try:release=api('/releases/tags/'+tag)
    except urllib.error.HTTPError as failure:
        if failure.code!=404:raise
        notes=(folder/'RELEASE-NOTES.md').read_text(encoding='utf-8')
        release=api('/releases','POST',{'tag_name':tag,'target_commitish':data['sourceCommit'],'name':'Camera Deck '+version['version']+' · единый клиент','body':notes,'draft':True,'prerelease':True,'make_latest':'false'})
    if not release['draft']:raise RuntimeError('Existing published release will not be overwritten')
    upload=release['upload_url'].split('{')[0]
    if not upload.startswith('https://uploads.github.com/'):raise RuntimeError('Unexpected upload endpoint')
    existing={a['name']:a for a in release['assets']}
    from urllib.parse import quote
    for path in sorted(folder.iterdir()):
        if not path.is_file():continue
        if path.name in existing:asset=existing[path.name]
        else:
            print('Uploading '+path.name,flush=True)
            with path.open('rb') as stream:
                req=urllib.request.Request(upload+'?name='+quote(path.name),data=stream,method='POST',headers={'Authorization':'Bearer '+token,'Content-Length':str(path.stat().st_size),'Content-Type':'application/octet-stream','User-Agent':'CameraDeck-Client-Release'})
                with urllib.request.urlopen(req,timeout=180) as response:asset=json.load(response)
        if asset.get('digest')!='sha256:'+digest(path) or asset['size']!=path.stat().st_size:raise RuntimeError('Uploaded asset mismatch; draft retained')
    if '--draft-only' in sys.argv:
        print('Validated draft staged; local scan of the exact downloaded asset required before publication.')
        return
    release=api('/releases/'+str(release['id']),'PATCH',{'draft':False,'prerelease':True,'make_latest':'false'})
    print(release['html_url'])
if __name__=='__main__':main()
