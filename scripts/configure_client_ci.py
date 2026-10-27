"""Store existing client signing material in GitHub Actions encrypted secrets.

Nothing secret is printed, committed, or written to the workspace.
"""
import base64,json,sys,urllib.request
from pathlib import Path
from nacl.public import PublicKey,SealedBox
from cryptography.hazmat.primitives import serialization
import client_identity
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'desktop'))
import updates
def main():
    token=updates.github_token();base='https://api.github.com/repos/prostoodin1/Camera-Deck'
    def api(path,method='GET',data=None):
        request=urllib.request.Request(base+path,data=None if data is None else json.dumps(data).encode(),method=method,headers={'Authorization':'Bearer '+token,'Accept':'application/vnd.github+json','Content-Type':'application/json','User-Agent':'CameraDeck-Client-Signing'})
        with urllib.request.urlopen(request,timeout=30) as response:return json.load(response) if response.status!=204 else None
    key=api('/actions/secrets/public-key');box=SealedBox(PublicKey(base64.b64decode(key['key'])))
    store,password,alias=client_identity.apk_identity();private=client_identity.private_key().private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()).decode()
    secrets={'CAMERA_DECK_CLIENT_APK_KEYSTORE_B64':base64.b64encode(store).decode(),'CAMERA_DECK_CLIENT_APK_PASSWORD':password,'CAMERA_DECK_CLIENT_APK_ALIAS':alias,'CAMERA_DECK_CLIENT_UPDATE_PRIVATE_PEM':private}
    for name,value in secrets.items():
        ciphertext=base64.b64encode(box.encrypt(value.encode())).decode()
        api('/actions/secrets/'+name,'PUT',{'encrypted_value':ciphertext,'key_id':key['key_id']});print('Configured encrypted release secret: '+name,flush=True)
    print('Client identity backed by owner-protected local files and GitHub Actions encrypted secrets.')
if __name__=='__main__':main()
