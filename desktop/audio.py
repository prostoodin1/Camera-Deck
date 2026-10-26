"""Bounded live PCM monitor. It never opens the computer's microphone."""
from __future__ import annotations
import queue
import threading
from PyQt6.QtCore import QObject, QTimer, pyqtSignal
from PyQt6.QtMultimedia import QAudio, QAudioFormat, QAudioSink, QMediaDevices


class AudioMonitor(QObject):
    failed=pyqtSignal(str)
    def __init__(self,client,parent=None):
        super().__init__(parent)
        self.client=client
        self.stop_event=threading.Event()
        self.chunks=queue.Queue(maxsize=5)
        self.sink=None; self.output=None; self.pending=b""
        self.timer=QTimer(self); self.timer.timeout.connect(self.pump)
        self.connection=None
        self.generation=0

    def start(self):
        if self.sink is not None:return
        device=QMediaDevices.defaultAudioOutput()
        if device.isNull():raise RuntimeError("Не найдено устройство воспроизведения звука Windows")
        fmt=QAudioFormat();fmt.setSampleRate(48000);fmt.setChannelCount(1);fmt.setSampleFormat(QAudioFormat.SampleFormat.Int16)
        if not device.isFormatSupported(fmt):raise RuntimeError("Устройство вывода не поддерживает PCM 48 кГц mono / 16 бит")
        self.sink=QAudioSink(device,fmt,self);self.sink.setBufferSize(9600)
        self.output=self.sink.start()
        if self.output is None:self.stop();raise RuntimeError("Не удалось включить воспроизведение звука")
        self.stop_event=threading.Event(); self.generation+=1
        generation=self.generation; event=self.stop_event
        self.timer.start(10)
        threading.Thread(target=self.receive,args=(generation,event),daemon=True,name="camera-audio").start()

    def receive(self,generation,event):
        conn=None
        try:
            conn=self.client.connection()  # certificate verified before token is sent
            if event.is_set():return
            self.connection=conn
            token=self.client.token
            conn.request("GET","/audio",headers={"Authorization":"Bearer "+token})
            response=conn.getresponse()
            if response.status==401 and getattr(self.client,'refresh_secret',''):
                self.client.renew(token)
                raise ConnectionError('Сеанс звука обновлён; переподключаемся')
            if response.status!=200:
                raise ConnectionError("Микрофон телефона недоступен: "+response.read(1024).decode(errors="replace"))
            if response.getheader("X-Audio-Encoding")!="pcm-s16le" or response.getheader("X-Audio-Rate")!="48000" or response.getheader("X-Audio-Channels")!="1":
                raise ValueError("Неподдерживаемый аудиоформат телефона")
            while not event.is_set():
                chunk=response.read(1920)
                if not chunk:raise ConnectionError("Телефон завершил передачу звука")
                if len(chunk)%2:raise ValueError("Повреждённый PCM-поток")
                if event.is_set():return
                try:self.chunks.put_nowait((generation,chunk))
                except queue.Full:
                    try:self.chunks.get_nowait()
                    except queue.Empty:pass
                    self.chunks.put_nowait((generation,chunk))
        except Exception as exc:
            if not event.is_set():self.failed.emit(str(exc))
        finally:
            if conn is not None:conn.close()
            if generation==self.generation:self.connection=None

    def pump(self):
        if self.sink is None or self.output is None:return
        if self.sink.error() not in (QAudio.Error.NoError,QAudio.Error.UnderrunError):
            self.failed.emit('Устройство воспроизведения недоступно');return
        if not self.pending:
            try:
                generation,data=self.chunks.get_nowait()
                if generation==self.generation:self.pending=data
            except queue.Empty:return
        count=min(len(self.pending),self.sink.bytesFree())
        count-=count%2
        if count:
            written=self.output.write(self.pending[:count])
            if written>0:self.pending=self.pending[written:]

    def stop(self):
        self.stop_event.set();self.timer.stop();self.generation+=1
        conn=self.connection
        if conn is not None:
            # close() alone may wait for a blocked read in a different thread.
            try:
                import socket
                if conn.sock:conn.sock.shutdown(socket.SHUT_RDWR)
            except OSError:pass
            conn.close()
        self.connection=None
        if self.sink is not None:self.sink.stop();self.sink.deleteLater()
        self.sink=None;self.output=None;self.pending=b""
        while True:
            try:self.chunks.get_nowait()
            except queue.Empty:break
