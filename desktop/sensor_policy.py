"""Camera-reported limits and partial commands for quick sensor controls."""
import math

ISO_STOPS=(25,50,100,200,400,800,1600,3200,6400,12800,25600,51200,102400)
SHUTTERS=tuple(round(1e9/n) for n in (8000,4000,2000,1000,500,250,125,100,60,50,30,25,15,8,4,2,1))

def bounds(kind,status):
    if kind=='iso':return max(1,int(status.get('isoMin',50))),max(1,int(status.get('isoMax',6400)))
    if kind=='shutter':
        low=max(1,int(status.get('exposureMinNs',1000)));high=max(1,int(status.get('exposureMaxNs',100000000)))
        if status.get('mode')=='video':high=min(high,1_000_000_000//max(1,int(status.get('videoFps',30))))
        return low,high
    if kind=='ev':
        data=status.get('imageControls',{});return float(data.get('evMin',0)),float(data.get('evMax',0))
    return 0,float(status.get('focusMax') or 0)

def supported(kind,status):
    low,high=bounds(kind,status)
    if high<low:return False
    if kind=='focus':return high>0
    if kind=='ev':return high>low and not status.get('manualIso')
    return bool(status.get('manualSensor'))

def clip(value,low,high):return max(low,min(high,value))

def presets(kind,status):
    low,high=bounds(kind,status)
    if high<low:return []
    values=ISO_STOPS if kind=='iso' else SHUTTERS if kind=='shutter' else (-2,-1,0,1,2) if kind=='ev' else (0,.25,.5,1,2,5,10)
    return sorted(set([low,high]+[v for v in values if low<=v<=high]))

def context(status):
    return (status.get('cameraId'),status.get('mode'),status.get('videoFps') if status.get('mode')=='video' else None,
            bounds('iso',status),bounds('shutter',status),bounds('focus',status),bounds('ev',status))

def command(kind,automatic,value,status):
    """Only send edited fields; preserve a partner that another operator may have changed."""
    if not supported(kind,status):raise ValueError('Эта настройка недоступна для текущего объектива / режима')
    if kind=='focus':return 'settings',{'focus':-1 if automatic else clip(float(value),*bounds(kind,status))}
    if kind=='ev':
        low,high=bounds(kind,status);step=float(status['imageControls'].get('evStep',1))
        if not math.isfinite(step) or step<=0:raise ValueError('Неизвестный шаг EV')
        steps=math.floor(float(value)/step+.5);return 'image_settings',{'ev':clip(steps*step,low,high)}
    if automatic:return 'settings',{'iso':0,'exposureNs':0}
    chosen=round(clip(value,*bounds(kind,status)))
    if kind=='iso':
        values={'iso':chosen}
        if not status.get('manualExposureNs'):values['exposureNs']=round(clip(status.get('exposureNs') or 10000000,*bounds('shutter',status)))
    else:
        values={'exposureNs':chosen}
        if not status.get('manualIso'):values['iso']=round(clip(status.get('iso') or bounds('iso',status)[0],*bounds('iso',status)))
    return 'settings',values
