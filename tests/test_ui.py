import os
os.environ["QT_QPA_PLATFORM"]="offscreen"
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"desktop"))
from PyQt6.QtWidgets import QApplication
from main import Window, CameraTile


def test_window_renders_without_hardware(tmp_path,monkeypatch):
    import updates
    monkeypatch.setattr(updates,"DATA",tmp_path)
    app=QApplication.instance() or QApplication([])
    window=Window(auto_checks=False)
    window.auto.setChecked(False)
    window.show()
    app.processEvents()
    assert window.empty.isVisible()
    assert window.device_list.count()==0
    assert not window.install_button.isEnabled()
    assert not window.grab().isNull()
    window.close()


def test_pairing_requires_full_fingerprint_confirmation_and_numeric_pin(tmp_path,monkeypatch):
    import main, updates
    from PyQt6.QtWidgets import QDialog,QCheckBox,QLineEdit,QDialogButtonBox
    monkeypatch.setattr(updates,"DATA",tmp_path)
    app=QApplication.instance() or QApplication([])
    window=Window(auto_checks=False)
    window.run=lambda fn,callback:callback(fn())
    monkeypatch.setattr(main.CameraClient,"probe",lambda self:"a"*64)
    def inspect(dialog):
        checked=dialog.findChild(QCheckBox)
        pin=dialog.findChild(QLineEdit)
        ok=dialog.findChild(QDialogButtonBox).button(QDialogButtonBox.StandardButton.Ok)
        pin.setText("1234");assert not ok.isEnabled()
        checked.setChecked(True);assert ok.isEnabled()
        pin.setText("abcd");assert not ok.isEnabled()
        pin.setText("１２３４");assert not ok.isEnabled()
        pin.setText("123");assert not ok.isEnabled()
        return QDialog.DialogCode.Rejected
    monkeypatch.setattr(QDialog,"exec",inspect)
    window.begin_connect("192.168.1.2","Android phone","a"*64)
    assert not window.tiles
    window.close()


def test_fullscreen_is_per_camera_and_frame_queue_keeps_only_latest(tmp_path,monkeypatch):
    import updates,threading
    from PyQt6.QtGui import QImage,QColor
    monkeypatch.setattr(updates,"DATA",tmp_path)
    monkeypatch.setattr(threading.Thread,"start",lambda self:None)
    app=QApplication.instance() or QApplication([])
    window=Window(auto_checks=False)
    class Client:host="192.168.1.2"
    tile=CameraTile(window,Client(),"Camera");tile.status={"recording":False,"ready":True}
    window.tiles.append(tile);window.show();window.rebuild();app.processEvents()
    assert not window.isFullScreen()
    for i in range(50):
        image=QImage(16,16,QImage.Format.Format_RGB32);image.fill(QColor(i,0,0))
        with tile.frame_lock:tile.pending_frame=(image,0)
    tile.display_latest()
    assert tile.video.frame.pixelColor(0,0).red()==49 and tile.pending_frame is None
    tile.open_fullscreen();app.processEvents()
    assert tile.fullscreen.isFullScreen() and not window.isFullScreen()
    tile.fullscreen.reject();app.processEvents();assert tile.fullscreen is None
    window.close()


def test_discovery_cards_connect_to_the_selected_phone(tmp_path,monkeypatch):
    import updates
    from PyQt6.QtWidgets import QPushButton
    monkeypatch.setattr(updates,"DATA",tmp_path)
    app=QApplication.instance() or QApplication([])
    window=Window(auto_checks=False);seen=[]
    window.devices=[{"name":"Phone A","host":"192.168.1.2","fingerprint":"a"*64},{"name":"Phone B","host":"192.168.1.3","fingerprint":"b"*64}]
    window.begin_connect=lambda *args:seen.append(args)
    window.refresh_devices();buttons=window.device_cards.findChildren(QPushButton)
    buttons[-1].click()
    assert seen==[("192.168.1.3","Phone B","b"*64)]
    window.close()


def test_iso_popup_keeps_exposure_paired_with_manual_iso(tmp_path,monkeypatch):
    import updates,threading
    from PyQt6.QtWidgets import QDialog
    from appearance import Toggle,SliderField
    monkeypatch.setattr(updates,"DATA",tmp_path)
    monkeypatch.setattr(threading.Thread,"start",lambda self:None)
    app=QApplication.instance() or QApplication([])
    window=Window(auto_checks=False);commands=[]
    class Client:
        host="192.168.1.2"
        def command(self,action,**values):commands.append((action,values))
    tile=CameraTile(window,Client(),"Phone");tile.status={"manualSensor":True,"isoMin":50,"isoMax":3200,"iso":100,"exposureNs":10000000,"focus":-1}
    window.run=lambda fn,callback:callback(fn())
    def choose(dialog):
        dialog.findChild(Toggle).setChecked(False)
        dialog.findChild(SliderField).slider.setValue(10000)
        return QDialog.DialogCode.Accepted
    monkeypatch.setattr(QDialog,"exec",choose)
    tile.quick_sensor("iso",tile.iso_readout)
    assert commands==[("settings",{"iso":3200,"exposureNs":10000000,"focus":-1})]
    tile.stopped.set();tile.monitor.stop();window.close()


