"""Camera Deck's minimal glass theme and viewfinder."""
from PyQt6.QtCore import QPointF, Qt,QRectF,QSize,QPoint,QTimer
from PyQt6.QtGui import QColor,QPainter,QPen,QFont,QLinearGradient,QPainterPath
from PyQt6.QtWidgets import QLabel,QSizePolicy,QDialog,QCheckBox,QGraphicsBlurEffect,QWidget,QVBoxLayout,QSlider,QPushButton
import math


class DropdownButton(QPushButton):
    def paintEvent(self,event):
        super().paintEvent(event)
        p=QPainter(self);p.setRenderHint(QPainter.RenderHint.Antialiasing);p.setPen(QPen(QColor("#ffd76b"),1.5));x=self.width()-13;y=self.height()/2
        p.drawLine(int(x-3),int(y-2),int(x),int(y+1));p.drawLine(int(x),int(y+1),int(x+3),int(y-2));p.end()


class PrecisionSlider(QSlider):
    """A small native slider with a ruler; no timer, capture, blur or extra video pass."""
    def __init__(self,orientation):
        super().__init__(orientation);self.setMinimumHeight(36);self.setSingleStep(50);self.setPageStep(500)
    def paintEvent(self,event):
        p=QPainter(self);p.setRenderHint(QPainter.RenderHint.Antialiasing);p.setOpacity(1 if self.isEnabled() else .35)
        left=12.;width=max(1,self.width()-24.);y=self.height()/2+3;fraction=(self.value()-self.minimum())/max(1,self.maximum()-self.minimum())
        p.setPen(QPen(QColor('#758397'),1))
        for i in range(33):
            x=left+width*i/32;p.drawLine(QPointF(x,y-11),QPointF(x,y-(18 if i%4==0 else 14)))
        p.setPen(Qt.PenStyle.NoPen);p.setBrush(QColor('#4a5869'));p.drawRoundedRect(QRectF(left,y-2,width,4),2,2)
        p.setBrush(QColor('#f1d578'));p.drawRoundedRect(QRectF(left,y-2,width*fraction,4),2,2)
        x=left+width*fraction;gradient=QLinearGradient(x,y-9,x,y+9);gradient.setColorAt(0,QColor('#fff0b9'));gradient.setColorAt(1,QColor('#cfad52'));p.setBrush(gradient);p.setPen(QPen(QColor('#fce9a3'),1));p.drawEllipse(QPointF(x,y),8,8);p.end()
    def position_value(self,event):
        fraction=max(0,min(1,(event.position().x()-12)/max(1,self.width()-24)));self.setValue(round(self.minimum()+fraction*(self.maximum()-self.minimum())))
    def mousePressEvent(self,event):
        if event.button()==Qt.MouseButton.LeftButton:self.setFocus();self.setSliderDown(True);self.position_value(event);event.accept()
        else:super().mousePressEvent(event)
    def mouseMoveEvent(self,event):
        if self.isSliderDown():self.position_value(event);event.accept()
        else:super().mouseMoveEvent(event)
    def mouseReleaseEvent(self,event):
        if self.isSliderDown():self.position_value(event);self.setSliderDown(False);event.accept()
        else:super().mouseReleaseEvent(event)


