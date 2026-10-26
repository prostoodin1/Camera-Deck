"""Reproducible UI screenshots against two loopback TLS camera simulators.

This is a functional desktop test stand, not evidence from Android hardware.
No production certificate checks or address restrictions are disabled in the app.
"""
from __future__ import annotations
import datetime
import hashlib
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import json
from pathlib import Path
import os
import ssl
import sys
import tempfile
import threading
import time
from urllib.parse import urlparse,parse_qs
from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID
os.environ["QT_QPA_PLATFORM"]="offscreen"
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/"desktop"))
from PyQt6.QtCore import QBuffer,QByteArray,QIODevice,QTimer,Qt
from PyQt6.QtGui import QImage,QPainter,QColor,QFont,QFontDatabase
from PyQt6.QtWidgets import QApplication,QDialog,QComboBox,QTabWidget
from main import Window,CameraTile
from protocol import CameraClient
from updates import VERSION,VERSION_CODE
import updates


def fixture(index):
    image=QImage(960,540,QImage.Format.Format_RGB32);image.fill(QColor("#101c2d"))
    painter=QPainter(image);painter.setRenderHint(QPainter.RenderHint.Antialiasing)
    if index==0:
        painter.fillRect(0,0,960,310,QColor("#29475d"));painter.fillRect(0,310,960,230,QColor("#796854"))
        painter.setPen(QColor("#b7c9cc"))
        for x in range(0,961,80):painter.drawLine(x,310,480+(x-480)*2,540)
        painter.fillRect(560,110,240,190,QColor("#9abfd0"));painter.fillRect(677,110,7,190,QColor("#26384d"));painter.fillRect(560,200,240,7,QColor("#26384d"))
        painter.fillRect(120,255,190,150,QColor("#365c56"));painter.fillRect(155,220,120,110,QColor("#d3a061"))
        painter.setBrush(QColor("#61b9a8"));painter.setPen(Qt.PenStyle.NoPen);painter.drawEllipse(370,220,130,180)
    else:
        colors=["#dedede","#e4cc52","#5dd3d1","#5ab47a","#bb70c8","#c66765","#5474c4"]
        for i,color in enumerate(colors):painter.fillRect(i*138,0,139,370,QColor(color))
        for i in range(12):painter.fillRect(i*80,370,80,170,QColor(i*22,i*22,i*22))
    painter.fillRect(0,0,960,65,QColor("#09111e"));painter.setPen(QColor("#ffffff"));painter.setFont(QFont("Segoe UI",19,QFont.Weight.Bold))
    painter.drawText(25,40,"СИМУЛЯТОР · НЕ РЕАЛЬНАЯ СЪЁМКА")
    painter.setFont(QFont("Segoe UI",15));painter.fillRect(0,475,960,65,QColor("#09111e"))
    painter.drawText(25,515,"Тестовый источник A · интерьер" if index==0 else "Тестовый источник B · цветовая таблица")
    painter.end();data=QByteArray();buffer=QBuffer(data);buffer.open(QIODevice.OpenModeFlag.WriteOnly);image.save(buffer,"JPEG",88)
    return bytes(data)


class State:
    def __init__(self,index,frame):
        self.index=index;self.frame=frame;self.mode="photo";self.recording=False;self.last="";self.files={};self.versionCode=VERSION_CODE
        self.width=1920;self.height=1080;self.fps=30;self.photoFormat="jpg"
    def status(self):
        return {"ready":True,"recording":self.recording,"error":"ТЕСТОВЫЙ СТЕНД · Android не подключён","cameraId":"0","rotation":0,"frameTime":int(time.time()*1000),
            "frameSequence":int(time.time()*10),"iso":100 if self.index==0 else 200,"exposureNs":10000000,"manualIso":0,"manualExposureNs":0,"focus":-1,
            "lastFile":self.last,"audio":True,"audioAvailable":False,"name":"Симулятор A" if self.index==0 else "Симулятор B","version":VERSION,"versionCode":self.versionCode,
            "mode":self.mode,"videoWidth":self.width,"videoHeight":self.height,"videoFps":self.fps,"photoWidth":4000,"photoHeight":3000,"photoFormat":self.photoFormat,
            "powerState":"record_preview" if self.recording else "preview","eco":False,"previewFps":24,"manualSensor":True,"isoMin":50,"isoMax":6400,"exposureMinNs":100000,"exposureMaxNs":1000000000,"focusMax":10,
            "cameras":[{"id":"0","facing":1},{"id":"1","facing":0}],"captureCapabilities":{"videoModes":[{"width":1280,"height":720,"fps":[30,60]},{"width":1920,"height":1080,"fps":[30,60]},{"width":3840,"height":2160,"fps":[30]}],
                "photoSizes":[{"width":4000,"height":3000},{"width":1920,"height":1080}],"rawSizes":[],"photoFormats":["jpg","png","webp"]}}


