from __future__ import annotations
import json
import os
from pathlib import Path
import sys
import threading
import time
import math
from PyQt6.QtCore import QObject, Qt, QTimer, pyqtSignal
from PyQt6.QtGui import QCloseEvent, QImage, QPixmap, QTransform, QFontDatabase, QFont
from PyQt6.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout, QGridLayout,
    QLabel, QPushButton, QLineEdit, QComboBox, QScrollArea, QInputDialog,
    QMessageBox, QDialog, QDialogButtonBox, QFormLayout, QSpinBox,
    QDoubleSpinBox, QCheckBox, QFileDialog, QTabWidget, QSlider, QFrame,
)
from protocol import CameraClient, discover, StreamUnavailable
from audio import AudioMonitor
from appearance import THEME,Viewfinder,shutter_label,GlassSheet,Toggle,SliderField
import updates


class Result(QObject):
    done = pyqtSignal(object)
    failed = pyqtSignal(str)


class Feed(QObject):
    image = pyqtSignal(bytes, object)
    state = pyqtSignal(object)
    failure = pyqtSignal(str)


class CameraTile(QWidget):
    def __init__(self, window, client, name):
        super().__init__()
        self.window, self.client = window, client
        self.stopped = threading.Event()
        self.paused = threading.Event()
        self.preview_enabled = threading.Event()
        self.preview_enabled.set()
        self.status = {}
        self.failures = 0
        self.fullscreen=None
        self.frame_lock=threading.Lock();self.pending_frame=None
        self.setAttribute(Qt.WidgetAttribute.WA_StyledBackground)
        self.setObjectName("tile")
        box = QVBoxLayout(self);box.setContentsMargins(10,8,10,8);box.setSpacing(7)
        heading = QHBoxLayout();heading.setSpacing(6)
        self.name = QLineEdit(name)
        self.name.setObjectName("cameraName")
        self.name.setToolTip("Название окна на этом компьютере")
        self.group = QLineEdit("Общая")
        self.group.setMaximumWidth(115)
        self.group.setPlaceholderText("Группа")
        self.group.editingFinished.connect(window.rebuild)
        remove = QPushButton("Откл.");remove.setToolTip("Отключить эту камеру от пульта")
        remove.clicked.connect(lambda: window.remove(self))
        full=QPushButton("⛶");full.setToolTip("Открыть камеру во весь экран");full.clicked.connect(self.open_fullscreen);heading.addWidget(full)
        heading.addWidget(self.name, 2); heading.addWidget(self.group, 1); heading.addWidget(remove)
        box.addLayout(heading)
        self.video = Viewfinder()
        box.addWidget(self.video,1)
        readings=QHBoxLayout();readings.setSpacing(4)
        self.iso_readout=QPushButton("ISO —");self.shutter_readout=QPushButton("S —")
        self.focus_readout=QPushButton("AF");self.profile_readout=QPushButton("—")
        for button in (self.iso_readout,self.shutter_readout,self.focus_readout,self.profile_readout):
            button.setObjectName("readout");readings.addWidget(button)
        self.iso_readout.clicked.connect(lambda:self.settings(1));self.shutter_readout.clicked.connect(lambda:self.settings(1))
        self.focus_readout.clicked.connect(lambda:self.settings(1));self.profile_readout.clicked.connect(lambda:self.settings(0))
        box.addLayout(readings)
        self.info = QLabel(client.host)
        self.info.setWordWrap(True);self.info.setStyleSheet("color:#858585;font-size:11px")
        box.addWidget(self.info)
        controls = QHBoxLayout()
        self.photo = QPushButton("◎ Фото");self.photo.setObjectName("primary")
        self.photo.clicked.connect(lambda: self.command("photo"))
        self.record = QPushButton("● Запись")
        self.record.clicked.connect(lambda: self.command("record_stop" if self.status.get("recording") else "record_start"))
        self.settings_button = QPushButton("Настройки")
        self.settings_button.clicked.connect(lambda:self.settings(0))
        self.files = QPushButton("Материалы")
        self.files.clicked.connect(self.media)
        self.mode=QComboBox();self.mode.addItem("Фото","photo");self.mode.addItem("Видео","video");self.mode.setMaximumWidth(105)
        self.mode.activated.connect(lambda _:self.command("capture_config",mode=self.mode.currentData()))
        controls.addWidget(self.mode)
        for button in (self.photo, self.record, self.settings_button, self.files): controls.addWidget(button)
        box.addLayout(controls)
        switches=QHBoxLayout()
        self.view_toggle = Toggle("Просмотр");self.view_toggle.setToolTip("Передавать изображение с камеры")
        self.view_toggle.setChecked(True)
        self.view_toggle.toggled.connect(window.rebuild)
        switches.addWidget(self.view_toggle)
        self.listen=Toggle("Слушать звук");self.listen.setToolTip("Слушать микрофон телефона; звук в записи настраивается отдельно")
        self.listen.toggled.connect(self.sync_audio)
        switches.addWidget(self.listen);switches.addStretch();box.addLayout(switches)
        self.monitor=AudioMonitor(client,self);self.monitor.failed.connect(self.audio_failed)
        self.phone_update = QPushButton("Обновить Android")
        self.phone_update.setEnabled(False)
        self.phone_update.setVisible(False)
        self.phone_update.clicked.connect(lambda: window.update_phone(self))
        box.addWidget(self.phone_update)
        self.feed = Feed()
        self.feed.image.connect(self.show_frame)
        self.feed.state.connect(self.show_status)
        self.feed.failure.connect(self.show_error)
        self.worker = threading.Thread(target=self.poll, daemon=True, name="camera-preview")
        self.worker.start()
        self.display_timer=QTimer(self);self.display_timer.timeout.connect(self.display_latest);self.display_timer.start(33)
        self.video.mouseDoubleClickEvent=lambda event:self.open_fullscreen()

    def set_preview_active(self, visible):
        if (visible or self.fullscreen is not None) and self.view_toggle.isChecked():
            self.preview_enabled.set()
        else:
            self.preview_enabled.clear()
            self.video.setText("Предпросмотр приостановлен · запись продолжается, если включена")
        self.sync_audio()

    def audio_failed(self,message):
        self.listen.setChecked(False);self.monitor.stop()
        self.window.message("Звук",message)

    def sync_audio(self):
        if self.listen.isChecked() and self.preview_enabled.is_set() and not self.stopped.is_set():
            try:self.monitor.start()
            except Exception as exc:self.audio_failed(str(exc))
        else:self.monitor.stop()

    def open_fullscreen(self):
        if self.fullscreen is not None:self.fullscreen.raise_();return
        dialog=QDialog(self.window);dialog.setWindowTitle(self.name.text());dialog.setStyleSheet(THEME)
        layout=QVBoxLayout(dialog);bar=QHBoxLayout();bar.addWidget(QLabel(self.name.text()));bar.addStretch();close=QPushButton("Выйти из полного экрана · Esc");close.clicked.connect(dialog.close);bar.addWidget(close);layout.addLayout(bar)
        view=Viewfinder();layout.addWidget(view,1);view.set_status(self.status)
        if self.video.frame is not None:view.set_frame(self.video.frame)
        self.fullscreen=dialog;self.full_view=view
        def finished():self.fullscreen=None;self.full_view=None;dialog.deleteLater();self.window.rebuild()
        dialog.finished.connect(finished);dialog.showFullScreen();self.set_preview_active(True)

    def display_latest(self):
        with self.frame_lock:pending=self.pending_frame;self.pending_frame=None
        if pending is None:return
        image,rotation=pending
        if rotation:image=image.transformed(QTransform().rotate(rotation),Qt.TransformationMode.SmoothTransformation)
        self.video.set_frame(image)
        if self.fullscreen is not None:self.full_view.set_frame(image)

    def receive_frames(self):
        stream_supported=hasattr(self.client,"frames")
        while not self.stopped.is_set():
            if not self.preview_enabled.is_set() or self.paused.is_set():self.stopped.wait(.1);continue
            try:
                if stream_supported:
                    for data,rotation in self.client.frames(self.stopped,self.preview_enabled):
                        if self.paused.is_set():break
                        image=QImage.fromData(data)
                        if not image.isNull():
                            with self.frame_lock:self.pending_frame=(image,rotation)
                else:
                    data=self.client.request("/frame",limit=2_000_000);image=QImage.fromData(data)
                    if not image.isNull():
                        with self.frame_lock:self.pending_frame=(image,self.status.get("rotation",0))
                    self.stopped.wait(1/max(1,min(30,self.status.get("previewFps",10))))
            except StreamUnavailable:stream_supported=False
            except Exception as exc:
                if self.stopped.is_set():return
                self.feed.failure.emit(str(exc));self.stopped.wait(1)

    def poll(self):
        threading.Thread(target=self.receive_frames,daemon=True,name="camera-stream").start()
        while not self.stopped.is_set():
            if self.paused.is_set():self.stopped.wait(.2);continue
            try:self.feed.state.emit(self.client.json("/status"))
            except Exception as exc:
                if self.stopped.is_set():return
                self.feed.failure.emit(str(exc))
            self.stopped.wait(1 if self.preview_enabled.is_set() else 3)

    def show_error(self, message):
        self.failures += 1
        self.info.setText("Связь / камера: " + message + ". При истечении сеанса подключитесь заново.")
        self.video.setText("Нет актуального изображения")
        self.photo.setEnabled(False); self.record.setEnabled(False)

    def show_frame(self, data, status):
        if not self.preview_enabled.is_set(): return
        self.show_status(status)
        image = QImage.fromData(data)
        if image.isNull(): return
        self.video.set_frame(image.transformed(QTransform().rotate(status.get("rotation", 0))))

    def show_status(self, status):
        self.status = status
        self.failures = 0
        recording = status.get("recording", False)
        self.video.set_status(status)
        if self.fullscreen is not None:self.full_view.set_status(status)
        self.iso_readout.setText("ISO  "+("Авто" if not status.get("manualIso") else str(status.get("manualIso")))+" ▾")
        self.shutter_readout.setText("Выдержка  "+("Авто" if not status.get("manualExposureNs") else shutter_label(status["manualExposureNs"]))+" ▾")
        self.focus_readout.setText("Фокус  "+("Авто" if status.get("focus",-1)<0 else f"{status['focus']:.1f}")+" ▾")
        self.profile_readout.setText(f"{status.get('videoHeight',1080)}p {status.get('videoFps',30)}" if status.get("mode")=="video" else status.get("photoFormat","jpg").upper())
        mode=status.get("mode","photo")
        self.mode.setCurrentIndex(1 if mode=="video" else 0);self.mode.setEnabled(not recording and status.get("ready",False))
        self.record.setText("■ Стоп" if recording else "● Запись")
        self.record.setStyleSheet("background:#a62520;color:#ffffff;border-color:#ed554b" if recording else "")
        self.record.setEnabled(status.get("ready", False) and mode=="video")
        self.photo.setEnabled(status.get("ready", False) and not recording and mode=="photo")
        self.listen.setEnabled(status.get("audioAvailable",False))
        self.info.setText(status.get('error') or f"{self.client.host}  /  REC {status.get('videoWidth',1920)}×{status.get('videoHeight',1080)} {status.get('videoFps',30)}p  /  {'AAC' if status.get('audio') else 'MUTE'}  /  VIEW ≈640px")
        free=status.get("storageFreeBytes",-1)
        if free>=0:self.info.setText(f"{status.get('storageName','Телефон')} · {free/1073741824:.1f} ГБ свободно"+("  ·  "+status['error'] if status.get('error') else ""))
        self.info.setToolTip(status.get("lastFile", ""))
        if status.get("previewBuild"):
            self.phone_update.setEnabled(False); self.phone_update.hide()
        elif self.window.available:
            self.phone_update.setEnabled(self.window.available["code"] > status.get("versionCode", 0) and not recording)
            self.phone_update.setVisible(self.window.available["code"] > status.get("versionCode",0))

    def command(self, action, **kwargs):
        self.window.run(lambda: self.client.command(action, **kwargs), lambda _: self.info.setText("Команда принята; ожидаем состояние камеры…"))

    def settings(self,initial_tab=0):
        if not self.status: return
        status = self.status
        dialog = GlassSheet(self.window)
        dialog.setWindowTitle("Настройки камеры — " + self.name.text())
        dialog.setMinimumWidth(530)
        outer=QVBoxLayout(dialog);outer.setContentsMargins(24,24,24,24);tabs=QTabWidget();outer.addWidget(tabs)
        capture_page=QWidget();sensor_page=QWidget()
        capture_page.setObjectName("settingsPage");sensor_page.setObjectName("settingsPage")
        tabs.addTab(capture_page,"Съёмка и форматы");tabs.addTab(sensor_page,"Экспозиция и фокус")
        tabs.setCurrentIndex(initial_tab)
        form = QFormLayout(capture_page)
        form.setVerticalSpacing(10)
        cameras = QComboBox()
        for camera in status.get("cameras", []):
            cameras.addItem(f"Камера {camera['id']} · " + {0: "фронтальная", 1: "задняя", 2: "внешняя"}.get(camera.get("facing"), ""), camera["id"])
            if camera["id"] == status.get("cameraId"): cameras.setCurrentIndex(cameras.count()-1)
        cameras.setEnabled(not status.get("recording"))
        form.addRow("Объектив", cameras)
        capabilities=status.get("captureCapabilities",{})
        video_modes=capabilities.get("videoModes",[])
        video_size=QComboBox();video_rate=QComboBox()
        for item in sorted(video_modes,key=lambda m:m["width"]*m["height"],reverse=True):
            video_size.addItem(f"{item['width']} × {item['height']}",item)
            if (item["width"],item["height"])==(status.get("videoWidth"),status.get("videoHeight")):video_size.setCurrentIndex(video_size.count()-1)
        def fill_rates():
            video_rate.clear()
            for rate in (video_size.currentData() or {}).get("fps",[]):video_rate.addItem(str(rate),rate)
            index=video_rate.findData(status.get("videoFps",30))
            if index>=0:video_rate.setCurrentIndex(index)
        video_size.currentIndexChanged.connect(fill_rates);fill_rates()
        form.addRow("Разрешение видеозаписи",video_size);form.addRow("FPS видеозаписи (целевой)",video_rate)
        sound=Toggle("Звук в видеозаписи");sound.setChecked(status.get("audio",True));form.addRow(sound)
        if not status.get("audioAvailable",False):form.addRow(QLabel("Для звука разрешите микрофон на телефоне и перезапустите камеру."))
        photo_format=QComboBox()
        for fmt in capabilities.get("photoFormats",[]):photo_format.addItem({"jpg":"JPEG","png":"PNG","webp":"WebP","dng":"RAW DNG"}.get(fmt,fmt),fmt)
        photo_format.setCurrentIndex(max(0,photo_format.findData(status.get("photoFormat","jpg"))))
        photo_size=QComboBox()
        def fill_photo_sizes():
            photo_size.clear()
            sizes=capabilities.get("rawSizes" if photo_format.currentData()=="dng" else "photoSizes",[])
            for item in sorted(sizes,key=lambda m:m["width"]*m["height"],reverse=True):
                photo_size.addItem(f"{item['width']} × {item['height']} · {item['width']*item['height']/1e6:.1f} Мп",item)
                if (item["width"],item["height"])==(status.get("photoWidth"),status.get("photoHeight")):photo_size.setCurrentIndex(photo_size.count()-1)
        photo_format.currentIndexChanged.connect(fill_photo_sizes);fill_photo_sizes()
        form.addRow("Формат фотографии",photo_format);form.addRow("Разрешение фотографии",photo_size)
        for control in (video_size,video_rate,sound,photo_format,photo_size):control.setEnabled(not status.get("recording",False))
        format_note=QLabel("PNG и WebP создаются из обработанного JPEG. DNG — настоящий RAW,\nесли его предоставляет камера. Менять формат и режим можно после остановки записи.")
        format_note.setWordWrap(True);form.addRow(format_note)
        form=QFormLayout(sensor_page)
        auto = Toggle("Автоматическая экспозиция")
        auto.setChecked(not status.get("manualIso"))
        auto.setEnabled(status.get("manualSensor", False))
        form.addRow(auto)
        iso=SliderField(status.get("isoMin",50),status.get("isoMax",6400),status.get("manualIso") or status.get("iso",100))
        minimum=max(.0001,status.get("exposureMinNs",1000)/1e6)
        maximum=min(1000/max(1,status.get("videoFps",30)) if status.get("recording") else status.get("exposureMaxNs",100000000)/1e6,status.get("exposureMaxNs",100000000)/1e6)
        exposure=SliderField(minimum,maximum,(status.get("manualExposureNs") or status.get("exposureNs",16666666))/1e6,4,True)
        form.addRow("ISO", iso); form.addRow("Выдержка, мс", exposure)
        def enable_exposure():
            enabled = not auto.isChecked() and status.get("manualSensor", False)
            iso.setEnabled(enabled); exposure.setEnabled(enabled)
        auto.toggled.connect(enable_exposure); enable_exposure()
        focus_auto = Toggle("Автофокус"); focus_auto.setChecked(status.get("focus", -1) < 0)
        focus=SliderField(0,status.get("focusMax") or 0,max(0,status.get("focus",-1)),2)
        focus_auto.setEnabled(bool(status.get("focusMax"))); focus.setEnabled(not focus_auto.isChecked() and bool(status.get("focusMax")))
        focus_auto.toggled.connect(lambda checked: focus.setEnabled(not checked and bool(status.get("focusMax"))))
        form.addRow(focus_auto); form.addRow("Фокус, диоптрии (0 = ∞)", focus)
        capabilities_label=QLabel("Возможности объектива: "+str(len(video_modes))+" видеорежимов · "+str(len(capabilities.get("photoSizes",[])))+" размеров фото")
        capabilities_label.setWordWrap(True);form.addRow(capabilities_label)
        special=capabilities.get("highSpeedModes",[]);maximum=capabilities.get("maximumSensorSizes",[])
        if special or maximum:
            details=QLabel("Специальные сеансы (показаны для справки):\n"+"\n".join(f"High speed · {v['width']} × {v['height']} · "+", ".join(f"{r['min']}–{r['max']} fps" for r in v.get('ranges',[])) for v in special)+"\n"+"\n".join(f"Maximum sensor · {v['width']} × {v['height']}" for v in maximum))
            details.setWordWrap(True);form.addRow(details)
        note = QLabel("При смене объектива сначала примените выбор, затем откройте настройки снова.\nДоступные диапазоны сообщает сам телефон.")
        note.setWordWrap(True); form.addRow(note)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.accepted.connect(dialog.accept); buttons.rejected.connect(dialog.reject); outer.addWidget(buttons)
        if dialog.exec() != QDialog.DialogCode.Accepted: return
        if cameras.currentData() != status.get("cameraId"):
            self.command("camera", id=cameras.currentData())
        else:
            sensor={"iso":0 if auto.isChecked() else iso.value(),"exposureNs":0 if auto.isChecked() else round(exposure.value()*1e6),"focus":-1 if focus_auto.isChecked() else focus.value()}
            capture=None
            if not status.get("recording") and photo_size.currentData():
                v=video_size.currentData() or {"width":status.get("videoWidth",1920),"height":status.get("videoHeight",1080)}
                p=photo_size.currentData()
                capture={"mode":status.get("mode","photo"),"videoWidth":v["width"],"videoHeight":v["height"],"videoFps":video_rate.currentData() or status.get("videoFps",30),
                    "photoFormat":photo_format.currentData(),"photoWidth":p["width"],"photoHeight":p["height"],"audio":sound.isChecked()}
            def apply():
                self.client.command("settings",**sensor)
                if capture is not None:self.client.command("capture_config",**capture)
            self.window.run(apply,lambda _:self.info.setText("Настройки приняты; ожидаем подтверждение камеры…"))

    def media(self):
        def choose(data):
            files = data.get("files", [])
            if not files:
                self.window.message("Материалы", "На телефоне пока нет готовых записей."); return
            names = [f"{f['name']}  ·  {f['size']/1048576:.1f} МБ" for f in files]
            selected, ok = QInputDialog.getItem(self, "Материалы на телефоне", "Выберите оригинал для скачивания:", names, 0, False)
            if not ok: return
            folder = QFileDialog.getExistingDirectory(self, "Куда сохранить оригинал")
            if not folder: return
            filename = files[names.index(selected)]["name"]
            self.window.run(lambda: self.client.download(filename, Path(folder)), lambda path: self.window.message("Скачано", str(path)))
        self.window.run(lambda: self.client.json("/media"), choose)