class SliderField(QWidget):
    def __init__(self,minimum,maximum,value,decimals=0,logarithmic=False):
        super().__init__();self.minimum=minimum;self.maximum=max(minimum,maximum);self.decimals=decimals;self.logarithmic=logarithmic;self.exact=None;self.setting_value=False
        layout=QVBoxLayout(self);layout.setContentsMargins(0,0,0,0);self.label=QLabel();self.label.setStyleSheet("color:#ffd76b;font-size:15px")
        self.slider=PrecisionSlider(Qt.Orientation.Horizontal);self.slider.setRange(0,10000);layout.addWidget(self.label);layout.addWidget(self.slider)
        if logarithmic and self.maximum>minimum and minimum>0:fraction=math.log(max(minimum,value)/minimum)/math.log(self.maximum/minimum)
        else:fraction=(value-minimum)/(self.maximum-minimum) if self.maximum>minimum else 0
        self.slider.setValue(round(10000*min(1,max(0,fraction))));self.exact=max(minimum,min(self.maximum,value));self.slider.valueChanged.connect(self.changed);self.refresh()
    def changed(self):
        if not self.setting_value:self.exact=None
        self.refresh()
    def set_value(self,value):
        value=max(self.minimum,min(self.maximum,value));span=self.maximum-self.minimum
        fraction=math.log(value/self.minimum)/math.log(self.maximum/self.minimum) if self.logarithmic and self.minimum>0 and span>0 else (value-self.minimum)/span if span>0 else 0
        self.setting_value=True;self.slider.setValue(round(fraction*10000));self.exact=value;self.setting_value=False;self.refresh()
    def value(self):
        f=self.slider.value()/10000
        v=self.exact if self.exact is not None else self.minimum*(self.maximum/self.minimum)**f if self.logarithmic and self.minimum>0 else self.minimum+(self.maximum-self.minimum)*f
        return round(v,self.decimals) if self.decimals else round(v)
    def refresh(self):self.label.setText(f"{self.value():.{self.decimals}f}")


class GlassSheet(QDialog):
    """Layered glass material, drawn once per paint without sampling the live viewfinder."""
    def __init__(self,parent=None):
        super().__init__(parent)
        self.setWindowFlags(Qt.WindowType.Dialog|Qt.WindowType.FramelessWindowHint)
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.back=QLabel(self);self.back.setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents)
        self.back.hide()
    def showEvent(self,event):
        super().showEvent(event)
    def paintEvent(self,event):
        p=QPainter(self);p.setRenderHint(QPainter.RenderHint.Antialiasing)
        rect=QRectF(self.rect()).adjusted(1,1,-1,-1);gradient=QLinearGradient(rect.topLeft(),rect.bottomRight())
        gradient.setColorAt(0,QColor('#303b49'));gradient.setColorAt(.28,QColor('#1c2632'));gradient.setColorAt(1,QColor('#141c25'))
        p.setBrush(gradient);p.setPen(QPen(QColor('#53606f'),1));p.drawRoundedRect(rect,25,25)
        edge=QLinearGradient(rect.topLeft(),rect.topRight());edge.setColorAt(0,QColor(255,255,255,8));edge.setColorAt(.5,QColor(255,255,255,85));edge.setColorAt(1,QColor(255,255,255,8));p.setPen(QPen(edge,1));p.drawLine(QPointF(24,2),QPointF(self.width()-24,2));p.end()


class Toggle(QCheckBox):
    def sizeHint(self):return QSize(self.fontMetrics().horizontalAdvance(self.text())+60,30)
    def hitButton(self,position):return self.rect().contains(position)
    def paintEvent(self,event):
        p=QPainter(self);p.setRenderHint(QPainter.RenderHint.Antialiasing)
        p.setPen(Qt.PenStyle.NoPen);p.setBrush(QColor("#ffd76b" if self.isChecked() else "#424242"));p.drawRoundedRect(QRectF(0,5,36,20),10,10)
        p.setBrush(QColor("#171717" if self.isChecked() else "#bcbcbc"));p.drawEllipse(QRectF(19 if self.isChecked() else 3,8,14,14))
        p.setPen(QColor("#e5e5e5" if self.isEnabled() else "#777777"));p.drawText(self.rect().adjusted(46,0,0,0),Qt.AlignmentFlag.AlignVCenter,self.text());p.end()

class ShutterButton(QPushButton):
    def __init__(self,photo=True):
        super().__init__();self.photo=photo;self.recording=False;self.setObjectName('shutter');self.setFixedSize(78,78)
        self.setAccessibleName('Снять фото' if photo else 'Начать запись');self.setToolTip(self.accessibleName())
    def set_recording(self,value):
        if self.recording!=value:self.recording=value;self.update()
        self.setAccessibleName('Остановить запись' if value else 'Снять фото' if self.photo else 'Начать запись');self.setToolTip(self.accessibleName())
    def paintEvent(self,event):
        p=QPainter(self);p.setRenderHint(QPainter.RenderHint.Antialiasing);r=QRectF(self.rect()).adjusted(4,4,-4,-4)
        p.setPen(QPen(QColor('#d3dbe6') if self.isEnabled() else QColor('#5b6571'),2));p.setBrush(Qt.BrushStyle.NoBrush);p.drawEllipse(r)
        color=QColor('#f0f3f7') if self.photo else QColor('#ff625e');color.setAlpha(255 if self.isEnabled() else 80);p.setPen(Qt.PenStyle.NoPen);p.setBrush(color)
        if self.recording:p.drawRoundedRect(QRectF(25,25,28,28),7,7)
        else:p.drawEllipse(r.adjusted(6,6,-6,-6))
        p.end()