def handler(state):
    class Handler(BaseHTTPRequestHandler):
        protocol_version="HTTP/1.1"
        def log_message(self,*args):pass
        def send(self,body,content_type="application/json",status=200):
            self.send_response(status);self.send_header("Content-Type",content_type);self.send_header("Content-Length",str(len(body)));self.end_headers();self.wfile.write(body)
        def document(self,data,status=200):self.send(json.dumps(data).encode(),status=status)
        def authorized(self):return self.headers.get("Authorization")=="Bearer demo-session"
        def handle(self):
            try:super().handle()
            except (BrokenPipeError,ConnectionResetError,ConnectionAbortedError,ssl.SSLError):pass
        def do_GET(self):
            if not self.authorized():self.document({"error":"Pair first"},401);return
            path=urlparse(self.path)
            if path.path=="/status":self.document(state.status())
            elif path.path=="/frame":self.send(state.frame,"image/jpeg")
            elif path.path=="/stream":
                self.send_response(200);self.send_header("Content-Type","multipart/x-mixed-replace; boundary=aurora");self.end_headers()
                deadline=time.monotonic()+8
                while time.monotonic()<deadline:
                    self.wfile.write(f"--aurora\r\nContent-Length: {len(state.frame)}\r\nX-Rotation: 0\r\n\r\n".encode()+state.frame+b"\r\n");self.wfile.flush();time.sleep(1/24)
                self.close_connection=True
            elif path.path=="/media":self.document({"files":[{"name":name,"size":len(data)} for name,data in state.files.items()]})
            elif path.path=="/file":
                name=parse_qs(path.query).get("name",[""])[0]
                if name in state.files:self.send(state.files[name],"image/jpeg")
                else:self.document({"error":"Unknown fixture"},404)
            else:self.document({"error":"Simulator endpoint unavailable"},404)
        def do_POST(self):
            body=json.loads(self.rfile.read(int(self.headers.get("Content-Length",0))))
            if self.path=="/pair":
                if body.get("pin")=="1234":self.document({"token":"demo-session","name":"Симулятор A" if state.index==0 else "Симулятор B"})
                else:self.document({"error":"Wrong simulator PIN"},403)
                return
            if not self.authorized():self.document({"error":"Pair first"},401);return
            action=body.get("action")
            if action=="capture_config":
                state.mode=body.get("mode",state.mode);state.width=body.get("videoWidth",state.width);state.height=body.get("videoHeight",state.height);state.fps=body.get("videoFps",state.fps)
            elif action=="record_start":state.recording=True # Control-state simulation only: no fake MP4 is created.
            elif action=="record_stop":state.recording=False
            elif action=="photo":
                state.last=f"CD_{int(time.time()*1000)}_deadbeef.jpg";state.files[state.last]=state.frame
            elif action!="settings":self.document({"error":"Unknown simulator action"},400);return
            self.document({"accepted":True,"action":action})
    return Handler


