"""Human-readable consequences and recovery, with expandable diagnostic details."""
from PyQt6.QtWidgets import QDialog,QVBoxLayout,QLabel,QPushButton,QPlainTextEdit,QDialogButtonBox
from appearance import GlassSheet


def explain(message):
    text=message.lower()
    if any(key in text for key in ('broken pipe','camera_error','camera error','configuring streams','unsupported camera stream')):
        return 'Камера не приняла режим съёмки.','Запуск записи не подтверждён. Проверьте состояние на телефоне.','Выберите другое сочетание разрешения и FPS или перезапустите камеру.'
    if any(key in text for key in ('timeout','timed out','connection','связ','http 503','видеопоток')):
        return 'Нет ответа от телефона.','Предпросмотр или команда могут быть недоступны; запись на телефоне может продолжаться.','Проверьте Wi-Fi и состояние приложения на телефоне, затем повторите подключение.'
    if any(key in text for key in ('space','накопитель','storage','disk','no current')):
        return 'Материал или накопитель недоступен.','Сохранение не подтверждено.','Проверьте свободное место и подключение накопителя. Незавершённый файл сохраните для восстановления.'
    if any(key in text for key in ('certificate','fingerprint','отпечат','ssl','tls')):
        return 'Не удалось подтвердить защищённое соединение.','Команда не отправлена по непроверенному соединению.','Сравните отпечаток на телефоне и подключитесь заново. Установите совместимые версии обеих программ.'
    return 'Операция требует внимания.','Проверьте состояние камеры перед повтором действия.','Подробности доступны ниже.'


def show_message(parent,title,message):
    dialog=GlassSheet(parent);dialog.setWindowTitle(title);dialog.setMinimumWidth(480);layout=QVBoxLayout(dialog);layout.setContentsMargins(24,24,24,24)
    if title in ('Не выполнено','Ошибка','Группа камер'):
        summary,impact,action=explain(str(message))
        for text in (summary,impact,action):label=QLabel(text);label.setWordWrap(True);layout.addWidget(label)
        details=QPlainTextEdit(str(message));details.setReadOnly(True);details.setMaximumHeight(170);details.hide()
        expand=QPushButton('Диагностические подробности');expand.setCheckable(True);expand.toggled.connect(details.setVisible);layout.addWidget(expand);layout.addWidget(details)
    else:
        label=QLabel(str(message));label.setWordWrap(True);label.setTextInteractionFlags(__import__('PyQt6.QtCore',fromlist=['Qt']).Qt.TextInteractionFlag.TextSelectableByMouse);layout.addWidget(label)
    buttons=QDialogButtonBox(QDialogButtonBox.StandardButton.Ok);buttons.accepted.connect(dialog.accept);layout.addWidget(buttons);dialog.exec()
