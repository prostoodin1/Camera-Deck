"""Reject publication when the scan is absent, stale or for a different EXE."""
import datetime,hashlib,json
from pathlib import Path

def validate_scan(folder,commit):
    folder=Path(folder)
    data=json.loads((folder/'WINDOWS-DEFENDER-SCAN.json').read_text(encoding='utf-8-sig'))
    if data.get('schema')!=1 or data.get('scanner')!='Microsoft Defender' or data.get('result')!='clean' or data.get('exitCode')!=0:
        raise ValueError('Successful Defender scan required before publication')
    if data.get('sourceCommit')!=commit:raise ValueError('Scan source commit mismatch')
    with (folder/'CameraDeck-Setup.exe').open('rb') as stream:actual=hashlib.file_digest(stream,'sha256').hexdigest()
    if data.get('sha256')!=actual:raise ValueError('Scan executable hash mismatch')
    now=datetime.datetime.now(datetime.timezone.utc)
    for key,max_age in [('completedAt',24),('signatureUpdatedAt',48)]:
        stamp=datetime.datetime.fromisoformat(data[key].replace('Z','+00:00'))
        if stamp.tzinfo is None or not datetime.timedelta(0)<=now-stamp<=datetime.timedelta(hours=max_age):
            raise ValueError('Missing, stale or future scan timestamp: '+key)
    if not data.get('signatureVersion') or not data.get('engineVersion'):raise ValueError('Missing scanner versions')
    return data
