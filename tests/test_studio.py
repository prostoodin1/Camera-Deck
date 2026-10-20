import os
os.environ['QT_QPA_PLATFORM']='offscreen'
from pathlib import Path
import sys
import threading
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'desktop'))
from PyQt6.QtWidgets import QApplication
from main import Window,CameraTile
import updates


def make_window(tmp_path,monkeypatch):
    monkeypatch.setattr(updates,'DATA',tmp_path)
    monkeypatch.setattr(threading.Thread,'start',lambda self:None)
    app=QApplication.instance() or QApplication([])
    window=Window(auto_checks=False);window.auto.setChecked(False)
    return app,window


def test_workspaces_preserve_selection_and_show_confirmed_state(tmp_path,monkeypatch):
    app,window=make_window(tmp_path,monkeypatch)
    class Client:host='192.168.1.2';fingerprint='a'*64
    tile=CameraTile(window,Client(),'Phone');window.tiles.append(tile)
    tile.show_status(dict(ready=True,recording=False,mode='video',originalRecording=True,iso=200,exposureNs=10000000,storageFreeBytes=10_000_000_000))
    window.show();window.rebuild();window.studio.select(tile);window.studio.navigate(1);app.processEvents()
    assert window.studio.inspector.isVisible() and window.studio.selected is tile
    assert window.studio.record_pc.isEnabled()
    assert '200' in window.studio.readouts['iso'].text()
    assert all(not control.isVisible() for control in tile.details)
    window.studio.navigate(2);app.processEvents()
    assert window.studio.table.rowCount()==1 and not tile.preview_enabled.is_set()
    assert window.studio.table.item(0,2).text()=='Готово'
    window.studio.navigate(0);app.processEvents();assert tile.preview_enabled.is_set()
    assert window.studio.selected is tile
    window.close()


def test_media_keeps_metadata_if_thumbnail_is_unavailable(tmp_path,monkeypatch):
    app,window=make_window(tmp_path,monkeypatch)
    name='CD_123_1234abcd.mp4'
    class Client:
        host='192.168.1.2';fingerprint='a'*64
        def json(self,path):
            if path=='/media':return {'files':[{'name':name,'size':100,'complete':True},{'name':name+'.partial','size':10,'complete':False}]}
            if name+'.partial' in path:raise FileNotFoundError('incomplete')
            return {'width':1280,'height':720,'durationMs':2000}
        def request(self,*a,**kw):raise FileNotFoundError('thumbnail unsupported')
    tile=CameraTile(window,Client(),'Phone');window.tiles.append(tile);tile.status={'ready':True,'recording':False}
    window.run=lambda fn,callback=lambda _:None,failure=None:callback(fn())
    window.studio.select(tile);window.studio.navigate(3)
    browser=window.studio.media;assert browser.list.count()==2
    assert '1280 × 720' in browser.items[name].text()
    browser.list.setCurrentItem(browser.items[name+'.partial']);assert not browser.download.isEnabled()
    browser.list.setCurrentItem(browser.items[name]);assert browser.download.isEnabled()
    window.close()
