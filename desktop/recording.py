"""Mux original encoder packets, without decoding or re-encoding. Phone keeps backup."""
import base64
from fractions import Fraction
import json
import io
from pathlib import Path
import shutil
import struct
import time
import av

MAX_PACKET=8_000_000


def exact(source,size):
    data=bytearray()
    while len(data)<size:
        chunk=source.read(size-len(data))
        if not chunk:raise ConnectionError('Поток оригинала оборвался. Копия на телефоне может продолжать записываться.')
        data.extend(chunk)
    return bytes(data)


def message(source):
    size=struct.unpack('!I',exact(source,4))[0]
    if not 1<=size<=MAX_PACKET:raise ValueError('Недопустимый пакет записи')
    data=exact(source,size);return data[0],data[1:]


class OriginalMuxer:
    def __init__(self,path,formats):
        self.path=Path(path);self.output=av.open(str(path),'w',format='mp4',options={'movflags':'frag_keyframe+empty_moov+delay_moov+default_base_moof'})
        self.template=av.open(io.BytesIO(),'w',format='null')
        self.streams={};self.last={};self.counts={};self.total=0;self.closed=False
        try:
            for track,key in enumerate(('video','audio')):
                f=formats.get(key)
                if f is None:continue
                mime=f.get('mime');codec={'video/avc':'h264','video/hevc':'hevc','audio/mp4a-latm':'aac'}.get(mime)
                if codec is None or (track==0 and codec=='aac') or (track==1 and codec!='aac'):raise ValueError('Неподдерживаемый кодек оригинала')
                config=base64.b64decode(f['csd'],validate=True)
                if not 0<len(config)<65536:raise ValueError('Недопустимая конфигурация кодека')
                if track==0:
                    w,h=int(f['width']),int(f['height']);rate=int(f.get('frame-rate',30))
                    if not 0<w<=16384 or not 0<h<=16384 or not 0<rate<=1000:raise ValueError('Неверный размер/FPS оригинала')
                    template=self.template.add_stream(codec,rate=rate);template.width=w;template.height=h;template.time_base=Fraction(1,1_000_000)
                    stream=self.output.add_stream_from_template(template,opaque=True)
                    stream.codec_context.extradata=config
                    angle=int(formats.get('rotation',0))
                    if angle not in (0,90,180,270):raise ValueError('Недопустимый поворот')
                    stream.set_display_rotation(angle)
                    self.video_duration=round(1_000_000/rate)
                else:
                    rate=int(f['sample-rate']);channels=int(f['channel-count'])
                    if rate!=48000 or channels!=1:raise ValueError('Неподдерживаемый звук оригинала')
                    template=self.template.add_stream(codec,rate=rate);template.layout='mono';template.time_base=Fraction(1,1_000_000)
                    stream=self.output.add_stream_from_template(template,opaque=True)
                    stream.codec_context.extradata=config
                    self.audio_duration=round(1024*1_000_000/rate)
                stream.time_base=Fraction(1,1_000_000);self.streams[track]=stream;self.counts[track]=0
            if 0 not in self.streams:raise ValueError('Нет видеодорожки')
        except Exception:self.close();raise

    def sample(self,data):
        if len(data)<14:raise ValueError('Пустой пакет оригинала')
        track,pts,flags=struct.unpack('!BqI',data[:13])
        if track not in self.streams or pts<0 or pts<=self.last.get(track,-1):raise ValueError('Нарушен порядок временных меток')
        packet=av.Packet(data[13:]);packet.stream=self.streams[track];packet.pts=packet.dts=pts;packet.time_base=Fraction(1,1_000_000);packet.duration=self.audio_duration if track else self.video_duration;packet.is_keyframe=bool(flags&1)
        self.output.mux(packet);self.last[track]=pts;self.counts[track]+=1;self.total+=len(data)-13

    def close(self):
        if not self.closed:
            self.closed=True
            try:self.output.close()
            finally:self.template.close()

    def complete(self):return all(self.counts.get(track,0)>0 for track in self.streams)


