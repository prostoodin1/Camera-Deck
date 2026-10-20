import io,json,struct,sys,threading
from pathlib import Path
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'desktop'))
import live_video


def active():
    event=threading.Event();event.set();return event


def wire(payload=b'AVC',rotation=90,key=1,metadata=None):
    data=json.dumps(metadata or {'faces':[]}).encode()
    return live_video.HEADER.pack(len(payload),12345,rotation,key,len(data))+data+payload


def test_avc_packets_are_bounded_and_keep_rotation_and_metadata():
    g=live_video.packets(io.BytesIO(wire()),threading.Event(),active())
    assert next(g)==(b'AVC',12345,90,True,{'faces':[]})


@pytest.mark.parametrize('payload',[
    live_video.HEADER.pack(2_000_001,0,0,1,2),
    live_video.HEADER.pack(10,0,45,1,2),
    live_video.HEADER.pack(10,0,0,2,2),
    live_video.HEADER.pack(10,0,0,1,16385),
    wire()[:-2],wire()[:8],
])
def test_invalid_avc_is_rejected_before_decode(payload):
    with pytest.raises(ValueError):next(live_video.packets(io.BytesIO(payload),threading.Event(),active()))


def test_actual_avc_encoder_decoder_round_trip_closes_pinned_connection():
    import av
    encoder=av.CodecContext.create('libx264','w');encoder.width=64;encoder.height=48;encoder.pix_fmt='yuv420p'
    from fractions import Fraction
    encoder.time_base=Fraction(1,30);encoder.options={'preset':'ultrafast','tune':'zerolatency','x264-params':'keyint=1:bframes=0'}
    frame=av.VideoFrame(64,48,'yuv420p')
    for plane in frame.planes:plane.update(bytes([100])*plane.buffer_size)
    encoded=encoder.encode(frame)+encoder.encode(None);payload=b''.join(bytes(p) for p in encoded)
    class Response(io.BytesIO):
        status=200
        def getheader(self,*args):return 'application/x-cameradeck-avc'
    class Socket:
        def settimeout(self,n):pass
    class Connection:
        sock=Socket();closed=False
        def request(self,method,path,headers):assert path=='/video' and headers=={'Authorization':'Bearer session'}
        def getresponse(self):return Response(wire(payload,rotation=270))
        def close(self):self.closed=True
    connection=Connection()
    class Client:
        token='session'
        def connection(self):return connection
    client=Client();g=live_video.frames(client,threading.Event(),active());image,rotation=next(g)
    assert image.width()==64 and image.height()==48 and rotation==270
    assert client.live_tracking=={'faces':[]}
    g.close();assert connection.closed


def test_unauthorized_avc_never_sends_media_or_falls_back():
    class Connection:
        class Socket:
            def settimeout(self,n):pass
        sock=Socket();closed=False
        def request(self,*a,**kw):pass
        def getresponse(self):return type('Response',(),{'status':401})()
        def close(self):self.closed=True
    conn=Connection();client=type('Client',(),{'token':'x','connection':lambda self:conn})()
    with pytest.raises(ConnectionError) as error:next(live_video.frames(client,threading.Event(),active()))
    from protocol import StreamUnavailable
    assert not isinstance(error.value,StreamUnavailable) and conn.closed
