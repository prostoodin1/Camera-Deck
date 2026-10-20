"""Authenticated media catalog and local playback, with bounded thumbnail work."""
from pathlib import Path
import json
from urllib.parse import urlencode
from PyQt6.QtCore import Qt,QUrl,pyqtSignal
from PyQt6.QtGui import QIcon,QPixmap
from PyQt6.QtWidgets import (QWidget,QVBoxLayout,QHBoxLayout,QPushButton,QLabel,QListWidget,
    QListWidgetItem,QFileDialog,QProgressBar,QLineEdit,QComboBox,QSlider)
from PyQt6.QtMultimedia import QMediaPlayer,QAudioOutput
from PyQt6.QtMultimediaWidgets import QVideoWidget


class MediaBrowser(QWidget):
    enrichment=pyqtSignal(object)
    progress=pyqtSignal(object)
    def __init__(self,window):
        super().__init__();self.window=window;self.camera=None;self.epoch=0;self.items={};self.local={};self.downloading=False
        layout=QVBoxLayout(self);layout.setContentsMargins(0,0,0,0)
        bar=QHBoxLayout();self.search=QLineEdit();self.search.setPlaceholderText('Поиск по имени');self.search.textChanged.connect(self.filter);bar.addWidget(self.search)
        self.kind=QComboBox();self.kind.addItems(['Все материалы','Видео','Фото','Незавершённые']);self.kind.currentIndexChanged.connect(self.filter);bar.addWidget(self.kind)
        refresh=QPushButton('Обновить');refresh.clicked.connect(self.reload);bar.addWidget(refresh);layout.addLayout(bar)
        recovery=QPushButton('Восстановить незавершённую запись ПК');recovery.clicked.connect(self.recover);layout.addWidget(recovery)
        self.notice=QLabel('Выберите подключённую камеру в рабочем пространстве «Камера».');self.notice.setWordWrap(True);self.notice.setObjectName('muted');layout.addWidget(self.notice)
        self.list=QListWidget();self.list.setIconSize(__import__('PyQt6.QtCore',fromlist=['QSize']).QSize(128,72));self.list.currentItemChanged.connect(self.selection);layout.addWidget(self.list,1)
        actions=QHBoxLayout();self.download=QPushButton('Скачать оригинал');self.download.setObjectName('primary');self.download.clicked.connect(self.save);actions.addWidget(self.download)
        self.play=QPushButton('Воспроизвести');self.play.clicked.connect(self.play_selected);actions.addWidget(self.play);self.retry=QPushButton('Повторить / обновить');self.retry.clicked.connect(self.reload);actions.addWidget(self.retry);actions.addStretch();layout.addLayout(actions)
        self.bar=QProgressBar();self.bar.setRange(0,1000);self.bar.setValue(0);layout.addWidget(self.bar)
        self.video=QVideoWidget();self.video.setMinimumHeight(180);self.video.hide();layout.addWidget(self.video)
        self.player=QMediaPlayer(self);self.audio=QAudioOutput(self);self.player.setAudioOutput(self.audio);self.player.setVideoOutput(self.video)
        self.position=QSlider(Qt.Orientation.Horizontal);self.position.hide();layout.addWidget(self.position);self.position.sliderMoved.connect(self.player.setPosition)
        self.player.durationChanged.connect(lambda value:self.position.setRange(0,value));self.player.positionChanged.connect(lambda value:not self.position.isSliderDown() and self.position.setValue(value));self.player.errorOccurred.connect(lambda error,message:self.notice.setText('Не удалось воспроизвести: '+message))
        self.enrichment.connect(self.enriched);self.progress.connect(self.show_progress);self.selection()

    def set_camera(self,camera):
        if camera is self.camera:return
        self.player.stop();self.camera=camera;self.epoch+=1;self.reload()

    def reload(self):
        self.epoch+=1;epoch=self.epoch;camera=self.camera;self.list.clear();self.items={};self.selection()
        if camera is None:return
        camera_name=camera.name.text();self.notice.setText('Читаем материалы · '+camera_name);client=camera.client
        def loaded(data):
            if epoch!=self.epoch:return
            for entry in data.get('files',[]):
                item=QListWidgetItem();item.setData(Qt.ItemDataRole.UserRole,entry);self.list.addItem(item);self.items[entry['name']]=item;self.label(item)
            self.notice.setText(f"{camera_name} · материалов: {self.list.count()}. Миниатюры анализируют готовый файл, без изменения оригинала.")
            self.filter();names=list(self.items);self.window.run(lambda:enrich(names),lambda _:None,lambda _:None)
        def enrich(names):
            for name in names[:100]:
                if epoch!=self.epoch:return
                try:
                    info=client.json('/media_info?'+urlencode({'name':name}))
                    try:thumb=client.request('/thumbnail?'+urlencode({'name':name}),limit=1_000_000)
                    except Exception:thumb=b''
                    self.enrichment.emit((epoch,name,info,thumb))
                except Exception:continue # Old phones and recording-busy phones keep the textual catalog usable.
        self.window.run(lambda:client.json('/media'),loaded,lambda message:epoch==self.epoch and self.notice.setText('Каталог недоступен. Проверьте накопитель и повторите.\n'+message))

    def label(self,item):
        entry=item.data(Qt.ItemDataRole.UserRole);duration=entry.get('durationMs');details=[]
        if not entry.get('complete',True):details.append('НЕ ЗАВЕРШЁН — требуется восстановление')
        details.append(f"{entry.get('size',0)/1048576:.1f} МБ")
        if entry.get('width'):details.append(f"{entry['width']} × {entry['height']}")
        if duration is not None:details.append(f'{int(duration)//60000:02d}:{int(duration)//1000%60:02d}')
        if entry.get('modified'):
            from datetime import datetime
            details.append(datetime.fromtimestamp(entry['modified']/1000).strftime('%d.%m.%Y %H:%M'))
        if entry.get('captureFps'):details.append(str(entry['captureFps'])+' fps (метаданные)')
        item.setText(entry['name']+'\n'+' · '.join(details))

    def enriched(self,result):
        epoch,name,info,thumbnail=result
        if epoch!=self.epoch or name not in self.items:return
        item=self.items[name];entry=item.data(Qt.ItemDataRole.UserRole);entry.update(info);item.setData(Qt.ItemDataRole.UserRole,entry);self.label(item)
        pix=QPixmap()
        if pix.loadFromData(thumbnail):item.setIcon(QIcon(pix))

    def filter(self):
        for item in self.items.values():
            e=item.data(Qt.ItemDataRole.UserRole);video=e['name'].endswith('.mp4');kind=self.kind.currentIndex()
            item.setHidden(self.search.text().lower() not in e['name'].lower() or kind==1 and not video or kind==2 and video or kind==3 and e.get('complete',True))

    def selection(self,*_):
        item=self.list.currentItem();entry=item.data(Qt.ItemDataRole.UserRole) if item else {};complete=entry.get('complete',True)
        self.download.setEnabled(bool(item) and complete and not self.downloading);self.play.setEnabled(bool(item) and self.key(entry) in self.local)

    def key(self,entry):return (getattr(self.camera.client,'fingerprint','') if self.camera else '',entry.get('name',''))

    def save(self):
        item=self.list.currentItem()
        if not item or self.camera is None or self.downloading:return
        entry=dict(item.data(Qt.ItemDataRole.UserRole));folder=QFileDialog.getExistingDirectory(self,'Сохранить оригинал')
        if not folder:return
        client=self.camera.client;key=self.key(entry);self.downloading=True;self.bar.setValue(0);self.selection();self.notice.setText('Скачиваем оригинал. Камера продолжает работу.')
        def work():return client.download(entry['name'],Path(folder),progress=lambda count,total:self.progress.emit((key,count,total)))
        def done(path):self.downloading=False;self.local[key]=Path(path);self.notice.setText('Сохранено: '+str(path));self.bar.setValue(1000);self.selection()
        def failed(message):self.downloading=False;self.notice.setText('Не удалось скачать. Выберите материал и повторите загрузку.\n'+message);self.selection()
        self.window.run(work,done,failed)

    def show_progress(self,result):
        key,count,total=result;self.bar.setValue(int(count*1000/max(1,total)))

    def play_selected(self):
        item=self.list.currentItem()
        if not item:return
        file=self.local.get(self.key(item.data(Qt.ItemDataRole.UserRole)))
        if file is None:return
        if file.suffix.lower()!='.mp4':
            from PyQt6.QtGui import QDesktopServices
            QDesktopServices.openUrl(QUrl.fromLocalFile(str(file)));return
        self.video.show();self.position.show();self.player.setSource(QUrl.fromLocalFile(str(file)));self.player.play()

    def recover(self):
        from recording import recover_partial
        source,_=QFileDialog.getOpenFileName(self,'Незавершённая запись ПК','','MP4 (*.partial.mp4 *.mp4)')
        if not source:return
        destination,_=QFileDialog.getSaveFileName(self,'Сохранить восстановленную копию',str(Path(source).with_suffix('.recovered.mp4')),'MP4 (*.mp4)')
        if not destination:return
        self.notice.setText('Восстанавливаем читаемые фрагменты. Исходный файл сохраняется.')
        self.window.run(lambda:recover_partial(source,destination),lambda file:self.notice.setText('Восстановленная копия: '+str(file)),lambda error:self.notice.setText('Восстановление не удалось. Исходник сохранён. '+error))