THEME="""
QMainWindow,QDialog,QWidget#root,QWidget#cameraGrid {background:#080808;color:#f4f4f4}
QWidget {font-family:'Segoe UI';font-size:13px;color:#dddddd}
QLabel {background:transparent}
QPushButton {background:#191919;border:1px solid #3c3c3c;border-radius:4px;padding:9px 15px;min-height:16px}
QPushButton:hover {background:#2b2b2b;border-color:#ffe39b;color:#ffe39b}
QPushButton:pressed {background:#ffd76b;color:#080808}
QPushButton:disabled {color:#585858;background:#101010;border-color:#242424}
QPushButton#primary {background:#ffd76b;color:#101010;border-color:#ffd76b;font-weight:600}
QPushButton#primary:disabled {background:#101010;color:#585858;border-color:#242424}
QPushButton#readout {border-radius:4px;color:#ffd76b;background:#151515;font-family:'Consolas';font-size:13px;border:1px solid #3c3c3c;padding:7px 8px}
QPushButton#readout:hover {border-color:#ffd76b;background:#292929}
QLineEdit,QComboBox,QSpinBox,QDoubleSpinBox {background:#151515;color:#ffd76b;border:1px solid #3c3c3c;border-radius:4px;padding:5px 8px;min-height:17px;selection-background-color:#ffd76b;selection-color:#000000}
QLineEdit#cameraName {color:#f2f2f2;border:0;background:transparent;font-weight:600}
QComboBox QAbstractItemView {padding:8px;border:1px solid #424242;border-radius:4px;background:#151515;color:#eeeeee;selection-background-color:#ffd76b;selection-color:#080808}
QComboBox::drop-down {border:0;width:20px}
QWidget#tile {background:#111111;border:1px solid #343434;border-radius:4px}
QScrollArea {border:0;background:#080808}
QScrollBar:vertical {background:#151515;width:8px} QScrollBar::handle:vertical {border-radius:4px;background:#505050;min-height:30px}
QCheckBox {spacing:6px;padding:2px;color:#ababab}
QCheckBox::indicator {width:12px;height:12px;border:1px solid #666666;background:#151515}
QCheckBox::indicator:checked {background:#ffd76b;border-color:#ffd76b}
QCheckBox:checked {color:#ffd76b} QCheckBox:disabled {color:#555555}
QTabWidget::pane,QStackedWidget,QWidget#settingsPage {background:#111111;border:0}
QTabBar::tab {background:#191919;color:#999999;padding:9px 17px;border-bottom:2px solid #2c2c2c}
QTabBar::tab:selected {background:#29251c;color:#ffd76b;border-bottom:2px solid #ffd76b}
QToolTip {background:#202020;color:#ffd76b;border:1px solid #505050}
QSlider::groove:horizontal {height:4px;background:#3a3a3a;border-radius:2px}
QSlider::sub-page:horizontal {background:#ffd76b;border-radius:2px}
QSlider::handle:horizontal {width:16px;height:16px;margin:-6px 0;background:#f5f5f5;border:1px solid #999999;border-radius:8px}
QSlider::sub-page:horizontal:disabled {background:#555555}
QSlider::handle:horizontal:disabled {background:#777777;border-color:#777777}
"""


