# mpv/Windows technical spike

Этот каталог — самостоятельный протокол этапа 1; исполняемый вход расположен в [MpvSpikeMain](../../screenpilot-app/src/main/java/ru/pavelkuzmin/screenpilot/app/MpvSpikeMain.java), чтобы после успешного gate его не пришлось переписывать. JavaFX-код здесь намеренно отсутствует.

## Что запускается

```powershell
.\gradlew.bat :screenpilot-app:run --args="mpv-spike"
```

В управляемом Job Object окружении Codex для отдельной проверки mpv IPC используется только диагностический режим:

```powershell
.\gradlew.bat :screenpilot-app:run --args="mpv-spike --without-job-object"
```

Для проверки реальных видео передают абсолютные пути в одном параметре на файл:

```powershell
.\gradlew.bat :screenpilot-app:run --args="mpv-spike --without-job-object --media=C:\path\h264.mp4 --media=C:\path\hevc-main10.mp4"
```

После того как Windows переведена пользователем в режим «Расширить», для ручного размещения на нужном mpv screen index:

```powershell
.\gradlew.bat :screenpilot-app:run --args="mpv-spike --without-job-object --screen=1 --hold-ms=5000 --media=C:\path\video.mp4"
```

Для повторяемой проверки десяти последовательных созданий и остановок окна:

```powershell
.\gradlew.bat :screenpilot-app:run --args="mpv-spike --without-job-object --screen=1 --repeat=10 --hold-ms=1000 --media=C:\path\video.mp4"
```

Для проверки встроенной subtitle-дорожки передают небольшой MKV с уже вложенным subtitle stream:

```powershell
.\gradlew.bat :screenpilot-app:run --args="mpv-spike --without-job-object --screen=1 --embedded-subtitle-media=C:\path\embedded-subtitle.mkv"
```

Прототип читает `track-list`, находит дорожку типа `sub`, выбирает её через `sid` и проверяет ответ mpv. В репозитории хранится только исходная SRT для самостоятельного fixture в [fixtures](fixtures/embedded-subtitle.srt); сгенерированный MKV остаётся временным файлом.

Проверка аварийного завершения через Windows Job Object требует отдельного обычного PowerShell/Windows Terminal, запущенного пользователем вне Codex и IDE:

```powershell
Set-Location C:\path\to\ScreenPilot
.\spikes\mpv-windows\verify-job-object.ps1
```

Скрипт запускает spike с `--crash-after-job-object`. Ненулевой код Gradle ожидаем: тестовый Java-процесс намеренно завершается через `Runtime.halt`. Успех — отсутствие нового `mpv.exe` после этого. Перед запуском закройте собственные экземпляры mpv, чтобы результат не был неоднозначным.

`--screen=N`, `--fullscreen` и `--fs-screen=N` передаются mpv как отдельные аргументы. Они не меняют topology Windows; номер экрана пока выбирается вручную и будет заменён стабильным display target после этапа 3.

## Результаты от 2026-08-04

Подробная pass/fail-таблица и решение — в [ADR-0001](../../docs/adr/0001-video-engine.md), а список оставшихся ручных проверок — в [hardware checklist](../../docs/hardware-test-checklist.md).

Короткий повторяемый ручной чек-лист после подключения HDMI:

1. Запустить `verify-job-object.ps1` в обычном PowerShell вне Codex/IDE и убедиться, что Job Object прекращает mpv после аварийного закрытия Java-процесса.
2. Подключить HDMI в extended mode, выбрать именно его стабильный идентификатор и выполнить 10 запусков/остановок полноэкранного окна.
3. Переключить audio-device между HDMI и speakers, не меняя Windows default audio.
4. Прогнать H.264, HEVC Main 10, HDR10→SDR и embedded/external subtitles.
5. Отключить HDMI во время playback и зафиксировать поведение для следующих этапов display recovery.
