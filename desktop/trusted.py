"""Remembered pairing secrets are encrypted for the current Windows account."""
import json
import updates


def load():
    path=updates.DATA/'trusted.dpapi'
    if not path.exists():return {}
    return json.loads(updates._protect(path.read_bytes(),True))


def save(devices):
    updates.DATA.mkdir(parents=True,exist_ok=True)
    path=updates.DATA/'trusted.dpapi';temp=path.with_suffix('.new')
    temp.write_bytes(updates._protect(json.dumps(devices).encode()))
    temp.replace(path)


def remember(identity,host,fingerprint,name,secret):
    devices=load();devices[identity]=dict(host=host,fingerprint=fingerprint,name=name,secret=secret);save(devices)


def forget(identity):
    devices=load();devices.pop(identity,None);save(devices)


def match(device,devices):
    saved=devices.get(device.get('id'))
    return saved if saved and saved.get('fingerprint')==device.get('fingerprint') else None