class Window(QMainWindow):
    def __init__(self,auto_checks=True):
        super().__init__()
        self.setWindowTitle("Camera Deck · Windows · " + updates.VERSION)
        self.resize(1280, 820)
        self.setMinimumSize(960,620)
        self.tiles, self.jobs, self.devices = [], [], []
        self.available = None
        self.checking = False
        self.scanning=False
        self.setStyleSheet(THEME)
        root = QWidget(); root.setObjectName("root"); self.setCentralWidget(root); layout = QVBoxLayout(root); layout.setContentsMargins(24,22,24,18);layout.setSpacing(16)
        brand=QHBoxLayout()
        title = QLabel("Camera Deck Aurora"); title.setStyleSheet("font-size:26px;font-weight:600;color:#f4f4f4");brand.addWidget(title)
        subtitle = QLabel("Камеры рядом. Управление здесь."); subtitle.setStyleSheet("font-size:12px;color:#a5a5a5");brand.addWidget(subtitle);brand.addStretch()
        self.connection_count=QLabel("Нет подключённых камер");self.connection_count.setStyleSheet("color:#ffd76b");brand.addWidget(self.connection_count)
        self.devices_button=QPushButton("Устройства");self.devices_button.setCheckable(True);self.devices_button.setChecked(True)
        self.devices_button.clicked.connect(lambda checked:self.sidebar.setVisible(checked));brand.addWidget(self.devices_button)
        layout.addLayout(brand)
        bar = QHBoxLayout()
        self.device_list = QComboBox(); self.device_list.setMinimumWidth(200); self.device_list.setPlaceholderText("Выберите телефон")
        self.scan = QPushButton("Найти телефоны"); self.scan.clicked.connect(self.scan_devices)
        connect = QPushButton("Подключить");connect.setObjectName("primary"); connect.clicked.connect(self.connect_device)
        manual = QPushButton("По IP"); manual.clicked.connect(self.manual)
        self.filter = QComboBox(); self.filter.addItem("Все группы"); self.filter.currentTextChanged.connect(self.rebuild)
        self.columns = QComboBox(); self.columns.addItems(["1 колонка", "2 колонки", "3 колонки"]); self.columns.setCurrentIndex(1); self.columns.currentIndexChanged.connect(self.rebuild)
        for widget in (self.scan,self.device_list,connect,manual,self.filter,self.columns): bar.addWidget(widget)
        layout.addLayout(bar)
        self.hint = QLabel("Включите камеру в Android-приложении. Телефон и компьютер должны быть в одной сети.")
        self.hint.setWordWrap(True); layout.addWidget(self.hint)
        self.hint.setStyleSheet("color:#828282;font-size:11px")
        scroll = QScrollArea(); scroll.setWidgetResizable(True)
        self.grid_widget = QWidget(); self.grid_widget.setObjectName("cameraGrid"); self.grid = QGridLayout(self.grid_widget);self.grid.setContentsMargins(0,0,0,0);self.grid.setSpacing(10); self.grid.setAlignment(Qt.AlignmentFlag.AlignTop)
        scroll.setWidget(self.grid_widget)
        body=QHBoxLayout();body.setSpacing(18)
        self.sidebar=QFrame();self.sidebar.setObjectName("tile");self.sidebar.setFixedWidth(270);side=QVBoxLayout(self.sidebar);side.setContentsMargins(16,18,16,18)
        heading=QLabel("ТЕЛЕФОНЫ РЯДОМ");heading.setStyleSheet("color:#ffd76b;font-size:11px;letter-spacing:2px");side.addWidget(heading)
        self.device_cards=QWidget();self.device_cards_layout=QVBoxLayout(self.device_cards);self.device_cards_layout.setContentsMargins(0,8,0,8)
        self.device_cards_layout.setAlignment(Qt.AlignmentFlag.AlignTop);side.addWidget(self.device_cards,1)
        side.addWidget(QLabel("Откройте камеру на телефоне.\nПодключитесь к общей Wi-Fi сети."))
        body.addWidget(self.sidebar);body.addWidget(scroll,1);layout.addLayout(body,1)
        self.refresh_devices()
        self.empty = QLabel("<span style='font-size:34px;color:#f4f4f4'>Ваша камера. Больше возможностей.</span><br><br>"
            "Подключите Android-телефон любой марки<br><br>"
            "<span style='color:#b9c8df'>1 · Откройте Camera Deck на телефоне и включите камеру<br>"
            "2 · Подключите оба устройства к одной Wi-Fi сети<br>"
            "3 · Нажмите «Найти телефоны» и введите PIN</span><br><br>"
            "<span style='font-size:12px'>Android 9+ · Возможности съёмки определяются камерой телефона<br>"
            "Телефон не найден? Подключитесь по IP из раздела «Подключить» на телефоне.</span>")
        self.empty.setAlignment(Qt.AlignmentFlag.AlignCenter); self.empty.setMinimumHeight(360); self.empty.setStyleSheet("font-size:15px;color:#a5a5a5;line-height:1.6;background:#111b29;border:1px solid #29394f;border-radius:24px;padding:30px")
        self.grid.addWidget(self.empty,0,0)
        bottom = QHBoxLayout()
        self.auto = Toggle("Автообновления")
        self.auto.setChecked(self.load_preferences().get("autoUpdates", True))
        self.auto.toggled.connect(self.save_preferences)
        self.update_label = QLabel("v"+updates.VERSION+" · обновления проверяются автоматически")
        self.update_label.setWordWrap(True)
        check = QPushButton("Проверить обновления"); check.clicked.connect(lambda: self.check_update(True))
        self.install_button = QPushButton("Обновить Windows"); self.install_button.setEnabled(False); self.install_button.clicked.connect(self.install_windows)
        bottom.addWidget(self.auto); bottom.addWidget(self.update_label);bottom.addStretch()
        for widget in (check,self.install_button): bottom.addWidget(widget)
        layout.addLayout(bottom)
        self.timer = QTimer(self); self.timer.timeout.connect(lambda: self.check_update() if self.auto.isChecked() else None); self.timer.start(3600000)
        self.discovery_timer=QTimer(self);self.discovery_timer.timeout.connect(lambda:self.scan_devices() if self.sidebar.isVisible() else None)
        if auto_checks:self.discovery_timer.start(8000)
        if auto_checks: QTimer.singleShot(300, self.scan_devices)
        if auto_checks: QTimer.singleShot(1200, lambda: self.check_update() if self.auto.isChecked() else None)

    def load_preferences(self):
        try: return json.loads((updates.DATA/"preferences.json").read_text())
        except (OSError,ValueError): return {}

    def save_preferences(self):
        updates.DATA.mkdir(parents=True,exist_ok=True)
        (updates.DATA/"preferences.json").write_text(json.dumps({"autoUpdates":self.auto.isChecked()}))

    def run(self, function, callback=lambda _: None, failure=None):
        signal = Result(); self.jobs.append(signal)
        def finish(value):
            if signal in self.jobs: self.jobs.remove(signal)
            callback(value)
        def fail(message):
            if signal in self.jobs: self.jobs.remove(signal)
            if failure: failure(message)
            else: self.message("Не выполнено", message)
        signal.done.connect(finish); signal.failed.connect(fail)
        def work():
            try: value = function()
            except Exception as exc: signal.failed.emit(str(exc))
            else: signal.done.emit(value)
        threading.Thread(target=work,daemon=True).start()

    def message(self, title, body): QMessageBox.information(self,title,body)

    def scan_devices(self):
        if self.scanning:return
        self.scanning=True
        self.scan.setEnabled(False); self.hint.setText("Ищем телефоны в локальной сети…")
        def done(devices):
            self.scanning=False;self.scan.setEnabled(True); self.devices = devices;self.refresh_devices(); self.device_list.clear()
            for item in devices: self.device_list.addItem(f"{item['name']} · {item['host']}",item)
            self.hint.setText(f"Найдено: {len(devices)}. Выберите устройство и нажмите «Подключить». Если список пуст — проверьте Wi-Fi и доступ через брандмауэр или введите IP.")
        def failed(message): self.scanning=False;self.scan.setEnabled(True); self.hint.setText(message)
        self.run(discover,done,failed)

    def refresh_devices(self):
        while self.device_cards_layout.count():
            item=self.device_cards_layout.takeAt(0)
            if item.widget():item.widget().deleteLater()
        if not self.devices:
            label=QLabel("Ищем доступные телефоны…\n\nНе найден? Нажмите «По IP».");label.setWordWrap(True);self.device_cards_layout.addWidget(label)
        for device in self.devices:
            card=QFrame();card.setObjectName("tile");box=QVBoxLayout(card)
            name=QLabel(device['name']);name.setWordWrap(True);name.setStyleSheet("font-size:15px;font-weight:600");box.addWidget(name)
            box.addWidget(QLabel(device['host']))
            connected=any(t.client.host==device['host'] for t in self.tiles)
            button=QPushButton("Подключён" if connected else "Подключить →");button.setObjectName("primary");button.setEnabled(not connected)
            button.clicked.connect(lambda checked=False,d=device:self.begin_connect(d['host'],d['name'],d['fingerprint']));box.addWidget(button);self.device_cards_layout.addWidget(card)

    def connect_device(self):
        item = self.device_list.currentData()
        if item: self.begin_connect(item["host"],item["name"],item["fingerprint"])
        else: self.scan_devices()

    def manual(self):
        ip, ok = QInputDialog.getText(self,"Подключение по IP","IPv4-адрес из Android-приложения:")
        if ok and ip.strip(): self.begin_connect(ip.strip(),ip.strip())

    def begin_connect(self, host, name, announced=""):
        if any(tile.client.host == host for tile in self.tiles):
            self.message("Подключение","Этот телефон уже подключён."); return
        try: client = CameraClient(host)
        except ValueError as exc: self.message("Адрес",str(exc)); return
        def confirm(fp):
            if announced and fp != announced:
                self.message("Сертификат","Отпечаток из поиска отличается от сертификата. Повторите подключение по IP и сравните отпечаток на телефоне."); return
            formatted = " ".join(fp[i:i+8] for i in range(0,len(fp),8))
            dialog = QDialog(self); dialog.setWindowTitle("Подключить телефон"); dialog.setMinimumWidth(480)
            form = QFormLayout(dialog); form.setSpacing(16)
            form.addRow(QLabel(f"{name} · {host}"))
            note = QLabel("Откройте «Подключить» на телефоне. Сравните весь отпечаток и введите PIN.")
            note.setWordWrap(True); form.addRow(note)
            fingerprint = QLabel(formatted); fingerprint.setWordWrap(True)
            fingerprint.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
            form.addRow("SHA-256", fingerprint)
            verified = QCheckBox("Отпечаток полностью совпадает с телефоном"); form.addRow(verified)
            pin = QLineEdit(); pin.setPlaceholderText("0000"); pin.setMaxLength(4)
            pin.setEchoMode(QLineEdit.EchoMode.Password); form.addRow("PIN телефона", pin)
            buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
            accept = buttons.button(QDialogButtonBox.StandardButton.Ok); accept.setText("Подключить"); accept.setObjectName("primary")
            buttons.button(QDialogButtonBox.StandardButton.Cancel).setText("Отмена")
            def validate():
                value = pin.text()
                accept.setEnabled(verified.isChecked() and len(value)==4 and value.isascii() and value.isdigit())
            verified.toggled.connect(validate); pin.textChanged.connect(validate); validate()
            buttons.accepted.connect(dialog.accept); buttons.rejected.connect(dialog.reject); form.addRow(buttons)
            pin.setFocus()
            if dialog.exec() != QDialog.DialogCode.Accepted: return
            def paired(result):
                tile = CameraTile(self,client,result.get("name",name)); self.tiles.append(tile); self.rebuild()
            self.run(lambda: client.pair(pin.text()),paired)
        self.run(client.probe,confirm)

    def remove(self,tile):
        if tile.fullscreen is not None:tile.fullscreen.close()
        tile.stopped.set();tile.monitor.stop(); self.tiles.remove(tile); self.grid.removeWidget(tile); tile.hide(); self.rebuild()
        # Keep QObject alive until its networking thread has observed the stop event.
        QTimer.singleShot(10000,tile.deleteLater)

    def rebuild(self):
        self.refresh_devices()
        self.connection_count.setText(f"Камер: {len(self.tiles)}" if self.tiles else "Нет подключённых камер")
        current = self.filter.currentText()
        groups = sorted({tile.group.text().strip() or "Общая" for tile in self.tiles})
        self.filter.blockSignals(True); self.filter.clear(); self.filter.addItems(["Все группы",*groups]); self.filter.setCurrentText(current if current in groups else "Все группы"); self.filter.blockSignals(False)
        while self.grid.count(): self.grid.takeAt(0)
        self.empty.setVisible(not self.tiles)
        if not self.tiles: self.grid.addWidget(self.empty,0,0)
        index = 0; columns = self.columns.currentIndex()+1
        for tile in self.tiles:
            visible = self.filter.currentText() == "Все группы" or self.filter.currentText() == (tile.group.text().strip() or "Общая")
            tile.setVisible(visible)
            tile.set_preview_active(visible and not self.isMinimized())
            if visible: self.grid.addWidget(tile,index//columns,index%columns); index += 1

    def check_update(self,manual=False):
        if self.checking: return
        self.checking = True; self.update_label.setText("Проверяем релизы…")
        def done(result):
            self.checking=False; self.available=result
            if result and result["code"] > updates.VERSION_CODE:
                self.update_label.setText("Доступна версия " + result["version"] + ". Загружаем проверенный пакет…")
                self.run(lambda: updates.download_update(result,"windows"), lambda _: self.ready_update(result))
            else: self.update_label.setText("Установлена последняя версия" if result else "Релизы Camera Deck ещё не опубликованы")
        def failed(message):
            self.checking=False; self.update_label.setText("Не удалось проверить публичные релизы")
            if manual: self.message("Обновления",message)
        self.run(updates.check_updates,done,failed)

    def ready_update(self,result):
        self.update_label.setText("Готово обновление " + result["version"])
        self.install_button.setEnabled(True)

    def is_recording(self): return any(not tile.status or tile.status.get("recording") or tile.failures for tile in self.tiles)

    def install_windows(self):
        if self.is_recording(): self.message("Обновление","Остановите запись и восстановите связь со всеми камерами перед перезапуском."); return
        result=self.available
        if not result: return
        def install(path):
            if self.is_recording(): self.message("Обновление","Камера записывает или недоступна. Повторите после остановки записи."); return
            if QMessageBox.question(self,"Обновить Windows", "Перезапустить Camera Deck и установить " + result["version"] + "?") != QMessageBox.StandardButton.Yes: return
            try: updates.restart_with_update(path,result)
            except Exception as exc: self.message("Обновление",str(exc)); return
            self.close()
        self.run(lambda: updates.download_update(result,"windows"),install)

    def update_phone(self,tile):
        result=self.available
        if not result or tile.status.get("recording"): return
        tile.phone_update.setEnabled(False)
        def work():
            apk=updates.download_update(result,"android")
            tile.paused.set()
            try: tile.client.send_update(apk,result["envelope"])
            finally: tile.paused.clear()
        self.run(work,lambda _: self.message("Android","Пакет проверен и передан. На телефоне нажмите «Установить обновление» и подтвердите установку."))

    def closeEvent(self,event:QCloseEvent):
        if self.is_recording() and QMessageBox.question(self,"Закрыть пульт", "Есть запись или потеряна связь. Камеры на телефонах продолжат работу. Закрыть пульт?") != QMessageBox.StandardButton.Yes:
            event.ignore(); return
        for tile in self.tiles: tile.stopped.set();tile.monitor.stop()
        event.accept()

    def changeEvent(self,event):
        super().changeEvent(event)
        from PyQt6.QtCore import QEvent
        if event.type() == QEvent.Type.WindowStateChange:
            self.rebuild()


def main():
    self_test = len(sys.argv) == 3 and sys.argv[1] == "--self-test"
    if self_test: os.environ["QT_QPA_PLATFORM"]="offscreen"
    app=QApplication(sys.argv)
    font_file=Path(os.environ.get("WINDIR","C:/Windows"))/"Fonts"/"segoeui.ttf"
    if font_file.is_file(): QFontDatabase.addApplicationFont(str(font_file))
    app.setFont(QFont("Segoe UI",10))
    window=Window(auto_checks=not self_test); window.show()
    if self_test:
        app.processEvents()
        out=Path(sys.argv[2]); out.mkdir(parents=True,exist_ok=True)
        assert window.empty.isVisible() and window.device_list.count()==0
        assert window.grab().save(str(out/"windows-preview.png"))
        (out/"windows-self-test.json").write_text(json.dumps({"version":updates.VERSION,"rendered":True,"hardwareTested":False}))
        window.close()
        return 0
    return app.exec()


if __name__ == "__main__": raise SystemExit(main())
