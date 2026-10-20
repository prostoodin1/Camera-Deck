"""Camera Deck workspaces. Network operations stay in Window.run workers."""
from datetime import datetime
from PyQt6.QtCore import Qt,QTimer,pyqtSignal
from PyQt6.QtWidgets import (QWidget,QVBoxLayout,QHBoxLayout,QLabel,QPushButton,QFrame,
    QComboBox,QLineEdit,QScrollArea,QGridLayout,QStackedWidget,QSplitter,QFormLayout,
    QTableWidget,QTableWidgetItem,QHeaderView,QPlainTextEdit,QAbstractItemView)
from appearance import Toggle
import production


class Studio(QWidget):
    operation=pyqtSignal(object)
    def __init__(self,window):
        super().__init__();self.window=window;self.selected=None;self.workspace=0;self.busy=False
        self.setObjectName('root')
        outer=QVBoxLayout(self);outer.setContentsMargins(16,12,16,12);outer.setSpacing(10)
        top=QHBoxLayout();brand=QLabel('CAMERA DECK');brand.setStyleSheet('font-size:15px;font-weight:600;letter-spacing:2px');top.addWidget(brand);top.addSpacing(24)
        self.nav=[]
        for index,title in enumerate(('Монитор','Камера','Продакшн','Материалы')):
            button=QPushButton(title);button.setCheckable(True);button.setObjectName('navigation');button.clicked.connect(lambda checked=False,i=index:self.navigate(i));top.addWidget(button);self.nav.append(button)
        top.addStretch();window.connection_count=QLabel('Нет камер');top.addWidget(window.connection_count)
        window.devices_button=QPushButton('Устройства');window.devices_button.setCheckable(True);window.devices_button.setChecked(True);top.addWidget(window.devices_button)
        outer.addLayout(top)
        self.split=QSplitter();outer.addWidget(self.split,1)
        window.sidebar=QFrame();window.sidebar.setObjectName('surface');window.sidebar.setMinimumWidth(210);window.sidebar.setMaximumWidth(300)
        side=QVBoxLayout(window.sidebar);side.setContentsMargins(12,14,12,12);side.addWidget(QLabel('УСТРОЙСТВА'))
        actions=QHBoxLayout();window.scan=QPushButton('Найти');window.scan.clicked.connect(window.scan_devices);actions.addWidget(window.scan)
        manual=QPushButton('По IP');manual.clicked.connect(window.manual);actions.addWidget(manual);side.addLayout(actions)
        window.device_list=QComboBox();window.device_list.setPlaceholderText('Выберите телефон');side.addWidget(window.device_list)
        connect=QPushButton('Подключить');connect.setObjectName('primary');connect.clicked.connect(window.connect_device);side.addWidget(connect)
        window.device_cards=QWidget();window.device_cards_layout=QVBoxLayout(window.device_cards);window.device_cards_layout.setContentsMargins(0,8,0,8);window.device_cards_layout.setAlignment(Qt.AlignmentFlag.AlignTop)
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
        note=QLabel('Команды отправляются выбранной группе. Подтверждение — отдельно от каждой камеры.\nОдновременная отправка не означает покадровую синхронизацию.');note.setObjectName('muted');note.setWordWrap(True);layout.addWidget(note)
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
        self.group=QLineEdit();self.group.setPlaceholderText('Группа камеры');self.group.editingFinished.connect(self.change_group);form.addWidget(self.group)
        self.view=Toggle('Предпросмотр');self.view.toggled.connect(lambda value:self.selected and self.selected.view_toggle.setChecked(value));form.addWidget(self.view)
        self.audio=Toggle('Слушать микрофон');self.audio.toggled.connect(lambda value:self.selected and self.selected.listen.setChecked(value));form.addWidget(self.audio)
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
        self.navigate(0)

    def navigate(self,index):
        if index!=3:self.media.player.pause()
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
        self.selected=tile;self.refresh()
        if self.workspace==3:self.media.set_camera(tile)

    def select_index(self,index):
        tile=self.selection.itemData(index)
        if tile is not None:self.select(tile)

    def change_group(self):
        if self.selected:self.selected.group.setText(self.group.text().strip() or 'Общая');self.window.rebuild()

    def refresh(self):
        tiles=self.window.tiles
        if self.selected not in tiles:self.selected=tiles[0] if tiles else None
        self.selection.blockSignals(True);self.selection.clear()
        for tile in tiles:self.selection.addItem(tile.name.text(),tile)
        if self.selected:self.selection.setCurrentIndex(tiles.index(self.selected))
        self.selection.blockSignals(False)
        s=self.selected.status if self.selected else {}
        self.status.setText(('Запись идёт' if s.get('recording') else 'Готово' if s.get('ready') else 'Ожидаем состояние')+('\n'+s['error'] if s.get('error') else ''))
        self.mode.blockSignals(True);self.mode.setCurrentIndex(1 if s.get('mode')=='video' else 0);self.mode.blockSignals(False);self.mode.setEnabled(bool(s.get('ready')) and not s.get('recording'))
        for key,button in self.readouts.items():
            value={'iso':s.get('iso') or '—','shutter':f"{s.get('exposureNs',0)/1e6:.2f} мс",'focus':'Авто' if s.get('focus',-1)<0 else s['focus']}[key]
            button.setText({'iso':'ISO','shutter':'Выдержка','focus':'Фокус'}[key]+'   '+str(value));button.setEnabled(bool(s.get('ready')))
        self.record.setText('■ Стоп' if s.get('recording') else '● Запись');self.record.setEnabled(bool(s.get('ready')) and s.get('mode')=='video');self.photo.setEnabled(bool(s.get('ready')) and not s.get('recording') and s.get('mode')=='photo')
        if self.selected:
            if not self.group.hasFocus():self.group.setText(self.selected.group.text())
            for control,value in [(self.view,self.selected.view_toggle.isChecked()),(self.audio,self.selected.listen.isChecked()),(self.lock,self.selected.rotation.locked_base is not None)]:control.blockSignals(True);control.setChecked(value);control.blockSignals(False)
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
