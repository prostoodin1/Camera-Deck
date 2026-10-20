"""Camera Deck's original black/yellow camera-console theme and viewfinder."""
from PyQt6.QtCore import Qt,QRectF,QSize
from PyQt6.QtGui import QColor,QPainter,QPen,QFont
from PyQt6.QtWidgets import QLabel,QSizePolicy

THEME="""
QMainWindow,QDialog,QWidget#root,QWidget#cameraGrid {background:#080808;color:#e5e5e5}
QWidget {font-family:'Segoe UI';font-size:12px;color:#d5d5d5}
QLabel {background:transparent}
QPushButton {background:#171717;border:1px solid #383838;border-radius:2px;padding:6px 10px;min-height:16px}
QPushButton:hover {background:#24221a;border-color:#d8b539;color:#f4cf59}
QPushButton:pressed {background:#f2c94c;color:#090909}
QPushButton:disabled {color:#585858;background:#101010;border-color:#242424}
QPushButton#primary {background:#f2c94c;color:#101010;border-color:#f2c94c;font-weight:600}
QPushButton#primary:disabled {background:#101010;color:#585858;border-color:#242424}
QPushButton#readout {color:#f2c94c;background:#111111;font-family:'Consolas';font-size:13px;border:1px solid #39352a;padding:7px 8px}
QPushButton#readout:hover {border-color:#f2c94c;background:#242016}
QLineEdit,QComboBox,QSpinBox,QDoubleSpinBox {background:#111111;color:#f2c94c;border:1px solid #3a3a3a;border-radius:1px;padding:5px 8px;min-height:17px;selection-background-color:#f2c94c;selection-color:#000000}
QLineEdit#cameraName {color:#f2f2f2;border:0;background:transparent;font-weight:600}
QComboBox QAbstractItemView {background:#111111;color:#eeeeee;selection-background-color:#f2c94c;selection-color:#080808}
QComboBox::drop-down {border:0;width:20px}
QWidget#tile {background:#0d0d0d;border:1px solid #303030;border-radius:2px}
QScrollArea {border:0;background:#080808}
QScrollBar:vertical {background:#111111;width:8px} QScrollBar::handle:vertical {background:#45412f;min-height:30px}
QCheckBox {spacing:6px;padding:2px;color:#ababab}
QCheckBox::indicator {width:12px;height:12px;border:1px solid #666666;background:#111111}
QCheckBox::indicator:checked {background:#f2c94c;border-color:#f2c94c}
QCheckBox:checked {color:#f2c94c} QCheckBox:disabled {color:#555555}
QTabWidget::pane,QStackedWidget,QWidget#settingsPage {background:#0d0d0d;border:0}
QTabBar::tab {background:#171717;color:#999999;padding:9px 17px;border-bottom:2px solid #2c2c2c}
QTabBar::tab:selected {background:#17150f;color:#f2c94c;border-bottom:2px solid #f2c94c}
QToolTip {background:#202020;color:#f2c94c;border:1px solid #65604a}
"""


def shutter_label(nanoseconds):
    seconds=nanoseconds/1e9
    if seconds<=0:return "—"
    return f"1/{round(1/seconds)}" if seconds<1 else f'{seconds:.1f}"'


class Viewfinder(QLabel):
    def __init__(self):
        super().__init__()
        self.frame=None;self.telemetry={};self.message="ОЖИДАНИЕ ИЗОБРАЖЕНИЯ"
        self.setMinimumSize(240,200)
        self.setSizePolicy(QSizePolicy.Policy.Expanding,QSizePolicy.Policy.Expanding)

    def sizeHint(self):return QSize(640,360)

    def setText(self,text):
        self.frame=None;self.message=text;self.update()

    def set_frame(self,image):
        self.frame=image;self.update()

    def set_status(self,status):
        self.telemetry=status;self.update()

    def paintEvent(self,event):
        painter=QPainter(self);painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        painter.fillRect(self.rect(),QColor("#030303"))
        area=QRectF(self.rect())
        if self.frame is not None and not self.frame.isNull():
            size=self.frame.size();size.scale(self.size(),Qt.AspectRatioMode.KeepAspectRatio)
            target=QRectF((self.width()-size.width())/2,(self.height()-size.height())/2,size.width(),size.height())
            painter.drawImage(target,self.frame)
        else:
            painter.setPen(QColor("#767676"));painter.setFont(QFont("Segoe UI",10))
            painter.drawText(self.rect().adjusted(24,30,-24,-30),Qt.AlignmentFlag.AlignCenter|Qt.TextFlag.TextWordWrap,self.message)
        s=self.telemetry
        # HUD contains only reported state, never fabricated meters or battery estimates.
        painter.fillRect(0,0,self.width(),29,QColor(0,0,0,215));painter.fillRect(0,self.height()-30,self.width(),30,QColor(0,0,0,215))
        painter.setFont(QFont("Consolas",10,QFont.Weight.Bold))
        painter.setPen(QColor("#f2c94c"));mode="MOVIE" if s.get("mode")=="video" else "PHOTO"
        painter.drawText(11,19,mode)
        right="● REC" if s.get("recording") else "STBY"
        painter.setPen(QColor("#ff5a52") if s.get("recording") else QColor("#dedede"))
        painter.drawText(self.width()-72,19,right)
        painter.setPen(QColor("#f2c94c"));painter.drawText(11,self.height()-10,f"ISO {s.get('iso') or '—'}   {shutter_label(s.get('exposureNs',0))}   {'AF' if s.get('focus',-1)<0 else 'MF'}")
        painter.setPen(QColor("#a0a0a0"))
        quality=f"VIEW {s.get('previewFps',5)}p"
        painter.drawText(self.width()-90,self.height()-10,quality)
        painter.setPen(QPen(QColor("#f2c94c"),1))
        for x,y,dx,dy in ((12,42,1,1),(self.width()-12,42,-1,1),(12,self.height()-43,1,-1),(self.width()-12,self.height()-43,-1,-1)):
            painter.drawLine(x,y,x+dx*12,y);painter.drawLine(x,y,x,y+dy*12)
        painter.end()