def main():
    demo_data=tempfile.TemporaryDirectory(prefix='camera-deck-demo-settings-')
    updates.DATA=Path(demo_data.name)
    app=QApplication([])
    font=Path(os.environ.get("WINDIR","C:/Windows"))/"Fonts/segoeui.ttf"
    if font.is_file():QFontDatabase.addApplicationFont(str(font))
    app.setFont(QFont("Segoe UI",10))
    out=ROOT/"docs/screenshots";out.mkdir(parents=True,exist_ok=True)
    key=ec.generate_private_key(ec.SECP256R1());name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,"CameraDeck loopback simulator")]);now=datetime.datetime.now(datetime.timezone.utc)
    cert=x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-datetime.timedelta(minutes=1)).not_valid_after(now+datetime.timedelta(days=1)).sign(key,hashes.SHA256())
    fingerprint=hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest()
    servers=[];window=Window(auto_checks=False);window.resize(1440,960)
    window.setWindowTitle("Camera Deck · функциональный тестовый стенд · НЕ Android-камеры")
    window.hint.setText("ТЕСТОВЫЙ СТЕНД: два симулятора по TLS. Телефоны не подключены; состояние записи имитируется.")
    window.update_label.setText("ТЕСТОВЫЙ СТЕНД · v"+VERSION)
    states=[State(i,fixture(i)) for i in range(2)]
    with tempfile.TemporaryDirectory(prefix="camera-deck-simulator-") as temp:
        certfile=Path(temp)/"cert.pem";keyfile=Path(temp)/"key.pem"
        certfile.write_bytes(cert.public_bytes(serialization.Encoding.PEM));keyfile.write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
        for i,state in enumerate(states):
            host=f"127.0.0.{i+1}"
            server=ThreadingHTTPServer((host,39812),handler(state));server.daemon_threads=True
            context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.load_cert_chain(certfile,keyfile);server.socket=context.wrap_socket(server.socket,server_side=True)
            threading.Thread(target=server.serve_forever,daemon=True).start();servers.append(server)
            # Only this test fixture uses loopback; the actual client transport still pins TLS.
            client=CameraClient("192.168.0.1",fingerprint);client.host=host;client.pair("1234")
            tile=CameraTile(window,client,"Симулятор A · интерьер" if i==0 else "Симулятор B · таблица");window.tiles.append(tile)
            window.devices.append({"name":tile.name.text(),"host":host,"fingerprint":fingerprint})
        window.rebuild();window.show()
        def screenshot(name):app.processEvents();assert window.grab().save(str(out/name))
        def capture_photo():
            window.tiles[0].client.command("photo")
            screenshot("01-multicamera-test-stand.png")
            filename=states[0].last
            folder=ROOT/"release/verification/simulator-captures";folder.mkdir(parents=True,exist_ok=True)
            window.tiles[0].client.download(filename,folder)
            window.tiles[1].client.command("capture_config",mode="video",videoWidth=3840,videoHeight=2160,videoFps=30)
            window.tiles[1].client.command("record_start")
        def capture_recording():
            screenshot("02-video-control-simulator.png")
            original=QDialog.exec
            def capture_dialog(dialog):
                dialog.show();app.processEvents();assert dialog.grab().save(str(out/"03-capture-settings.png"))
                tabs=dialog.findChild(QTabWidget);tabs.setCurrentIndex(1);app.processEvents();assert dialog.grab().save(str(out/"04-exposure-settings.png"))
                return QDialog.DialogCode.Rejected
            QDialog.exec=capture_dialog
            try:window.tiles[0].settings()
            finally:QDialog.exec=original
            tile=window.tiles[0];tile.open_fullscreen();app.processEvents();assert tile.fullscreen.isFullScreen();assert not window.isFullScreen()
            assert tile.fullscreen.grab().save(str(out/"05-fullscreen-monitor.png"));tile.fullscreen.reject();app.processEvents()
            def capture_popup(dialog):
                dialog.show();app.processEvents();assert dialog.grab().save(str(out/"06-iso-popover.png"));return QDialog.DialogCode.Rejected
            QDialog.exec=capture_popup
            try:tile.quick_sensor("iso",tile.iso_readout)
            finally:QDialog.exec=original
            states[1].recording=False
            for tile in window.tiles:tile.status["recording"]=False;tile.stopped.set();tile.monitor.stop()
            window.close();app.quit()
        QTimer.singleShot(2000,capture_photo);QTimer.singleShot(3500,capture_recording)
        result=app.exec()
        for server in servers:server.shutdown();server.server_close()
    print(json.dumps({"screenshots":[str(p) for p in sorted(out.glob("*.png"))],"realPhonesTested":False,"tlsPairingAndJpegTransferTested":True},ensure_ascii=False))
    demo_data.cleanup()
    return result


if __name__=="__main__":raise SystemExit(main())
