"""Camera Deck's minimal glass theme and viewfinder."""
from PyQt6.QtCore import QPointF, Qt,QRectF,QSize,QPoint
from PyQt6.QtGui import QColor,QPainter,QPen,QFont
from PyQt6.QtWidgets import QLabel,QSizePolicy,QDialog,QCheckBox,QGraphicsBlurEffect,QWidget,QVBoxLayout,QSlider,QPushButton
import math


class DropdownButton(QPushButton):
    def paintEvent(self,event):
        super().paintEvent(event)
        p=QPainter(self);p.setRenderHint(QPainter.RenderHint.Antialiasing);p.setPen(QPen(QColor("#ffd76b"),1.5));x=self.width()-13;y=self.height()/2
        p.drawLine(int(x-3),int(y-2),int(x),int(y+1));p.drawLine(int(x),int(y+1),int(x+3),int(y-2));p.end()


class SliderField(QWidget):
    def __init__(self,minimum,maximum,value,decimals=0,logarithmic=False):
        super().__init__();self.minimum=minimum;self.maximum=max(minimum,maximum);self.decimals=decimals;self.logarithmic=logarithmic
        layout=QVBoxLayout(self);layout.setContentsMargins(0,0,0,0);self.label=QLabel();self.label.setStyleSheet("color:#ffd76b;font-size:15px")
        self.slider=QSlider(Qt.Orientation.Horizontal);self.slider.setRange(0,10000);layout.addWidget(self.label);layout.addWidget(self.slider)
        if logarithmic and self.maximum>minimum and minimum>0:fraction=math.log(max(minimum,value)/minimum)/math.log(self.maximum/minimum)
        else:fraction=(value-minimum)/(self.maximum-minimum) if self.maximum>minimum else 0
        self.slider.setValue(round(10000*min(1,max(0,fraction))));self.slider.valueChanged.connect(self.refresh);self.refresh()
    def value(self):
        f=self.slider.value()/10000
        v=self.minimum*(self.maximum/self.minimum)**f if self.logarithmic and self.minimum>0 else self.minimum+(self.maximum-self.minimum)*f
        return round(v,self.decimals) if self.decimals else round(v)
    def refresh(self):self.label.setText(f"{self.value():.{self.decimals}f}")


class GlassSheet(QDialog):
    """A frozen, blurred view of the actual desk behind legible popup controls."""
    def __init__(self,parent=None):
        super().__init__(parent)
        self.setWindowFlags(Qt.WindowType.Dialog|Qt.WindowType.FramelessWindowHint)
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.back=QLabel(self);self.back.setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents)
        blur=QGraphicsBlurEffect(self.back);blur.setBlurRadius(28);self.back.setGraphicsEffect(blur)
        self.back.lower()
    def showEvent(self,event):
        parent=self.parentWidget()
        if parent:
            pix=parent.grab();origin=parent.mapFromGlobal(self.mapToGlobal(QPoint()))
            self.back.setPixmap(pix.copy(origin.x(),origin.y(),self.width(),self.height()))
        self.back.setGeometry(self.rect());self.back.lower();super().showEvent(event)
    def paintEvent(self,event):
        p=QPainter(self);p.setRenderHint(QPainter.RenderHint.Antialiasing)
        p.setBrush(QColor(22,22,22,238));p.setPen(QPen(QColor(255,255,255,45),1));p.drawRoundedRect(QRectF(self.rect()).adjusted(1,1,-1,-1),22,22);p.end()


class Toggle(QCheckBox):
    def sizeHint(self):return QSize(self.fontMetrics().horizontalAdvance(self.text())+60,30)
    def hitButton(self,position):return self.rect().contains(position)
    def paintEvent(self,event):
        p=QPainter(self);p.setRenderHint(QPainter.RenderHint.Antialiasing)
        p.setPen(Qt.PenStyle.NoPen);p.setBrush(QColor("#ffd76b" if self.isChecked() else "#424242"));p.drawRoundedRect(QRectF(0,5,36,20),10,10)
        p.setBrush(QColor("#171717" if self.isChecked() else "#bcbcbc"));p.drawEllipse(QRectF(19 if self.isChecked() else 3,8,14,14))
        p.setPen(QColor("#e5e5e5" if self.isEnabled() else "#777777"));p.drawText(self.rect().adjusted(46,0,0,0),Qt.AlignmentFlag.AlignVCenter,self.text());p.end()

