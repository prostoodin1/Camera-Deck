"""AVC access units over the existing pinned TLS connection, with no frame queue."""
import struct
import json
import av
from PyQt6.QtGui import QImage
from protocol import StreamUnavailable

HEADER=struct.Struct('!Iqiii')


def packets(response,stopped,enabled):
    while not stopped.is_set() and enabled.is_set():
        header=response.read(HEADER.size)
        if not header:raise StreamUnavailable('Поток H.264 завершён')
        if len(header)!=HEADER.size:raise ValueError('Неполный заголовок H.264')
        size,pts,rotation,flags,meta_size=HEADER.unpack(header)
        if not 0<size<=2_000_000 or rotation not in (0,90,180,270) or flags not in (0,1):raise ValueError('Повреждённый пакет H.264')
        if not 2<=meta_size<=16384:raise ValueError("Недопустимый размер метаданных")
        raw=response.read(meta_size)
        if len(raw)!=meta_size:raise ValueError("Неполные метаданные")
        metadata=json.loads(raw)
        if not isinstance(metadata,dict):raise ValueError("Неверные метаданные")
        payload=response.read(size)
        if len(payload)!=size:raise ValueError('Неполный пакет H.264')
        yield payload,pts,rotation,bool(flags),metadata


def frames(client,stopped,enabled):
    conn=client.connection()
    try:
        conn.sock.settimeout(4)
        conn.request('GET','/video',headers={'Authorization':'Bearer '+client.token})
        response=conn.getresponse()
        if response.status in (404,501):raise StreamUnavailable('Совместимый JPEG-просмотр')
        if response.status!=200:raise ConnectionError(f'H.264: HTTP {response.status}')
        if response.getheader('Content-Type','').split(';')[0]!='application/x-cameradeck-avc':raise ValueError('Неверный формат AVC')
        decoder=None
        for payload,pts,rotation,key,metadata in packets(response,stopped,enabled):
            # Each IDR includes parameter sets and permits immediate recovery after skips.
            if key:
                decoder=av.CodecContext.create('h264','r');decoder.thread_count=1;decoder.flags|=0x80000
            if decoder is None:continue
            client.live_tracking=metadata
            packet=av.Packet(payload);packet.pts=pts
            for frame in decoder.decode(packet):
                rgb=frame.reformat(format='rgb24');plane=rgb.planes[0]
                yield QImage(bytes(plane),rgb.width,rgb.height,plane.line_size,QImage.Format.Format_RGB888).copy(),rotation
    finally:conn.close()
