"""Preview-only exposure aids. Analysis runs outside the GUI at a bounded rate."""
import threading
import time
from PyQt6.QtCore import QObject,pyqtSignal
from PyQt6.QtGui import QImage


def analyze(image,mode):
    small=image.convertToFormat(QImage.Format.Format_RGB888).scaled(160,90)
    width,height=small.width(),small.height();pitch=small.bytesPerLine();raw=small.constBits().asstring(small.sizeInBytes());hist=[0]*256;overlay=bytearray(width*height*4);wave=bytearray(width*90*4)
    for y in range(height):
        for x in range(width):
            i=y*pitch+x*3;luma=max(0,min(255,round(.2126*raw[i]+.7152*raw[i+1]+.0722*raw[i+2])));hist[luma]+=1
            if mode=='waveform':
                pos=((89-round(luma*89/255))*width+x)*4
                wave[pos:pos+4]=bytes((140,220,180,230))
            elif mode in ('zebra','false'):
                color=(0,0,0,0)
                if mode=='zebra' and luma>=235 and (x+y)%8<4:color=(255,215,107,180)
                if mode=='false':
                    color=(22,20,120,255) if luma<16 else (65,85,200,255) if luma<48 else (90,150,170,255) if luma<96 else (95,180,120,255) if luma<144 else (180,180,180,255) if luma<200 else (240,180,70,255) if luma<235 else (235,65,65,255)
                pos=(y*width+x)*4;overlay[pos:pos+4]=bytes(color)
    result={'histogram':hist,'width':width,'height':height,'mode':mode}
    if mode in ('zebra','false'):result['overlay']=QImage(bytes(overlay),width,height,width*4,QImage.Format.Format_RGBA8888).copy()
    if mode=='waveform':result['waveform']=QImage(bytes(wave),width,90,width*4,QImage.Format.Format_RGBA8888).copy()
    return result


class MonitorAnalyzer(QObject):
    ready=pyqtSignal(object)
    def __init__(self,parent=None):
        super().__init__(parent);self.lock=threading.Lock();self.latest=None;self.running=False;self.closed=False;self.epoch=0
    def offer(self,image,mode):
        with self.lock:
            if self.closed:return
            self.latest=(image,mode,self.epoch)
            if self.running:return
            self.running=True
        threading.Thread(target=self.work,daemon=True,name='preview-scopes').start()
    def work(self):
        while True:
            with self.lock:
                if self.closed or self.latest is None:self.running=False;return
                image,mode,epoch=self.latest;self.latest=None
            started=time.monotonic();result=analyze(image,mode);result['epoch']=epoch;result['ms']=(time.monotonic()-started)*1000
            if not self.closed:self.ready.emit(result)
            time.sleep(max(0,.25-(time.monotonic()-started)))
    def invalidate(self):
        with self.lock:self.epoch+=1;self.latest=None
    def close(self):
        with self.lock:self.closed=True;self.latest=None
