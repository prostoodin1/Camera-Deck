import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'desktop'))
from PyQt6.QtGui import QImage,QColor
from monitor_tools import analyze


def test_histogram_uses_actual_preview_pixels():
    image=QImage(160,90,QImage.Format.Format_RGB888);image.fill(QColor(255,255,255));result=analyze(image,'histogram')
    assert result['histogram'][255]==160*90 and sum(result['histogram'])==160*90
    image.fill(QColor(0,0,0));assert analyze(image,'histogram')['histogram'][0]==160*90


def test_zebra_is_transparent_below_threshold_and_scopes_are_bounded():
    image=QImage(1920,1080,QImage.Format.Format_RGB888);image.fill(QColor(100,100,100));result=analyze(image,'zebra')
    assert result['overlay'].width()==160 and result['overlay'].height()==90
    assert result['overlay'].pixelColor(2,2).alpha()==0
    image.fill(QColor(255,255,255));result=analyze(image,'zebra');assert result['overlay'].pixelColor(0,0).alpha()>0
    assert not analyze(image,'waveform')['waveform'].isNull()