def test_capture_settings_keep_fps_and_raw_sizes_in_supported_mode(tmp_path,monkeypatch):
    import updates,threading
    from PyQt6.QtWidgets import QComboBox,QDialog
    monkeypatch.setattr(updates,"DATA",tmp_path)
    monkeypatch.setattr(threading.Thread,"start",lambda self:None)
    app=QApplication.instance() or QApplication([])
    window=Window(auto_checks=False)
    commands=[]
    class Client:
        host="192.168.1.2"
        def command(self,action,**args):commands.append((action,args))
    tile=CameraTile(window,Client(),"Pixel")
    tile.status={"mode":"photo","ready":True,"recording":False,"cameraId":"0","cameras":[{"id":"0","facing":1}],
        "manualSensor":True,"isoMin":50,"isoMax":3200,"iso":100,"exposureNs":10000000,"exposureMinNs":1000,"exposureMaxNs":100000000,
        "focus":-1,"focusMax":10,"videoWidth":1920,"videoHeight":1080,"videoFps":60,"photoFormat":"jpg","photoWidth":4000,"photoHeight":3000,
        "captureCapabilities":{"videoModes":[{"width":1920,"height":1080,"fps":[30,60]},{"width":3840,"height":2160,"fps":[30]}],
            "photoFormats":["jpg","png","webp","dng"],"photoSizes":[{"width":4000,"height":3000}],"rawSizes":[{"width":4032,"height":3024}]}}
    def choose(dialog):
        combos=dialog.findChildren(QComboBox)
        sizes=next(c for c in combos if isinstance(c.itemData(0),dict) and "fps" in c.itemData(0))
        for i in range(sizes.count()):
            if sizes.itemData(i)["width"]==3840:sizes.setCurrentIndex(i)
        rates=next(c for c in combos if isinstance(c.itemData(0),int))
        assert rates.count()==1 and rates.currentData()==30
        formats=next(c for c in combos if c.findData("dng")>=0)
        formats.setCurrentIndex(formats.findData("dng"))
        raw_sizes=next(c for c in combos if isinstance(c.itemData(0),dict) and c.itemData(0).get("width")==4032)
        assert raw_sizes.currentData()["height"]==3024
        return QDialog.DialogCode.Accepted
    monkeypatch.setattr(QDialog,"exec",choose)
    window.run=lambda fn,callback:callback(fn())
    tile.settings()
    assert commands[0][0]=="settings"
    action,config=commands[1]
    assert action=="capture_config" and config["videoFps"]==30 and config["videoWidth"]==3840
    assert config["photoFormat"]=="dng" and config["photoWidth"]==4032
    tile.stopped.set();tile.monitor.stop();window.close()


def test_preview_pauses_for_hidden_group_and_minimized_window(tmp_path,monkeypatch):
    import updates
    import threading
    monkeypatch.setattr(updates,"DATA",tmp_path)
    monkeypatch.setattr(threading.Thread,"start",lambda self:None)
    app=QApplication.instance() or QApplication([])
    window=Window(auto_checks=False)
    class Client: host="192.168.1.2"
    tile=CameraTile(window,Client(),"Pixel")
    tile.status={"recording":False}
    window.tiles.append(tile)
    other=CameraTile(window,Client(),"Samsung")
    other.group.setText("Другая")
    other.status={"recording":False}
    window.tiles.append(other)
    window.show(); window.rebuild(); app.processEvents()
    assert tile.preview_enabled.is_set() and other.preview_enabled.is_set()
    window.filter.setCurrentText("Общая"); app.processEvents()
    assert tile.preview_enabled.is_set() and not other.preview_enabled.is_set()
    tile.view_toggle.setChecked(False)
    assert not tile.preview_enabled.is_set()
    tile.view_toggle.setChecked(True)
    window.showMinimized(); app.processEvents()
    assert not tile.preview_enabled.is_set() and not other.preview_enabled.is_set()
    window.showNormal(); app.processEvents()
    assert tile.preview_enabled.is_set()
    tile.show_status({"recording":True,"ready":True,"powerState":"record","previewFps":5})
    assert "Стоп" in tile.record.text()
    assert tile.status["recording"]  # Pausing preview never issues record_stop.
    tile.status["recording"]=False
    window.close()
