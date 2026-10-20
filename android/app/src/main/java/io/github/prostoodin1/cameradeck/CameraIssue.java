package io.github.prostoodin1.cameradeck;

import java.util.Locale;

final class CameraIssue {
    static String summary(String message){
        String m=message.toLowerCase(Locale.ROOT);
        if(m.contains("camera_error")||m.contains("camera error")||m.contains("broken pipe")||m.contains("stream combination"))return "Камера не приняла режим. Запуск записи не подтверждён. Выберите другое разрешение/FPS; подробности — по нажатию.";
        if(m.contains("storage")||m.contains("space")||m.contains("накопитель")||m.contains("finaliz"))return "Сохранение не подтверждено. Проверьте накопитель и свободное место. Сохраните незавершённый файл для восстановления.";
        if(m.contains("microphone")||m.contains("audio:"))return "Звук недоступен. Проверьте разрешение микрофона и перезапустите камеру после остановки записи.";
        if(m.contains("exception")||m.contains("codec")||m.contains("error"))return "Операция не завершена. Проверьте состояние записи. Нажмите на сообщение для диагностики.";
        return message;
    }
}