# Shared role palette matches CameraDesign on Android.
THEME += """
QMainWindow,QDialog,QWidget#root,QWidget#cameraGrid {background:#080a0c;color:#eef0f3}
QWidget {font-family:'Segoe UI';font-size:12px;color:#eef0f3}
QFrame#surface,QWidget#surface,QWidget#tile {background:#15181c;border:1px solid #30353c;border-radius:8px}
QPushButton {background:#1b2026;border:1px solid #30353c;border-radius:6px;padding:7px 11px;min-height:18px;color:#d7dbe1}
QPushButton:hover {background:#2a3038;color:#ffffff;border-color:#626b77}
QPushButton#navigation {background:transparent;border:0;color:#969da7;padding:8px 14px}
QPushButton#navigation:checked {background:#252b33;color:#ffd76b}
QPushButton#recordAction {color:#ff8e8e;background:#291c21;border-color:#674049}
QPushButton#primary {background:#ffd76b;color:#080a0c;border:0}
QLabel#muted {color:#969da7}
QLabel#emptyMonitor {background:#0d1014;color:#969da7;border:1px dashed #30353c;border-radius:8px;padding:30px;font-size:15px}
QMenuBar,QMenu {background:#101318;color:#d7dbe1;border:0}
QMenuBar::item:selected,QMenu::item:selected {background:#30353c}
QLineEdit,QComboBox,QSpinBox,QDoubleSpinBox {background:#101318;color:#eef0f3;border:1px solid #30353c;border-radius:6px;padding:6px 8px}
QTableWidget,QListWidget,QPlainTextEdit {background:#101318;color:#d7dbe1;border:1px solid #30353c;border-radius:6px;selection-background-color:#353d48;gridline-color:#252b33}
QHeaderView::section {background:#1b2026;color:#969da7;border:0;padding:9px}
QListWidget::item {padding:10px;border-bottom:1px solid #252b33}
QProgressBar {background:#101318;border:0;border-radius:3px;max-height:6px;color:transparent}
QProgressBar::chunk {background:#ffd76b;border-radius:3px}
QSplitter::handle {background:#080a0c;width:10px}
QWidget#deviceCards {background:#15181c}
"""


def shutter_label(nanoseconds):
    seconds=nanoseconds/1e9
    if seconds<=0:return "—"
    return f"1/{round(1/seconds)}" if seconds<1 else f'{seconds:.1f}"'


