"""Keep Qt's application alive while widgets from all test modules are collected."""
import os,gc
os.environ['QT_QPA_PLATFORM']='offscreen'
import pytest
from PyQt6.QtWidgets import QApplication
from PyQt6.QtCore import QEvent

@pytest.fixture(scope='session',autouse=True)
def qt_application_lifetime():
    app=QApplication.instance() or QApplication([])
    yield app
    gc.collect()
    app.processEvents()
    app.quit()

@pytest.fixture(autouse=True)
def dispose_test_widgets(qt_application_lifetime):
    yield
    # close() alone retains dialogs and their QObject cycles until a later unrelated test.
    for widget in QApplication.topLevelWidgets():
        widget.deleteLater()
    QApplication.sendPostedEvents(None,QEvent.Type.DeferredDelete)
    qt_application_lifetime.processEvents()
    gc.collect()
