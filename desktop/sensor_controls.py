"""Capability-bounded sensor sheets; apply once, never send commands while dragging."""
from PyQt6.QtCore import Qt,QPoint
from PyQt6.QtWidgets import QVBoxLayout,QHBoxLayout,QGridLayout,QLabel,QPushButton,QDialogButtonBox,QDialog,QApplication
from appearance import GlassSheet,Toggle,SliderField,shutter_label
import sensor_policy as policy

TITLES={'iso':('ISO','Чувствительность'),'shutter':('S','Выдержка'),'focus':('AF / MF','Фокусировка'),'ev':('EV','Коррекция экспозиции')}

def show(tile,kind,anchor):
    before=dict(tile.status)
    if not before:return
    low,high=policy.bounds(kind,before);allowed=policy.supported(kind,before)
    high=max(low,high)
    title,name=TITLES[kind];dialog=GlassSheet(tile.window);dialog.setWindowTitle(name);dialog.setObjectName('sensorSheet')
    dialog.setMinimumWidth(350);dialog.setMaximumWidth(440)
    layout=QVBoxLayout(dialog);layout.setContentsMargins(24,20,24,20);layout.setSpacing(13)
    head=QHBoxLayout();tag=QLabel(title);tag.setObjectName('eyebrow');head.addWidget(tag);head.addStretch()
    close=QPushButton('×');close.setObjectName('sheetClose');close.setAccessibleName('Закрыть настройку');close.clicked.connect(dialog.reject);head.addWidget(close);layout.addLayout(head)
    label=QLabel(name);label.setObjectName('sheetTitle');layout.addWidget(label)
    camera_name=QLabel(tile.name.text());camera_name.setObjectName('muted');layout.addWidget(camera_name)
    hero=QLabel();hero.setObjectName('sensorHero');hero.setAlignment(Qt.AlignmentFlag.AlignCenter);layout.addWidget(hero)
    auto=Toggle('AF · автофокус' if kind=='focus' else 'AUTO · ISO и выдержка')
    auto.setChecked(before.get('focus',-1)<0 if kind=='focus' else not before.get('manualIso'))
    if kind!='ev':layout.addWidget(auto)
    else:auto.hide()
    auto.setEnabled(allowed)
    value=(before.get('manualIso') or before.get('iso') or low) if kind=='iso' else (before.get('manualExposureNs') or before.get('exposureNs') or low) if kind=='shutter' else before.get('imageControls',{}).get('ev',0) if kind=='ev' else max(0,before.get('focus',-1))
    # The field stores milliseconds only for a shutter, preserving nanosecond commands.
    scale=1e6 if kind=='shutter' else 1
    field=SliderField(low/scale,high/scale,policy.clip(value,low,high)/scale,6 if kind=='shutter' else 2 if kind in ('focus','ev') else 0,kind in ('iso','shutter'))
    field.label.hide();layout.addWidget(field)
    ticks=QHBoxLayout();left=QLabel('Дальше / ∞' if kind=='focus' else shutter_label(low) if kind=='shutter' else str(round(low,2)));right=QLabel('Ближе' if kind=='focus' else shutter_label(high) if kind=='shutter' else str(round(high,2)))
    left.setObjectName('muted');right.setObjectName('muted');ticks.addWidget(left);ticks.addStretch();ticks.addWidget(right);layout.addLayout(ticks)
    grid=QGridLayout();grid.setSpacing(6);chips=[]
    def display(n):return shutter_label(round(n)) if kind=='shutter' else ('∞' if n==0 else f'{n:g}') if kind=='focus' else f'{n:+g}' if kind=='ev' else str(round(n))
    for i,n in enumerate(policy.presets(kind,before)):
        chip=QPushButton(display(n));chip.setObjectName('sensorChip');chip.setCheckable(True);chip.setProperty('sensorValue',n);chip.setAccessibleName(name+' '+display(n));chips.append((chip,n))
        def choose(checked=False,value=n):
            if kind!='ev':auto.setChecked(False)
            field.set_value(value/scale);refresh()
        chip.clicked.connect(choose);grid.addWidget(chip,i//4,i%4)
    layout.addLayout(grid)
    note=QLabel('При открытии: ISO '+str(before.get('iso') or '—')+' · '+shutter_label(before.get('exposureNs',0)) if kind in ('iso','shutter') else 'EV доступна в AUTO. В ручном режиме яркость задают ISO и выдержка.' if kind=='ev' and before.get('manualIso') else 'Шкала объектива в диоптриях: 0 = бесконечность. Это не измерение расстояния.' if kind=='focus' else 'Коррекция яркости по шагу, который сообщает камера.')
    note.setObjectName('muted');note.setWordWrap(True);layout.addWidget(note)
    if not allowed:disabled=QLabel('Ручная настройка недоступна для этого объектива / режима.');disabled.setObjectName('muted');disabled.setWordWrap(True);layout.addWidget(disabled)
    def refresh():
        automatic=kind!='ev' and auto.isChecked();n=field.value()*scale
        hero.setText('AF' if kind=='focus' and automatic else 'AUTO' if automatic else display(n))
        field.setEnabled(allowed and not automatic)
        for chip,value in chips:chip.setEnabled(allowed);chip.setChecked(not automatic and abs(n-value)<max(.01,abs(value)*.015))
    field.slider.valueChanged.connect(refresh);auto.toggled.connect(refresh);refresh()
    buttons=QDialogButtonBox(QDialogButtonBox.StandardButton.Ok|QDialogButtonBox.StandardButton.Cancel)
    buttons.button(QDialogButtonBox.StandardButton.Ok).setText('Применить');buttons.button(QDialogButtonBox.StandardButton.Ok).setObjectName('primary');buttons.button(QDialogButtonBox.StandardButton.Ok).setEnabled(allowed)
    buttons.button(QDialogButtonBox.StandardButton.Cancel).setText('Отмена');buttons.accepted.connect(dialog.accept);buttons.rejected.connect(dialog.reject);layout.addWidget(buttons)
    dialog.adjustSize();point=anchor.mapToGlobal(QPoint(0,anchor.height()+8));screen=QApplication.screenAt(point) or QApplication.primaryScreen();bounds=screen.availableGeometry()
    dialog.move(max(bounds.left(),min(point.x(),bounds.right()-dialog.width())),max(bounds.top(),min(point.y(),bounds.bottom()-dialog.height())))
    if dialog.exec()!=QDialog.DialogCode.Accepted or not allowed:return
    if policy.context(before)!=policy.context(tile.status):tile.window.message('Настройка камеры','Объектив или режим изменился. Откройте настройку заново.');return
    if tile.failures or tile.stopped.is_set():tile.window.message('Настройка камеры','Камера отключена. Дождитесь восстановления связи с телефоном.');return
    try:action,values=policy.command(kind,auto.isChecked(),field.value()*scale,tile.status)
    except ValueError as error:tile.window.message('Настройка камеры',str(error));return
    tile.command(action,**values)
