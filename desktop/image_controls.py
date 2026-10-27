"""Camera-reported sensor controls; effects are saved by Android, not painted over preview."""
from PyQt6.QtWidgets import QVBoxLayout,QWidget,QScrollArea,QFormLayout,QComboBox,QLabel,QDialogButtonBox,QApplication
from appearance import GlassSheet,SliderField,Toggle

def show(tile):
    data=tile.status.get('imageControls')
    if not data:tile.window.message('Изображение','Обновите Android для новых контролов изображения.');return
    dialog=GlassSheet(tile.window);dialog.setWindowTitle('Экспозиция / цвет / фон');dialog.setMinimumWidth(520)
    outer=QVBoxLayout(dialog);scroll=QScrollArea();scroll.setWidgetResizable(True);scroll.setStyleSheet('QScrollArea {background:#080808;border:0;}');page=QWidget();page.setObjectName('root');form=QFormLayout(page);scroll.setWidget(page);outer.addWidget(scroll)
    raw=tile.status.get('mode')=='photo' and tile.status.get('photoFormat')=='dng';manual=bool(tile.status.get('manualIso'))
    ev=SliderField(data.get('evMin',0),data.get('evMax',0),data.get('ev',0),2);ev.setEnabled(not manual and data.get('evMax',0)>data.get('evMin',0));form.addRow('Яркость · EV (AUTO)',ev)
    lock=Toggle('AE-L · удерживать экспозицию');lock.setChecked(data.get('aeLock',False));lock.setEnabled(not manual and data.get('aeLockAvailable',False));form.addRow(lock)
    def choices(label,values,names,selected):
        combo=QComboBox()
        for value in values:combo.addItem(names.get(value,str(value)),value)
        combo.setCurrentIndex(max(0,combo.findData(selected)));combo.setEnabled(bool(values));form.addRow(label,combo);return combo
    meter=choices('Замер света',[0,1,2,3] if data.get('meteringAvailable',False) else [0],{0:'Матричный',1:'По центру',2:'Точка фокуса',3:'Выбранное лицо'},data.get('metering',0))
    wb=choices('Баланс белого',data.get('whiteBalanceModes',[]),{0:'Выкл.',1:'Авто',2:'Лампа накаливания',3:'Флуоресцентный',4:'Тёплый флуоресцентный',5:'Дневной свет',6:'Облачно',7:'Сумерки',8:'Тень'},data.get('whiteBalance',1))
    anti=choices('Антимерцание',data.get('antiBandingModes',[]),{0:'Выкл.',1:'50 Гц',2:'60 Гц',3:'Авто'},data.get('antiBanding',3))
    filters=choices('Фильтр · в сохранённом файле',data.get('filters',[0]),{0:'Без фильтра',1:'Монохром',2:'Негатив',3:'Соляризация',4:'Сепия',5:'Постеризация',6:'Белая доска',7:'Чёрная доска',8:'Аква'},data.get('filter',0));filters.setEnabled(not raw)
    contrast=SliderField(.5,1.8,data.get('contrast',1),2);gamma=SliderField(.6,1.8,data.get('gamma',1),2)
    for control in (contrast,gamma):control.setEnabled(not raw and data.get('toneCurveAvailable',False))
    form.addRow('Контраст',contrast);form.addRow('Гамма · средние тона',gamma)
    apertures=data.get('apertures',[]);aperture=choices('Диафрагма',[0]+apertures,{0:'Автоматически',**{v:f'f/{v:g}' for v in apertures}},data.get('aperture',0));aperture.setEnabled(len(apertures)>1)
    amount=float(data.get('backgroundBlur',0));preset=choices('Размытие · эксперимент',[0,1,2,3],{0:'Выключено',1:'Слабое',2:'Сильное',3:'Вручную'},0)
    preset.setCurrentIndex(0 if amount==0 else 1 if abs(amount-.25)<.001 else 2 if abs(amount-.75)<.001 else 3)
    blur=SliderField(0,100,round(amount*100));form.addRow('Интенсивность',blur)
    def fill_blur():
        index=preset.currentData()
        if index in (0,1,2):blur.slider.setValue({0:0,1:2500,2:7500}[index])
        blur.setEnabled(index==3 and not raw and not tile.status.get('recording'))
    preset.activated.connect(fill_blur);fill_blur();preset.setEnabled(not raw and not tile.status.get('recording'))
    note=QLabel('Фильтр и тональная кривая сохраняются камерой. Размытие создаёт отдельную копию после выключения камеры: JPEG / видео до 1920 px со звуком. Исходник остаётся. Волосы, движение и группы требуют проверки.');note.setWordWrap(True);note.setObjectName('muted');form.addRow(note)
    if manual:form.addRow(QLabel('В ручном режиме яркость задают ISO и выдержка; EV отключён.'))
    buttons=QDialogButtonBox(QDialogButtonBox.StandardButton.Apply|QDialogButtonBox.StandardButton.Close);buttons.button(QDialogButtonBox.StandardButton.Apply).setText('Применить');buttons.button(QDialogButtonBox.StandardButton.Close).setText('Закрыть');buttons.rejected.connect(dialog.reject);outer.addWidget(buttons)
    def apply():
        values=dict(metering=meter.currentData(),filter=filters.currentData(),contrast=contrast.value(),gamma=gamma.value(),backgroundBlur=blur.value()/100)
        if not manual:values.update(ev=ev.value(),aeLock=lock.isChecked())
        if wb.isEnabled():values['whiteBalance']=wb.currentData()
        if anti.isEnabled():values['antiBanding']=anti.currentData()
        if aperture.isEnabled():values['aperture']=aperture.currentData()
        buttons.setEnabled(False)
        tile.window.run(lambda:tile.client.command('image_settings',**values),lambda _:(tile.poll_wake.set(),dialog.accept()),lambda message:(buttons.setEnabled(True),tile.window.message('Изображение',message)))
    buttons.button(QDialogButtonBox.StandardButton.Apply).clicked.connect(apply)
    dialog.resize(560,min(760,QApplication.primaryScreen().availableGeometry().height()-80));dialog.exec()