class Viewfinder(QLabel):
    def __init__(self):
        super().__init__()
        self.frame_rotation=0;self.on_focus=None;self.image_target=None
        from face_motion import FaceMotion
        self.face_motion=FaceMotion();self.face_timer=QTimer(self);self.face_timer.setInterval(16);self.face_timer.timeout.connect(self.animate_faces)
        self.tool='none';self.grid_lines=False;self.safe_area=False;self.analysis=None
        from monitor_tools import MonitorAnalyzer
        self.analyzer=MonitorAnalyzer(self);self.analyzer.ready.connect(self.analysis_ready)
        self.soft_background=False;self.backdrop=None;self.frame=None;self.telemetry={};self.message="ОЖИДАНИЕ ИЗОБРАЖЕНИЯ"
        self.setMinimumSize(240,200)
        self.setSizePolicy(QSizePolicy.Policy.Expanding,QSizePolicy.Policy.Expanding)

    def mousePressEvent(self,event):
        target=self.image_target
        if target is None or self.on_focus is None or not target.contains(event.position()):return super().mousePressEvent(event)
        x=(event.position().x()-target.left())/target.width();y=(event.position().y()-target.top())/target.height()
        if self.frame_rotation==90:x,y=y,1-x
        elif self.frame_rotation==180:x,y=1-x,1-y
        elif self.frame_rotation==270:x,y=1-y,x
        self.on_focus(x,y)

    def sizeHint(self):return QSize(640,360)

    def setText(self,text):
        self.frame=None;self.message=text;self.update()

    def set_tools(self,tool='none',grid=False,safe=False):
        if tool!=self.tool:self.analyzer.invalidate();self.analysis=None
        self.tool=tool;self.grid_lines=grid;self.safe_area=safe
        if self.frame is not None and tool!='none':self.analyzer.offer(self.frame,tool)
        self.update()

    def analysis_ready(self,result):
        if result['epoch']==self.analyzer.epoch and result['mode']==self.tool:self.analysis=result;self.update()

    def set_frame(self,image):
        self.frame=image
        if self.tool!='none':self.analyzer.offer(image,self.tool)
        # A low-resolution, smoothly enlarged frame forms a soft photographic backdrop.
        self.backdrop=image.scaled(24,14,Qt.AspectRatioMode.KeepAspectRatio,Qt.TransformationMode.SmoothTransformation) if self.soft_background else None
        self.update()

    def animate_faces(self):
        if not self.face_motion.sample() or not self.isVisible():self.face_timer.stop()
        self.update()

    def hideEvent(self,event):self.face_timer.stop();super().hideEvent(event)
    def showEvent(self,event):
        super().showEvent(event)
        if self.face_motion.tracks:self.face_timer.start()
    def set_status(self,status):
        self.telemetry=status
        if status.get('tracking') is False:self.face_motion.clear()
        else:
            self.face_motion.expiry=.85 if 'trackingStamp' in status else 1.5
            self.face_motion.observe(status.get('faces',[]),status.get('trackingStamp'))
        if self.face_motion.tracks and self.isVisible():self.face_timer.start()
        self.update()

    def paintEvent(self,event):
        painter=QPainter(self);painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        painter.fillRect(self.rect(),QColor("#030303"))
        area=QRectF(self.rect())
        if self.frame is not None and not self.frame.isNull():
            size=self.frame.size();size.scale(self.size(),Qt.AspectRatioMode.KeepAspectRatio)
            target=QRectF((self.width()-size.width())/2,(self.height()-size.height())/2,size.width(),size.height())
            painter.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform)
            if self.backdrop is not None:
                painter.drawImage(area,self.backdrop)
                painter.fillRect(self.rect(),QColor(8,14,24,170))
            painter.drawImage(target,self.frame);self.image_target=target
            if self.analysis and 'overlay' in self.analysis:painter.drawImage(target,self.analysis['overlay'])
            painter.setPen(QPen(QColor(255,255,255,95),1))
            if self.grid_lines:
                for fraction in (1/3,2/3):
                    painter.drawLine(QPointF(target.left()+target.width()*fraction,target.top()),QPointF(target.left()+target.width()*fraction,target.bottom()))
                    painter.drawLine(QPointF(target.left(),target.top()+target.height()*fraction),QPointF(target.right(),target.top()+target.height()*fraction))
            if self.safe_area:painter.drawRect(target.adjusted(target.width()*.05,target.height()*.05,-target.width()*.05,-target.height()*.05))
            if self.analysis and self.tool in ('histogram','waveform'):
                scope=QRectF(target.left()+12,target.bottom()-110,180,80);painter.fillRect(scope,QColor(8,10,12,220))
                if self.tool=='waveform':painter.drawImage(scope,self.analysis['waveform'])
                else:
                    counts=self.analysis['histogram'];peak=max(counts) or 1;painter.setPen(QPen(QColor('#b3becb'),1))
                    for i,count in enumerate(counts):painter.drawLine(QPointF(scope.left()+i*scope.width()/256,scope.bottom()),QPointF(scope.left()+i*scope.width()/256,scope.bottom()-count/peak*scope.height()))
            if self.tool!='none':
                painter.fillRect(QRectF(target.left()+8,target.top()+30,270,22),QColor(8,10,12,210))
                painter.setPen(QColor('#c5cbd3'));painter.setFont(QFont('Segoe UI',8));painter.drawText(int(target.left()+12),int(target.top()+45),'Анализ 8-bit предпросмотра · '+self.tool)
            s=self.telemetry
            def position(x,y):
                if self.frame_rotation==90:x,y=1-y,x
                elif self.frame_rotation==180:x,y=1-x,1-y
                elif self.frame_rotation==270:x,y=y,1-x
                return QPointF(target.left()+max(0,min(1,x))*target.width(),target.top()+max(0,min(1,y))*target.height())
            painter.save();painter.setClipRect(target)
            for face in self.face_motion.sample():
                selected=s.get('faceFocus',True) and s.get('focus',-1)<0 and face['id']==s.get('activeFaceId',-1)
                painter.setOpacity(face['alpha']*(1 if selected else .62))
                color='#84e6a4' if selected and s.get('afState') in (2,4) else '#ffd24a' if selected else '#b2b4b8'
                painter.setPen(QPen(QColor(color),1.8 if selected else 1.1))
                l,t,r,b=face['box'];rect=QRectF(position(l,t),position(r,b)).normalized()
                if s.get('trackingShape')=='oval':painter.drawEllipse(rect)
                else:
                    length=min(18,rect.width()*.22,rect.height()*.22)
                    for x,sx in ((rect.left(),1),(rect.right(),-1)):
                        for y,sy in ((rect.top(),1),(rect.bottom(),-1)):
                            painter.drawLine(QPointF(x,y),QPointF(x+sx*length,y));painter.drawLine(QPointF(x,y),QPointF(x,y+sy*length))
                for eye in face['eyes']:painter.drawEllipse(position(*eye),6 if selected else 4,6 if selected else 4)
            painter.setOpacity(1)
            if s.get('focusPoint') and not s.get('faceFocus',True):
                try:
                    p=position(*s['focusPoint']);painter.setPen(QPen(QColor('#84e6a4' if s.get('afState') in (2,4) else '#ffd24a'),1.5));painter.drawRoundedRect(QRectF(p.x()-10,p.y()-10,20,20),3,3)
                except (ValueError,TypeError):pass
            painter.restore()
        else:
            painter.setPen(QColor("#767676"));painter.setFont(QFont("Segoe UI",10))
            painter.drawText(self.rect().adjusted(24,30,-24,-30),Qt.AlignmentFlag.AlignCenter|Qt.TextFlag.TextWordWrap,self.message)
        s=self.telemetry
        # HUD contains only reported state, never fabricated meters or battery estimates.
        painter.fillRect(0,0,self.width(),29,QColor(8,8,8,180));painter.fillRect(0,self.height()-30,self.width(),30,QColor(8,8,8,180))
        painter.setFont(QFont("Segoe UI",10,QFont.Weight.DemiBold))
        painter.setPen(QColor("#ffd76b"));mode="ВИДЕО" if s.get("mode")=="video" else "ФОТО"
        painter.drawText(11,19,mode)
        right="● ЗАПИСЬ" if s.get("recording") else "ГОТОВО"
        painter.setPen(QColor("#ff5a52") if s.get("recording") else QColor("#dedede"))
        painter.drawText(self.width()-105,19,right)
        painter.setPen(QColor("#d7dbe1"));painter.drawText(11,self.height()-10,f"ISO {s.get('iso') or '—'}   {shutter_label(s.get('exposureNs',0))}   {'AF' if s.get('focus',-1)<0 else 'MF'}")
        painter.setPen(QColor("#a0a0a0"))
        quality=f"{s.get('viewFps',0)} fps"
        painter.drawText(self.width()-90,self.height()-10,quality)
        painter.setPen(QPen(QColor(255,255,255,45),1))
        for x,y,dx,dy in ((12,42,1,1),(self.width()-12,42,-1,1),(12,self.height()-43,1,-1),(self.width()-12,self.height()-43,-1,-1)):
            painter.drawLine(x,y,x+dx*12,y);painter.drawLine(x,y,x,y+dy*12)
        painter.end()

