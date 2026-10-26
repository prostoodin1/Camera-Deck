import sys
from pathlib import Path
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'desktop'))
from usb_transport import UsbConnections
from protocol import CameraClient

def test_usb_uses_distinct_reusable_forwards_and_only_removes_owned_ports():
    usb=UsbConnections();calls=[];ports=iter(['44001','44002'])
    def call(*args):
        calls.append(args)
        if args==('devices','-l'):return 'List of devices attached\nPHONE_A device model:Pixel_8\nPHONE_B device model:Samsung\n192.168.1.8:5555 device\nemulator-5554 device\n'
        if args==('forward','--list'):return '\n'.join(f'{s} tcp:{p} tcp:39812' for s,p in usb.forwards.items())
        if 'tcp:0' in args:return next(ports)
        return ''
    usb.call=call
    found=usb.discover();assert [d['port'] for d in found]==[44001,44002]
    assert len(usb.discover())==2
    assert sum('tcp:0' in c for c in calls)==2
    usb.close()
    assert ('-s','PHONE_A','forward','--remove','tcp:44001') in calls
    assert ('-s','PHONE_B','forward','--remove','tcp:44002') in calls
    assert not any('--remove-all' in c or 'kill-server' in c for c in calls)
    assert usb.discover()==[]

def test_usb_unauthorized_requires_phone_confirmation():
    usb=UsbConnections();usb.call=lambda *args:'List of devices attached\nPHONE unauthorized\n'
    with pytest.raises(RuntimeError,match='разрешите USB-отладку'):usb.discover()

def test_loopback_is_only_available_for_explicit_usb_transport():
    with pytest.raises(ValueError):CameraClient('127.0.0.1')
    with pytest.raises(ValueError):CameraClient('192.168.1.3',transport='usb')
    with pytest.raises(ValueError):CameraClient('127.0.0.1',transport='usb',port=0)
    client=CameraClient('127.0.0.1',transport='usb',port=44001)
    assert client.port==44001 and client.host=='127.0.0.1' and not client.token

def test_malformed_adb_forward_port_is_not_used():
    usb=UsbConnections();usb.call=lambda *args:'List of devices attached\nPHONE device\n' if args[0]=='devices' else '0'
    with pytest.raises(RuntimeError,match='порт'):usb.discover()
    assert not usb.forwards

def test_unplugged_usb_forward_is_recreated_on_next_connect():
    usb=UsbConnections();usb.forwards['PHONE']=44000
    def call(*args):
        if args[0]=='devices':return 'PHONE device'
        if args==('forward','--list'):return ''
        return '44001'
    usb.call=call
    assert usb.discover()[0]['port']==44001

def test_reconnect_updates_only_matching_usb_phone_without_changing_trust():
    from main import Window
    from types import SimpleNamespace
    import threading
    client=SimpleNamespace(transport='usb',usb_serial='PHONE',port=44000,fingerprint='a'*64,token='test-session')
    tile=SimpleNamespace(client=client,failures=1,poll_wake=threading.Event(),status={'recording':True})
    restored=Window.restore_usb(SimpleNamespace(tiles=[tile]),[{'serial':'OTHER','port':44002},{'serial':'PHONE','port':44001}])
    assert restored=={'PHONE'} and client.port==44001 and tile.poll_wake.is_set()
    assert client.fingerprint=='a'*64 and client.token=='test-session' and tile.status['recording']
