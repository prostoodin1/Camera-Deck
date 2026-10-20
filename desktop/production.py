"""Confirmed per-camera group operations. Host observation times are not frame sync."""
from concurrent.futures import ThreadPoolExecutor, as_completed
import time


def estimate_clock(client,samples=5,now=time.monotonic_ns):
    estimates=[]
    for _ in range(samples):
        before=now();remote=client.json('/clock').get('monotonicNs');after=now()
        if type(remote) is not int or remote<=0 or after<before:raise ValueError('Телефон не сообщает монотонное время')
        estimates.append({'offsetNs':remote-(before+after)//2,'uncertaintyNs':(after-before)//2,'roundTripNs':after-before})
    return min(estimates,key=lambda item:item['roundTripNs'])


def operate(cameras, recording, emit, timeout=12):
    def one(identity, client):
        started=time.monotonic()
        try:
            state=client.json('/status')
            if bool(state.get('recording')) == recording:
                return identity,True,'Уже записывает' if recording else 'Остановлена',state
            if not state.get('ready'):
                raise RuntimeError('Камера не готова. Проверьте связь и состояние телефона.')
            if recording and state.get('mode')!='video':
                raise RuntimeError('Выберите режим Видео перед групповым запуском.')
            client.command('record_start' if recording else 'record_stop')
            deadline=time.monotonic()+timeout
            while time.monotonic()<deadline:
                state=client.json('/status')
                if bool(state.get('recording'))==recording and state.get('ready'):
                    return identity,True,f'Подтверждено через {time.monotonic()-started:.2f} с',state
                if state.get('error') and not state.get('recording'):
                    raise RuntimeError(state['error'])
                time.sleep(.15)
            raise TimeoutError('Нет подтверждения. Проверьте запись на телефоне; повторный запуск автоматически не отправляется.')
        except Exception as exc:
            return identity,False,str(exc),None
    with ThreadPoolExecutor(max_workers=min(4,max(1,len(cameras)))) as pool:
        tasks=[pool.submit(one,identity,client) for identity,client in cameras]
        for future in as_completed(tasks):
            result=future.result();emit(result)

