"""Keep Qt's application alive while widgets from all test modules are collected."""
import os,gc
os.environ['QT_QPA_PLATFORM']='offscreen'
import pytest
from PyQt6.QtWidgets import QApplication

@pytest.fixture(scope='session',autouse=True)
def qt_application_lifetime():
    app=QApplication.instance() or QApplication([])
    yield app
    gc.collect()
    app.processEvents()
    app.quit()