def receive_original(client,destination,notify):
    """Runs in a worker. Failure never sends an automatic stop to the phone."""
    destination=Path(destination)
    if destination.exists():raise FileExistsError('Файл уже существует')
    partial=destination.with_suffix('.partial.mp4');journal=destination.with_suffix('.recording.json')
    if partial.exists() or journal.exists():raise FileExistsError('Рядом уже есть незавершённая запись')
    if shutil.disk_usage(destination.parent).free<256_000_000:raise OSError('Для начала записи на ПК нужно минимум 256 МБ свободного места')
    conn=client.connection();mux=None;started=False;last_check=time.monotonic()
    try:
        conn.sock.settimeout(15)
        conn.request('GET','/recording',headers={'Authorization':'Bearer '+client.token})
        response=conn.getresponse()
        if response.status!=200:raise ConnectionError('Телефон не готов к записи на ПК или требует обновления: HTTP '+str(response.status))
        if response.getheader('Content-Type','').split(';')[0]!='application/x-cameradeck-recording':raise ValueError('Неверный формат оригинального потока')
        kind,hello=message(response)
        if kind!=0 or json.loads(hello).get('protocol')!=1:raise ValueError('Несовместимый протокол оригинала')
        journal.write_text(json.dumps({'state':'starting','phoneBackup':True,'target':destination.name}),encoding='utf-8')
        client.command('record_start');started=True;notify('starting',{'phoneBackup':True})
        while True:
            kind,data=message(response)
            if kind==1:
                if mux is not None:raise ValueError('Повторная конфигурация дорожек')
                formats=json.loads(data);mux=OriginalMuxer(partial,formats);journal.write_text(json.dumps({'state':'recording','formats':formats,'phoneBackup':True}),encoding='utf-8')
            elif kind==2:
                if mux is None:raise ValueError('Данные получены до конфигурации')
                mux.sample(data)
                if time.monotonic()-last_check>1:
                    if shutil.disk_usage(destination.parent).free<64_000_000:raise OSError('На ПК заканчивается место. Резервная запись остаётся на телефоне.')
                    last_check=time.monotonic();notify('recording',{'bytes':mux.total,'videoPackets':mux.counts.get(0,0)})
            elif kind==3:
                end=json.loads(data)
                if end.get('error'):raise RuntimeError(end['error'])
                if mux is None or not mux.complete():raise ValueError('Не получены все дорожки записи')
                mux.close();partial.rename(destination);journal.unlink();notify('complete',{'path':str(destination),'bytes':destination.stat().st_size});return destination
            else:raise ValueError('Неизвестный пакет оригинала')
    except Exception as exc:
        if journal.exists():journal.write_text(json.dumps({'state':'interrupted','phoneBackup':True,'error':str(exc),'partial':partial.name}),encoding='utf-8')
        raise
    finally:
        if mux is not None:mux.close()
        conn.close()


def recover_partial(source,destination):
    """Remux readable fragments to a new MP4; never overwrite/delete the source."""
    source=Path(source);destination=Path(destination)
    temporary=destination.with_suffix('.recovering.mp4')
    if source.resolve()==destination.resolve() or destination.exists() or temporary.exists():raise FileExistsError('Выберите новое имя для восстановленного файла')
    count=0
    with av.open(str(source)) as original,av.open(str(temporary),'w',format='mp4') as output:
        streams={s.index:output.add_stream_from_template(s,opaque=True) for s in original.streams if s.type in ('video','audio')}
        if not any(s.type=='video' for s in original.streams):raise ValueError('В файле нет читаемой видеодорожки')
        for packet in original.demux():
            if packet.size==0 or packet.dts is None or packet.stream.index not in streams:continue
            packet.stream=streams[packet.stream.index];output.mux(packet);count+=1
    if count==0:raise ValueError('В файле нет завершённых фрагментов для восстановления')
    temporary.rename(destination);return destination