# Compact camera-console roles, deliberately opaque for fast popup rendering.
THEME += """
QMainWindow,QDialog,QWidget#root,QWidget#cameraGrid {background:#070707;color:#eeeeee}
QFrame#surface,QWidget#surface,QWidget#tile {background:#111111;border:1px solid #303030;border-radius:4px}
QPushButton {background:#171717;border:1px solid #363636;border-radius:4px;padding:5px 9px;min-height:18px}
QPushButton:hover {background:#252525;border-color:#ffd24a;color:#ffd24a}
QPushButton#readout {background:#101010;border:1px solid #393939;border-radius:3px;color:#ffd24a;font-family:Consolas;font-size:13px;padding:6px 8px}
QPushButton#primary {background:#ffd24a;color:#070707;border:1px solid #ffd24a}
QPushButton#navigation:checked {background:#171717;color:#ffd24a;border-bottom:2px solid #ffd24a}
QLineEdit,QComboBox,QSpinBox,QDoubleSpinBox {background:#101010;border:1px solid #363636;border-radius:3px;color:#ffd24a}
QMenuBar,QMenu {background:#101010;color:#eeeeee}
QTabBar::tab:selected {background:#202020;color:#ffd24a;border-bottom:2px solid #ffd24a}
"""

THEME += "QWidget#deviceCards {background:#111111} QSplitter::handle {background:#070707} QLabel#emptyMonitor {background:#101010} QScrollArea {background:#070707}"