THEME="""
QMainWindow,QDialog,QWidget#root,QWidget#cameraGrid {background:#080808;color:#f4f4f4}
QWidget {font-family:'Segoe UI';font-size:13px;color:#dddddd}
QLabel {background:transparent}
QPushButton {background:#191919;border:1px solid #3c3c3c;border-radius:12px;padding:9px 15px;min-height:16px}
QPushButton:hover {background:#2b2b2b;border-color:#ffe39b;color:#ffe39b}
QPushButton:pressed {background:#ffd76b;color:#080808}
QPushButton:disabled {color:#585858;background:#101010;border-color:#242424}
QPushButton#primary {background:#ffd76b;color:#101010;border-color:#ffd76b;font-weight:600}
QPushButton#primary:disabled {background:#101010;color:#585858;border-color:#242424}
QPushButton#readout {border-radius:14px;color:#ffd76b;background:#151515;font-family:'Consolas';font-size:13px;border:1px solid #3c3c3c;padding:7px 8px}
QPushButton#readout:hover {border-color:#ffd76b;background:#292929}
QLineEdit,QComboBox,QSpinBox,QDoubleSpinBox {background:#151515;color:#ffd76b;border:1px solid #3c3c3c;border-radius:9px;padding:5px 8px;min-height:17px;selection-background-color:#ffd76b;selection-color:#000000}
QLineEdit#cameraName {color:#f2f2f2;border:0;background:transparent;font-weight:600}
QComboBox QAbstractItemView {padding:8px;border:1px solid #424242;border-radius:12px;background:#151515;color:#eeeeee;selection-background-color:#ffd76b;selection-color:#080808}
QComboBox::drop-down {border:0;width:20px}
QWidget#tile {background:#111111;border:1px solid #343434;border-radius:12px}
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


def shutter_label(nanoseconds):
    seconds=nanoseconds/1e9
    if seconds<=0:return "—"
    return f"1/{round(1/seconds)}" if seconds<1 else f'{seconds:.1f}"'


class Viewfinder(QLabel):
    def __init__(self):
        super().__init__()
        self.frame_rotation=0;self.on_focus=None;self.image_target=None
        self.backdrop=None;self.frame=None;self.telemetry={};self.message="ОЖИДАНИЕ ИЗОБРАЖЕНИЯ"
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

    def set_frame(self,image):
        self.frame=image
        # A low-resolution, smoothly enlarged frame forms a soft photographic backdrop.
        self.backdrop=image.scaled(24,14,Qt.AspectRatioMode.KeepAspectRatio,Qt.TransformationMode.SmoothTransformation)
        self.update()

    def set_status(self,status):
        self.telemetry=status;self.update()

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
            s=self.telemetry
            def position(x,y):
                if self.frame_rotation==90:x,y=1-y,x
                elif self.frame_rotation==180:x,y=1-x,1-y
                elif self.frame_rotation==270:x,y=y,1-x
                return QPointF(target.left()+max(0,min(1,x))*target.width(),target.top()+max(0,min(1,y))*target.height())
            painter.setPen(QPen(QColor("#84e6a4" if s.get('afState') in (2,4) else "#ffd76b"),1.5))
            try:
                for face in s.get('faces',[])[:20]:
                    a=position(face['left'],face['top']);b=position(face['right'],face['bottom']);painter.drawRoundedRect(QRectF(a,b).normalized(),5,5)
                    for eye in face.get('eyes',[])[:2]:painter.drawEllipse(position(*eye),4,4)
                if s.get('focusPoint'):
                    p=position(*s['focusPoint']);painter.drawRect(QRectF(p.x()-10,p.y()-10,20,20))
            except (KeyError,ValueError,TypeError):pass
        else:
            painter.setPen(QColor("#767676"));painter.setFont(QFont("Segoe UI",10))
            painter.drawText(self.rect().adjusted(24,30,-24,-30),Qt.AlignmentFlag.AlignCenter|Qt.TextFlag.TextWordWrap,self.message)
        s=self.telemetry
        # HUD contains only reported state, never fabricated meters or battery estimates.
        painter.fillRect(0,0,self.width(),29,QColor(8,8,8,180));painter.fillRect(0,self.height()-30,self.width(),30,QColor(8,8,8,180))
        painter.setFont(QFont("Segoe UI",10,QFont.Weight.DemiBold))
        painter.setPen(QColor("#ffd76b"));mode="MOVIE" if s.get("mode")=="video" else "PHOTO"
        painter.drawText(11,19,mode)
        right="● REC" if s.get("recording") else "STBY"
        painter.setPen(QColor("#ff5a52") if s.get("recording") else QColor("#dedede"))
        painter.drawText(self.width()-72,19,right)
        painter.setPen(QColor("#ffd76b"));painter.drawText(11,self.height()-10,f"ISO {s.get('iso') or '—'}   {shutter_label(s.get('exposureNs',0))}   {'AF' if s.get('focus',-1)<0 else 'MF'}")
        painter.setPen(QColor("#a0a0a0"))
        quality=f"{s.get('viewFps',0)} fps"
        painter.drawText(self.width()-90,self.height()-10,quality)
        painter.setPen(QPen(QColor(255,255,255,45),1))
        for x,y,dx,dy in ((12,42,1,1),(self.width()-12,42,-1,1),(12,self.height()-43,1,-1),(self.width()-12,self.height()-43,-1,-1)):
            painter.drawLine(x,y,x+dx*12,y);painter.drawLine(x,y,x,y+dy*12)
        painter.end()
