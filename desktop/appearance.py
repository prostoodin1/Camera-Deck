"""Camera Deck's minimal glass theme and viewfinder."""
from PyQt6.QtCore import Qt,QRectF,QSize
from PyQt6.QtGui import QColor,QPainter,QPen,QFont
from PyQt6.QtWidgets import QLabel,QSizePolicy

THEME="""
QMainWindow,QDialog,QWidget#root,QWidget#cameraGrid {background:#0b1019;color:#edf2fa}
QWidget {font-family:'Segoe UI';font-size:13px;color:#d5deed}
QLabel {background:transparent}
QPushButton {background:#192232;border:1px solid #303e54;border-radius:12px;padding:9px 15px;min-height:16px}
QPushButton:hover {background:#24344b;border-color:#84b9ff;color:#b2d2ff}
QPushButton:pressed {background:#8cbdff;color:#0b1019}
QPushButton:disabled {color:#585858;background:#101010;border-color:#242424}
QPushButton#primary {background:#8cbdff;color:#101010;border-color:#8cbdff;font-weight:600}
QPushButton#primary:disabled {background:#101010;color:#585858;border-color:#242424}
QPushButton#readout {color:#8cbdff;background:#121c2b;font-family:'Consolas';font-size:13px;border:1px solid #303e54;padding:7px 8px}
QPushButton#readout:hover {border-color:#8cbdff;background:#22344c}
QLineEdit,QComboBox,QSpinBox,QDoubleSpinBox {background:#121c2b;color:#8cbdff;border:1px solid #303e54;border-radius:9px;padding:5px 8px;min-height:17px;selection-background-color:#8cbdff;selection-color:#000000}
QLineEdit#cameraName {color:#f2f2f2;border:0;background:transparent;font-weight:600}
QComboBox QAbstractItemView {background:#121c2b;color:#eeeeee;selection-background-color:#8cbdff;selection-color:#0b1019}
QComboBox::drop-down {border:0;width:20px}
QWidget#tile {background:#111b29;border:1px solid #2b3a50;border-radius:12px}
QScrollArea {border:0;background:#0b1019}
QScrollBar:vertical {background:#121c2b;width:8px} QScrollBar::handle:vertical {border-radius:4px;background:#405370;min-height:30px}
QCheckBox {spacing:6px;padding:2px;color:#ababab}
QCheckBox::indicator {width:12px;height:12px;border:1px solid #666666;background:#121c2b}
QCheckBox::indicator:checked {background:#8cbdff;border-color:#8cbdff}
QCheckBox:checked {color:#8cbdff} QCheckBox:disabled {color:#555555}
QTabWidget::pane,QStackedWidget,QWidget#settingsPage {background:#111b29;border:0}
QTabBar::tab {background:#192232;color:#999999;padding:9px 17px;border-bottom:2px solid #2c2c2c}
QTabBar::tab:selected {background:#20334e;color:#8cbdff;border-bottom:2px solid #8cbdff}
QToolTip {background:#202020;color:#8cbdff;border:1px solid #405370}
"""


def shutter_label(nanoseconds):
    seconds=nanoseconds/1e9
    if seconds<=0:return "—"
    return f"1/{round(1/seconds)}" if seconds<1 else f'{seconds:.1f}"'


class Viewfinder(QLabel):
    def __init__(self):
        super().__init__()
        self.backdrop=None;self.frame=None;self.telemetry={};self.message="ОЖИДАНИЕ ИЗОБРАЖЕНИЯ"
        self.setMinimumSize(240,200)
        self.setSizePolicy(QSizePolicy.Policy.Expanding,QSizePolicy.Policy.Expanding)

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
            painter.drawImage(target,self.frame)
        else:
            painter.setPen(QColor("#767676"));painter.setFont(QFont("Segoe UI",10))
            painter.drawText(self.rect().adjusted(24,30,-24,-30),Qt.AlignmentFlag.AlignCenter|Qt.TextFlag.TextWordWrap,self.message)
        s=self.telemetry
        # HUD contains only reported state, never fabricated meters or battery estimates.
        painter.fillRect(0,0,self.width(),29,QColor(12,20,32,180));painter.fillRect(0,self.height()-30,self.width(),30,QColor(12,20,32,180))
        painter.setFont(QFont("Segoe UI",10,QFont.Weight.DemiBold))
        painter.setPen(QColor("#8cbdff"));mode="MOVIE" if s.get("mode")=="video" else "PHOTO"
        painter.drawText(11,19,mode)
        right="● REC" if s.get("recording") else "STBY"
        painter.setPen(QColor("#ff5a52") if s.get("recording") else QColor("#dedede"))
        painter.drawText(self.width()-72,19,right)
        painter.setPen(QColor("#8cbdff"));painter.drawText(11,self.height()-10,f"ISO {s.get('iso') or '—'}   {shutter_label(s.get('exposureNs',0))}   {'AF' if s.get('focus',-1)<0 else 'MF'}")
        painter.setPen(QColor("#a0a0a0"))
        quality=f"VIEW {s.get('previewFps',5)}p"
        painter.drawText(self.width()-90,self.height()-10,quality)
        painter.setPen(QPen(QColor("#8cbdff"),1))
        for x,y,dx,dy in ((12,42,1,1),(self.width()-12,42,-1,1),(12,self.height()-43,1,-1),(self.width()-12,self.height()-43,-1,-1)):
            painter.drawLine(x,y,x+dx*12,y);painter.drawLine(x,y,x,y+dy*12)
        painter.end()