# Glass Camera: a quiet content layer, rounded functional surfaces and restrained gold.
THEME += """
QMainWindow,QWidget#root,QWidget#cameraGrid {background:#090c10;color:#f4f5f7}
QDialog {background:transparent;color:#f4f5f7}
QWidget {font-family:'Segoe UI';font-size:13px;color:#e9edf3}
QWidget#tile {background:#10161e;border:1px solid #313d4a;border-radius:19px}
QFrame#surface,QWidget#surface {background:qlineargradient(x1:0,y1:0,x2:1,y2:1,stop:0 #222d3a,stop:1 #151c25);border:1px solid #3a4654;border-radius:22px}
QPushButton {background:qlineargradient(x1:0,y1:0,x2:1,y2:1,stop:0 #2b3745,stop:1 #1d2733);border:1px solid #44505f;border-radius:17px;padding:9px 13px;min-height:19px;color:#e8ecf2}
QPushButton:hover {background:#374657;border-color:#7c8999;color:#ffffff}
QPushButton:pressed {background:#495767}
QPushButton:disabled {background:#1a2028;color:#727d8b;border-color:#303945}
QPushButton#primary {background:#f1d578;color:#111820;border:1px solid #f1d578;border-radius:18px;font-weight:600}
QPushButton#navigation {border:0;background:transparent;border-radius:17px;padding:9px 15px;color:#b6bfcb}
QPushButton#navigation:checked {background:#303b48;border:0;color:#f1d578}
QPushButton#readout {background:#202c39;border:1px solid #4a5665;border-radius:16px;color:#f1d578;padding:10px 12px;font-family:Consolas;font-size:14px}
QPushButton#cameraChoice {background:#19222d;border:1px solid #394656;border-radius:15px;text-align:left;padding:9px 12px;min-height:44px}
QPushButton#cameraChoice:checked {border-color:#f1d578;color:#f1d578;background:#27313b}
QPushButton#sensorChip {background:#273443;border:1px solid #4a586a;border-radius:14px;color:#dce4ee;padding:8px 5px;min-height:20px}
QPushButton#sensorChip:checked {background:#f1d578;border-color:#f1d578;color:#111820}
QPushButton#sheetClose {background:#35414e;border:0;border-radius:16px;min-width:32px;max-width:32px;min-height:32px;max-height:32px;padding:0;color:#e8edf4;font-size:18px}
QPushButton#shutter {background:transparent;border:0;border-radius:39px;min-width:78px;max-width:78px;min-height:78px;max-height:78px;padding:0}
QLabel#sensorHero {font-size:48px;font-weight:300;color:#f1d578;padding:9px 0;font-family:'Segoe UI Light','Segoe UI'}
QLabel#sheetTitle {font-size:19px;font-weight:600;color:#f4f5f7}
QLabel#eyebrow {font-size:11px;letter-spacing:2px;color:#c0c9d5}
QLabel#muted {color:#b0bac8}
QLineEdit,QComboBox,QSpinBox,QDoubleSpinBox {background:#16202c;border:1px solid #3e4b5c;border-radius:13px;color:#e9edf3;padding:8px 10px}
QScrollArea {background:transparent;border:0} QWidget#deviceCards {background:transparent}
QSlider::groove:horizontal {height:5px;background:#475565;border:0;border-radius:2px}
QSlider::sub-page:horizontal {background:#f1d578;border:0;border-radius:2px}
QSlider::handle:horizontal {width:21px;height:21px;margin:-8px 0;background:#f1d578;border:1px solid #f9e6a6;border-radius:11px}
QMenuBar,QMenu {background:#111821;color:#e9edf3}
QTabBar::tab {background:transparent;color:#b3bfcd;border:0;border-radius:14px;padding:10px 15px}
QTabBar::tab:selected {background:#303d4c;border:0;color:#f1d578}
QWidget#settingsPage,QTabWidget::pane,QStackedWidget {background:transparent;border:0}
QSplitter::handle {background:#090c10;width:14px}
QLabel#emptyMonitor {background:#121b25;color:#b0bdcb;border:1px solid #334252;border-radius:20px}
"""
