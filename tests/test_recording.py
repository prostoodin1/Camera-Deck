import base64
from fractions import Fraction
import io
from pathlib import Path
import struct
import sys
import av
import pytest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'desktop'))
from recording import OriginalMuxer,message


def make_recording(tmp_path):
    video=av.CodecContext.create('libx264','w');video.width=64;video.height=48;video.pix_fmt='yuv420p';video.time_base=Fraction(1,30);video.framerate=Fraction(30);video.options={'preset':'ultrafast','tune':'zerolatency'};video.flags|=1<<22;video.open()
    audio=av.CodecContext.create('aac','w');audio.sample_rate=48000;audio.layout='mono';audio.format='fltp';audio.time_base=Fraction(1,48000);audio.flags|=1<<22;audio.open()
    packets=[]
    for i in range(30):
        frame=av.VideoFrame(64,48,'yuv420p');frame.pts=i
        for plane in frame.planes:plane.update(bytes([128])*plane.buffer_size)
        packets.extend((0,p) for p in video.encode(frame))
    packets.extend((0,p) for p in video.encode(None))
    for i in range(48):
        frame=av.AudioFrame(format='fltp',layout='mono',samples=1024);frame.sample_rate=48000;frame.pts=i*1024
        for plane in frame.planes:plane.update(bytes(plane.buffer_size))
        packets.extend((1,p) for p in audio.encode(frame))
    packets.extend((1,p) for p in audio.encode(None))
    formats={'rotation':90,'video':{'mime':'video/avc','width':64,'height':48,'frame-rate':30,'csd':base64.b64encode(video.extradata).decode()},'audio':{'mime':'audio/mp4a-latm','sample-rate':48000,'channel-count':1,'csd':base64.b64encode(audio.extradata).decode()}}
    output=tmp_path/'original.mp4';mux=OriginalMuxer(output,formats)
    assert all(not stream.codec_context.is_open for stream in mux.streams.values())
    packets.sort(key=lambda pair:float(pair[1].pts*pair[1].time_base))
    last={}
    for track,p in packets:
        pts=max(last.get(track,-1)+1,round(float(p.pts*p.time_base)*1e6)+25000);last[track]=pts
        mux.sample(struct.pack('!BqI',track,pts,int(p.is_keyframe))+bytes(p))
    assert mux.complete()
    assert all(not stream.codec_context.is_open for stream in mux.streams.values())
    mux.close()
    with av.open(str(output)) as container:
        assert len(container.streams.video)==len(container.streams.audio)==1
        assert container.streams.video[0].width==64
        assert len(list(container.decode(video=0)))==30
    with av.open(str(output)) as container:
        assert sum(f.samples for f in container.decode(audio=0))>47000
    return output


def test_original_h264_aac_muxes_without_transcoding(tmp_path):
    make_recording(tmp_path)


def test_recovery_preserves_source_and_finalizes_new_copy(tmp_path):
    from recording import recover_partial
    source=make_recording(tmp_path);original=source.read_bytes();destination=tmp_path/'recovered.mp4'
    recover_partial(source,destination)
    assert source.read_bytes()==original
    with av.open(str(destination)) as media:assert len(list(media.decode(video=0)))==30
    with pytest.raises(FileExistsError):recover_partial(source,destination)


@pytest.mark.parametrize('payload',[b'',b'\x00\x00\x00\x00',struct.pack('!I',8_000_001),struct.pack('!I',100)+b'abc'])
def test_original_rejects_truncated_and_oversized_messages(payload):
    with pytest.raises((ConnectionError,ValueError)):message(io.BytesIO(payload))
