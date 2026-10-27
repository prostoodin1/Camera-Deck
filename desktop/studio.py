"""Camera Deck workspaces. Network operations stay in Window.run workers."""
from datetime import datetime
from PyQt6.QtCore import Qt,QTimer,pyqtSignal
from PyQt6.QtWidgets import (QWidget,QVBoxLayout,QHBoxLayout,QLabel,QPushButton,QFrame,
    QComboBox,QLineEdit,QScrollArea,QGridLayout,QStackedWidget,QSplitter,QFormLayout,
    QTableWidget,QTableWidgetItem,QHeaderView,QPlainTextEdit,QAbstractItemView)
from appearance import Toggle
from icons import icon
import production


class Studio(QWidget):
    operation=pyqtSignal(object)
    recording_event=pyqtSignal(object)
    def __init__(self,window):
        super().__init__();self.window=window;self.selected=None;self.workspace=0;self.busy=False;self.pc_jobs={}
        self.setObjectName('root')
        outer=QVBoxLayout(self);outer.setContentsMargins(16,12,16,12);outer.setSpacing(10)
        top=QHBoxLayout();brand=QLabel('CAMERA DECK');brand.setStyleSheet('font-size:15px;font-weight:600;letter-spacing:2px');top.addWidget(brand);top.addSpacing(24)
        self.nav=[]
        for index,title in enumerate(('Монитор','Камера','Продакшн','Материалы')):
            button=QPushButton(title);button.setIcon(icon(("monitor","control","production","media")[index]));button.setCheckable(True);button.setObjectName('navigation');button.clicked.connect(lambda checked=False,i=index:self.navigate(i));top.addWidget(button);self.nav.append(button)
        top.addStretch();window.connection_count=QLabel('Нет камер');top.addWidget(window.connection_count)
        window.devices_button=QPushButton('Устройства');window.devices_button.setCheckable(True);window.devices_button.setChecked(True);top.addWidget(window.devices_button)
        outer.addLayout(top)
        self.split=QSplitter();outer.addWidget(self.split,1)
        window.sidebar=QFrame();window.sidebar.setObjectName('surface');window.sidebar.setMinimumWidth(210);window.sidebar.setMaximumWidth(300)
        side=QVBoxLayout(window.sidebar);side.setContentsMargins(12,14,12,12);side.addWidget(QLabel('УСТРОЙСТВА'))
        actions=QHBoxLayout();window.scan=QPushButton('Найти');window.scan.clicked.connect(window.scan_devices);actions.addWidget(window.scan)
        manual=QPushButton('По IP');manual.clicked.connect(window.manual);actions.addWidget(manual);side.addLayout(actions)
        usb=QPushButton('USB · кабель');usb.clicked.connect(window.connect_usb);side.addWidget(usb)
        window.device_list=QComboBox();window.device_list.setPlaceholderText('Выберите телефон');side.addWidget(window.device_list)
        connect=QPushButton('Подключить');connect.setObjectName('primary');connect.clicked.connect(window.connect_device);side.addWidget(connect)
        window.device_cards=QWidget();window.device_cards.setObjectName("deviceCards");window.device_cards.setAttribute(Qt.WidgetAttribute.WA_StyledBackground);window.device_cards_layout=QVBoxLayout(window.device_cards);window.device_cards_layout.setContentsMargins(0,8,0,8);window.device_cards_layout.setAlignment(Qt.AlignmentFlag.AlignTop)
        devices_scroll=QScrollArea();devices_scroll.setWidgetResizable(True);devices_scroll.setWidget(window.device_cards);side.addWidget(devices_scroll,1)
        window.hint=QLabel('Одна локальная сеть.\nВключите камеру на телефоне.');window.hint.setWordWrap(True);window.hint.setObjectName('muted');side.addWidget(window.hint)
        window.devices_button.toggled.connect(window.sidebar.setVisible);self.split.addWidget(window.sidebar)
        center=QWidget();middle=QVBoxLayout(center);middle.setContentsMargins(0,0,0,0)
        toolbar=QHBoxLayout();self.title=QLabel('Мониторинг');self.title.setStyleSheet('font-size:18px;font-weight:600');toolbar.addWidget(self.title);toolbar.addStretch()
        window.filter=QComboBox();window.filter.addItem('Все группы');window.filter.currentTextChanged.connect(window.rebuild);toolbar.addWidget(window.filter)
        window.columns=QComboBox();window.columns.addItems(['1 колонка','2 колонки','3 колонки']);window.columns.setCurrentIndex(1);window.columns.currentIndexChanged.connect(window.rebuild);toolbar.addWidget(window.columns);middle.addLayout(toolbar)
        self.pages=QStackedWidget();middle.addWidget(self.pages,1)
        monitor=QScrollArea();monitor.setWidgetResizable(True)
        window.grid_widget=QWidget();window.grid_widget.setObjectName('cameraGrid');window.grid=QGridLayout(window.grid_widget);window.grid.setContentsMargins(0,0,0,0);window.grid.setSpacing(10);window.grid.setAlignment(Qt.AlignmentFlag.AlignTop);monitor.setWidget(window.grid_widget);self.pages.addWidget(monitor)
        window.empty=QLabel('Подключите первую камеру\n\nВключите Camera Deck на телефоне, затем нажмите «Найти».\nПосле первого сопряжения доверенный телефон подключится без PIN.');window.empty.setWordWrap(True);window.empty.setAlignment(Qt.AlignmentFlag.AlignCenter);window.empty.setMinimumHeight(320);window.empty.setObjectName('emptyMonitor');window.grid.addWidget(window.empty,0,0)
        prod=QWidget();layout=QVBoxLayout(prod);layout.setContentsMargins(0,0,0,0)
        row=QHBoxLayout();self.group_start=QPushButton('● Записать группу');self.group_start.setObjectName('recordAction');self.group_stop=QPushButton('■ Остановить группу');row.addWidget(self.group_start);row.addWidget(self.group_stop);row.addStretch();layout.addLayout(row)
        calibrate=QPushButton('Оценить расхождение часов');calibrate.clicked.connect(self.calibrate);row.addWidget(calibrate)
        note=QLabel('Команды отправляются выбранной группе. Подтверждение — отдельно от каждой камеры.\nОценка часов не синхронизирует экспозицию и не гарантирует совпадение кадров.');note.setObjectName('muted');note.setWordWrap(True);layout.addWidget(note)
        self.table=QTableWidget(0,6);self.table.setHorizontalHeaderLabels(['Камера','Режим','Состояние','Длительность','Накопитель','Последняя операция']);self.table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers);self.table.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Stretch);self.table.verticalHeader().hide();layout.addWidget(self.table,1)
        self.timeline=QPlainTextEdit();self.timeline.setReadOnly(True);self.timeline.setMaximumBlockCount(200);self.timeline.setMaximumHeight(140);layout.addWidget(self.timeline);self.pages.addWidget(prod)
        from media_browser import MediaBrowser
        self.media=MediaBrowser(window);self.pages.addWidget(self.media)
        self.split.addWidget(center)
        self.inspector=QScrollArea();self.inspector.setWidgetResizable(True);self.inspector.setMinimumWidth(260);self.inspector.setMaximumWidth(340)
        panel=QWidget();panel.setObjectName('surface');form=QVBoxLayout(panel);form.setContentsMargins(16,16,16,16);self.selection=QComboBox();self.selection.currentIndexChanged.connect(self.select_index);form.addWidget(QLabel('ВЫБРАННАЯ КАМЕРА'));form.addWidget(self.selection)
        self.status=QLabel('Подключите камеру');self.status.setWordWrap(True);self.status.setObjectName('muted');form.addWidget(self.status)
        self.readouts={}
        for key,label in [('iso','ISO'),('shutter','Выдержка'),('focus','Фокус')]:
            button=QPushButton(label);button.clicked.connect(lambda checked=False,k=key:self.sensor(k));self.readouts[key]=button;form.addWidget(button)
        for title,method in [('Качество и формат','settings'),('Зум и композиция','framing_settings'),('Скачать материалы','media'),('На весь экран','open_fullscreen')]:
            button=QPushButton(title);button.clicked.connect(lambda checked=False,m=method:self.invoke(m));form.addWidget(button)
        self.mode=QComboBox();self.mode.addItem('Фото','photo');self.mode.addItem('Видео','video');self.mode.activated.connect(lambda _:self.selected and self.selected.command('capture_config',mode=self.mode.currentData()));form.addWidget(self.mode)
        actions=QHBoxLayout();self.photo=QPushButton('Снять фото');self.photo.clicked.connect(lambda:self.selected and self.selected.command('photo'));self.record=QPushButton('● Запись');self.record.setObjectName('recordAction');self.record.clicked.connect(lambda:self.selected and self.selected.command('record_stop' if self.selected.status.get('recording') else 'record_start'));actions.addWidget(self.photo);actions.addWidget(self.record);form.addLayout(actions)
        self.record_pc=QPushButton('Запись оригинала на ПК');self.record_pc.setObjectName('recordAction');self.record_pc.clicked.connect(self.pc_record);form.addWidget(self.record_pc)
        pc_note=QLabel('Исходные видео и звук. Резервная копия остаётся на телефоне.');pc_note.setObjectName('muted');pc_note.setWordWrap(True);form.addWidget(pc_note)
        self.pc_status=QLabel('');self.pc_status.setWordWrap(True);form.addWidget(self.pc_status)
        self.recording_event.connect(self.pc_event)
        form.addWidget(QLabel('ИНСТРУМЕНТЫ ПРОСМОТРА'))
        self.tool=QComboBox()
        for title,key in [('Обычный вид','none'),('Гистограмма','histogram'),('Waveform','waveform'),('Zebra ≥235/255','zebra'),('False color','false')]:self.tool.addItem(title,key)
        self.tool.setToolTip('Приблизительный анализ 8-bit предпросмотра. Не измерение RAW, HDR или экспозиции сенсора.');self.tool.activated.connect(self.monitor_tools);form.addWidget(self.tool)
        self.grid_lines=Toggle('Сетка третей');self.grid_lines.toggled.connect(self.monitor_tools);form.addWidget(self.grid_lines)
        self.safe_area=Toggle('Безопасная область 90%');self.safe_area.toggled.connect(self.monitor_tools);form.addWidget(self.safe_area)
        self.group=QLineEdit();self.group.setPlaceholderText('Группа камеры');self.group.editingFinished.connect(self.change_group);form.addWidget(self.group)
        self.view=Toggle('Предпросмотр');self.view.toggled.connect(lambda value:self.selected and self.selected.view_toggle.setChecked(value));form.addWidget(self.view)
        self.audio=Toggle('Слушать микрофон');self.audio.toggled.connect(lambda value:self.selected and self.selected.listen.setChecked(value));form.addWidget(self.audio)
        image=QPushButton('Яркость / цвет / фон');image.clicked.connect(lambda:self.invoke('image_settings'));form.addWidget(image)
        audio_route=QPushButton('Источник / выход звука');audio_route.clicked.connect(lambda:self.selected and self.selected.audio_settings());form.addWidget(audio_route)
        rotate=QPushButton('Повернуть 90°');rotate.clicked.connect(lambda:self.selected and self.selected.rotate_picture());form.addWidget(rotate)
        self.lock=Toggle('Блокировка поворота');self.lock.toggled.connect(lambda value:self.selected and self.selected.lock_rotation(value));form.addWidget(self.lock)
        form.addStretch();self.inspector.setWidget(panel);self.split.addWidget(self.inspector);self.inspector.hide();self.split.setStretchFactor(1,1);self.split.setSizes([230,900,280])
        footer=QHBoxLayout();window.auto=Toggle('Автообновления');window.auto.setChecked(window.load_preferences().get('autoUpdates',True));window.auto.toggled.connect(window.save_preferences);footer.addWidget(window.auto)
        import updates
        window.update_label=QLabel('Camera Deck · '+updates.RELEASE_NAME);window.update_label.setObjectName('muted');footer.addWidget(window.update_label);footer.addStretch()
        check=QPushButton('Проверить обновления');check.clicked.connect(lambda:window.check_update(True));footer.addWidget(check)
        window.install_button=QPushButton('Обновить Windows');window.install_button.setEnabled(False);window.install_button.clicked.connect(window.install_windows);footer.addWidget(window.install_button);outer.addLayout(footer)
        self.group_start.clicked.connect(lambda:self.operate(True));self.group_stop.clicked.connect(lambda:self.operate(False));self.operation.connect(self.operation_result);self.results={}
        self.timer=QTimer(self);self.timer.timeout.connect(self.refresh);self.timer.start(1000)
        self.saved_selection=window.load_preferences().get('selectedCamera','')
        self.navigate(0)

    def navigate(self,index):
        if index!=3:
            self.media.player.pause()
            if self.workspace==3:self.media.epoch+=1
        self.workspace=index;self.pages.setCurrentIndex(0 if index<2 else index-1);self.inspector.setVisible(index==1)
        self.title.setText(('Мониторинг','Управление камерой','Съёмочная группа','Материалы')[index])
        for i,b in enumerate(self.nav):b.setChecked(i==index)
        if index==3:self.media.set_camera(self.selected)
        if hasattr(self.window,'studio'):self.window.rebuild()

    def invoke(self,method):
        if self.selected:
            if method=='media':self.navigate(3)
            else:getattr(self.selected,method)()

    def sensor(self,key):
        if self.selected:self.selected.quick_sensor(key,getattr(self.selected,{'iso':'iso_readout','shutter':'shutter_readout','focus':'focus_readout'}[key]))

    def select(self,tile):
        self.selected=tile;self.saved_selection=getattr(tile.client,"fingerprint",tile.client.host);self.refresh()
        if self.workspace==3:self.media.set_camera(tile)

    def select_index(self,index):
        tile=self.selection.itemData(index)
        if tile is not None:self.select(tile)

    def change_group(self):
        if self.selected:self.selected.group.setText(self.group.text().strip() or 'Общая');self.window.rebuild()

    def refresh(self):
        tiles=self.window.tiles
        if self.selected not in tiles:self.selected=next((t for t in tiles if getattr(t.client,"fingerprint",t.client.host)==self.saved_selection),tiles[0] if tiles else None)
        if self.workspace==3 and self.media.camera is not self.selected:self.media.set_camera(self.selected)
        self.selection.blockSignals(True)
        entries=[(tile.name.text(),tile) for tile in tiles]
        if entries!=[(self.selection.itemText(i),self.selection.itemData(i)) for i in range(self.selection.count())]:
            self.selection.clear()
            for name,tile in entries:self.selection.addItem(name,tile)
        if self.selected:self.selection.setCurrentIndex(tiles.index(self.selected))
        self.selection.blockSignals(False)
        s=self.selected.status if self.selected else {}
        self.status.setText(('Запись идёт' if s.get('recording') else 'Готово' if s.get('ready') else 'Ожидаем состояние')+('\n'+s['error'] if s.get('error') else ''))
        if s.get('trackingAvailable') is False:self.status.setText(self.status.text()+'\nВ этом режиме записи анализ лица и автозум недоступны')
        if s.get('recording'):
            self.status.setText(self.status.text()+('\nЗвук: аудиодорожка активна' if s.get('audioRecordingActive') else '\nЗвук: ожидаем дорожку' if s.get('audio') else '\nЗапись без звука'))
        if s.get('micCapturing') and s.get('micLevelDb') is not None:self.status.setText(self.status.text()+f"\nМикрофон: {s['micLevelDb']:.1f} dBFS")
        if s.get('thermalStatus',-1)>=3:self.status.setText(self.status.text()+'\nСистема сообщает ограничения из-за нагрева')
        self.mode.blockSignals(True);self.mode.setCurrentIndex(1 if s.get('mode')=='video' else 0);self.mode.blockSignals(False);self.mode.setEnabled(bool(s.get('ready')) and not s.get('recording'))
        for key,button in self.readouts.items():
            value={'iso':s.get('iso') or '—','shutter':f"{s.get('exposureNs',0)/1e6:.2f} мс",'focus':'Авто' if s.get('focus',-1)<0 else s['focus']}[key]
            button.setText({'iso':'ISO','shutter':'Выдержка','focus':'Фокус'}[key]+'   '+str(value));button.setEnabled(bool(s.get('ready')))
        self.photo.setVisible(s.get("mode")=="photo");self.record.setVisible(s.get("mode")=="video")
        self.record.setText('■ Стоп' if s.get('recording') else '● Запись');self.record.setEnabled(bool(s.get('ready')) and s.get('mode')=='video');self.photo.setEnabled(bool(s.get('ready')) and not s.get('recording') and s.get('mode')=='photo')
        job=self.pc_jobs.get(id(self.selected),{})
        active=job.get('state') in ('starting','recording','recovering')
        self.record_pc.setText('■ Остановить запись на ПК' if active else 'Запись оригинала на ПК')
        self.record_pc.setEnabled(bool(s.get('ready')) and s.get('mode')=='video' and bool(s.get('originalRecording')) and (not s.get('recording') or active))
        if active and (not s.get('recording') or self.selected.failures):self.record_pc.setEnabled(False)
        if active and job.get('sessionId') and s.get('recordingActiveSessionId')!=job['sessionId']:self.record_pc.setEnabled(False)
        self.pc_status.setText(job.get('message','Телефон должен поддерживать протокол оригинальной записи (Android 10+).') if self.selected else '')
        if active and not s.get('recording'):self.record.setEnabled(False)
        if self.selected:
            for control,value in [(self.grid_lines,self.selected.video.grid_lines),(self.safe_area,self.selected.video.safe_area)]:control.blockSignals(True);control.setChecked(value);control.blockSignals(False)
            self.tool.blockSignals(True);self.tool.setCurrentIndex(max(0,self.tool.findData(self.selected.video.tool)));self.tool.blockSignals(False)
            if not self.group.hasFocus():self.group.setText(self.selected.group.text())
            for control,value in [(self.view,self.selected.view_toggle.isChecked()),(self.audio,self.selected.listen.isChecked()),(self.lock,self.selected.rotation.locked_base is not None)]:control.blockSignals(True);control.setChecked(value);control.blockSignals(False)
        for tile in tiles:
            label=getattr(self.window,'device_state_labels',{}).get((tile.client.host,getattr(tile.client,'port',39812)))
            if label:label.setText(('Нет связи' if tile.failures else '● Запись' if tile.status.get('recording') else 'Подключён')+' · '+('USB' if getattr(tile.client,'transport','lan')=='usb' else tile.client.host))
        self.table.setRowCount(len(tiles))
        for row,tile in enumerate(tiles):
            s=tile.status;elapsed=int(s.get('recordingElapsedMs',0)/1000);free=s.get('storageFreeBytes',-1)
            state='Нет связи' if tile.failures else 'Запись' if s.get('recording') else 'Готово' if s.get('ready') else 'Ожидание'
            values=[tile.name.text(),s.get('mode','—'),state,f'{elapsed//60:02d}:{elapsed%60:02d}',f'{free/1e9:.1f} GB' if free>=0 else 'Неизвестно',self.results.get(id(tile),'—')]
            for col,value in enumerate(values):self.table.setItem(row,col,QTableWidgetItem(value))
        self.group_start.setEnabled(bool(tiles) and not self.busy);self.group_stop.setEnabled(bool(tiles) and not self.busy)

    def operate(self,recording):
        if self.busy:return
        targets=[t for t in self.window.tiles if self.window.filter.currentText()=='Все группы' or t.group.text()==self.window.filter.currentText()]
        if not targets:return
        self.busy=True;self.refresh()
        self.timeline.appendPlainText(datetime.now().strftime('%H:%M:%S')+' · '+('Запуск' if recording else 'Остановка')+f' · камер: {len(targets)}')
        def work():production.operate([(id(t),t.client) for t in targets],recording,self.operation.emit)
        def done(_):self.busy=False;self.refresh()
        self.window.run(work,done,lambda message:(done(None),self.window.message('Группа камер',message)))

    def operation_result(self,result):
        identity,ok,message,state=result;tile=next((t for t in self.window.tiles if id(t)==identity),None)
        self.results[identity]=message
        if tile:
            if state:tile.show_status(state)
            else:tile.failures+=1
            self.timeline.appendPlainText(datetime.now().strftime('%H:%M:%S')+' · '+tile.name.text()+' · '+('✓ ' if ok else 'Ошибка: ')+message)
        self.refresh()

    def pc_record(self):
        if self.selected is None:return
        from PyQt6.QtWidgets import QFileDialog
        from pathlib import Path
        from recording import receive_original
        tile=self.selected;identity=id(tile);client=tile.client;job=self.pc_jobs.get(identity,{})
        if job.get('state') in ('starting','recording','recovering'):
            if tile.failures:return
            if job.get('sessionId') and tile.status.get('recordingActiveSessionId')!=job['sessionId']:return
            self.window.run(lambda:client.command('record_stop'));return
        path,_=QFileDialog.getSaveFileName(self.window,'Запись оригинала на ПК','','Видео MP4 (*.mp4)')
        if not path:return
        destination=Path(path)
        if destination.suffix.lower()!='.mp4':destination=destination.with_suffix('.mp4')
        self.pc_jobs[identity]={'state':'starting','message':'Подключаем поток оригинала. Ждём подтверждения кодера.'};self.refresh()
        def notify(state,data):self.recording_event.emit((identity,state,data))
        self.window.run(lambda:receive_original(client,destination,notify),lambda _:None,lambda message:self.pc_event((identity,'error',{'message':message})))

    def pc_event(self,event):
        identity,state,data=event
        message={'starting':'Телефон принял команду. Ожидаем дорожки оригинала.','recording':f"Пишем на ПК · {data.get('bytes',0)/1048576:.1f} МБ · резерв на телефоне",'recovering':data.get('message','Восстанавливаем резерв с телефона'),'complete':('Резерв восстановлен: ' if data.get('backupRecovered') else 'Готово: ')+data.get('path',''),'error':'Запись на ПК прервана. Проверьте резерв на телефоне. '+data.get('message','')}[state]
        self.pc_jobs[identity]={**self.pc_jobs.get(identity,{}),**data,'state':state,'message':message};self.refresh()

    def monitor_tools(self,*_):
        if self.selected:
            values=(self.tool.currentData(),self.grid_lines.isChecked(),self.safe_area.isChecked());self.selected.video.set_tools(*values)
            if self.selected.fullscreen is not None:self.selected.full_view.set_tools(*values)

    def calibrate(self):
        targets=[(t.name.text(),t.client) for t in self.window.tiles]
        def work():
            result=[]
            for name,client in targets:
                try:
                    estimate=production.estimate_clock(client)
                    result.append(f"{name}: смещение {estimate['offsetNs']/1e6:+.2f} мс, неопределённость оценки ±{estimate['uncertaintyNs']/1e6:.2f} мс")
                except Exception:result.append(name+': оценка часов недоступна (проверьте связь и версию телефона)')
            return result
        self.window.run(work,lambda rows:[self.timeline.appendPlainText(row) for row in rows])
