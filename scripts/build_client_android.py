"""Build the permanent non-debuggable client using its existing APK identity."""
import base64,os,subprocess,tempfile
from pathlib import Path
import client_identity
ROOT=Path(__file__).resolve().parents[1]
def main():
    env=dict(os.environ)
    if env.get('CAMERA_DECK_CLIENT_APK_KEYSTORE_B64'):
        store=base64.b64decode(env['CAMERA_DECK_CLIENT_APK_KEYSTORE_B64'],validate=True);password=env['CAMERA_DECK_CLIENT_APK_PASSWORD'];alias=env['CAMERA_DECK_CLIENT_APK_ALIAS']
        parent=Path(env['RUNNER_TEMP'])
    else:store,password,alias=client_identity.apk_identity();parent=client_identity.HOME
    with tempfile.TemporaryDirectory(prefix='client-build-',dir=parent) as folder:
        path=Path(folder).resolve()
        if path.parent!=parent.resolve():raise RuntimeError('Unexpected private build directory')
        keystore=path/'client.jks';keystore.write_bytes(store)
        env.update(CAMERA_DECK_KEYSTORE=str(keystore),CAMERA_DECK_STORE_PASSWORD=password,CAMERA_DECK_KEY_ALIAS=alias)
        gradle=env.get('CAMERA_DECK_GRADLE','gradle')
        subprocess.run([gradle,'-p',str(ROOT/'android'),'--no-daemon','-PcameraDeckClient=true',':app:assembleRelease',':app:testReleaseUnitTest',':app:lintRelease'],cwd=ROOT,env=env,check=True)
    print('Permanent Android client built with unchanged signing certificate.')
if __name__=='__main__':main()
