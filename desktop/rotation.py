"""Viewer orientation is independent of camera capture and recording."""
import json
import updates


class Rotation:
    def __init__(self,offset=0,locked_base=None):
        self.offset=offset if type(offset) is int and offset in (0,90,180,270) else 0
        self.locked_base=locked_base if type(locked_base) is int and locked_base in (0,90,180,270) else None
        self.source=0

    def angle(self,source=None):
        if source in (0,90,180,270):self.source=source
        return ((self.source if self.locked_base is None else self.locked_base)+self.offset)%360

    def turn(self):self.offset=(self.offset+90)%360

    def lock(self,value):self.locked_base=self.source if value else None

    def config(self):return dict(offset=self.offset,lockedBase=self.locked_base)


def load(identity):
    try:
        all_settings=json.loads((updates.DATA/'view-rotation.json').read_text(encoding='utf-8'))
        item=all_settings.get(identity,{})
        return Rotation(item.get('offset',0),item.get('lockedBase'))
    except (OSError,ValueError,TypeError,AttributeError):return Rotation()


def save(identity,rotation):
    path=updates.DATA/'view-rotation.json'
    try:all_settings=json.loads(path.read_text(encoding='utf-8'))
    except (OSError,ValueError):all_settings={}
    if not isinstance(all_settings,dict):all_settings={}
    all_settings[identity]=rotation.config();updates.DATA.mkdir(parents=True,exist_ok=True)
    temporary=path.with_suffix('.new');temporary.write_text(json.dumps(all_settings),encoding='utf-8');temporary.replace(path)
