import sys,os
from pathlib import Path
import pytest
os.environ['QT_QPA_PLATFORM']='offscreen'
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'desktop'))
from rotation import Rotation,load,save


def test_locked_orientation_ignores_sensor_changes_but_allows_manual_turns():
    r=Rotation();assert r.angle(180)==180;r.lock(True)
    assert r.angle(0)==180
    r.turn();assert r.angle(270)==270
    r.lock(False);assert r.angle()==0


def test_manual_offset_follows_auto_without_accumulating_frame_rotations():
    r=Rotation();r.turn();r.turn()
    assert [r.angle(a) for a in (0,90,180,270,0)]==[180,270,0,90,180]
    r.turn();r.turn();assert r.angle(0)==0


def test_orientation_is_saved_by_phone_identity(tmp_path,monkeypatch):
    import updates
    monkeypatch.setattr(updates,'DATA',tmp_path)
    r=Rotation();r.angle(270);r.turn();r.lock(True);save('phone-a',r)
    restored=load('phone-a');assert restored.angle(90)==0 and restored.locked_base==270
    assert load('phone-b').angle(90)==90


def test_viewer_rotates_original_pixels_and_fullscreen_uses_same_lock(tmp_path,monkeypatch):
    import updates,threading
    from PyQt6.QtWidgets import QApplication
    from PyQt6.QtGui import QImage,QColor
    from main import Window,CameraTile
    monkeypatch.setattr(updates,'DATA',tmp_path);monkeypatch.setattr(threading.Thread,'start',lambda self:None)
    app=QApplication.instance() or QApplication([]);window=Window(auto_checks=False)
    class Client:host='192.168.1.2';fingerprint='a'*64
    tile=CameraTile(window,Client(),'Camera');tile.status={'recording':False,'ready':True};window.tiles.append(tile)
    raw=QImage(3,2,QImage.Format.Format_RGB32);raw.fill(QColor('black'));raw.setPixelColor(0,0,QColor('red'));raw.setPixelColor(2,1,QColor('blue'))
    def frame(angle):tile.pending_frame=(raw,angle);tile.display_latest()
    frame(0);tile.rotate_picture();tile.rotate_picture()
    assert tile.video.frame.pixelColor(2,1)==QColor('red')
    tile.rotation_lock.setChecked(True);frame(90)
    assert tile.video.frame_rotation==180 and tile.video.frame.size()==raw.size()
    tile.open_fullscreen();app.processEvents()
    assert tile.full_view.frame_rotation==180 and tile.full_rotation_lock.isChecked()
    tile.rotate_picture();assert tile.video.frame_rotation==270 and tile.full_view.frame_rotation==270
    tile.full_rotation_lock.setChecked(False);assert not tile.rotation_lock.isChecked()
    frame(0);assert tile.video.frame_rotation==270
    tile.fullscreen.reject();tile.stopped.set();window.close()
