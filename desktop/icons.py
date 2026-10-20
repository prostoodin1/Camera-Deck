"""Small original SVG line icons; no third-party artwork or bitmap dependencies."""
from PyQt6.QtCore import QByteArray,Qt
from PyQt6.QtGui import QIcon,QPainter,QPixmap
from PyQt6.QtSvg import QSvgRenderer

PATHS={
 'monitor':'<rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 21h8m-4-4v4"/>',
 'control':'<path d="M5 3v18m7-18v18m7-18v18"/><rect x="3" y="7" width="4" height="4" rx="1"/><rect x="10" y="14" width="4" height="4" rx="1"/><rect x="17" y="6" width="4" height="4" rx="1"/>',
 'production':'<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
 'media':'<rect x="3" y="3" width="18" height="18" rx="2"/><path d="m10 8 6 4-6 4z"/>',
 'expand':'<path d="M3 9V3h6m6 0h6v6M3 15v6h6m6 0h6v-6"/>',
 'rotate':'<path d="M4 8a8 8 0 1 1-1 7m1-7V3m0 5h5"/>',
 'download':'<path d="M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5"/>',
}


def icon(name,color='#aeb7c4'):
    svg=f'<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><g fill="none" stroke="{color}" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">{PATHS[name]}</g></svg>'
    pix=QPixmap(48,48);pix.fill(Qt.GlobalColor.transparent);painter=QPainter(pix);QSvgRenderer(QByteArray(svg.encode())).render(painter);painter.end();pix.setDevicePixelRatio(2);return QIcon(pix)
