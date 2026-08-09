# ScreenPilot

> Обновление от 07.08.2026: основной пользовательский сценарий этапа 6 проверен на реальном HDMI-мониторе. Видео открывается только на выбранном внешнем экране, работают пауза и перемотка ±10 секунд, а «Остановить вывод» закрывает mpv и оставляет расширенный режим Windows без изменений. Физическая проверка HDMI-аудио по-прежнему отложена владельцем проекта.

ScreenPilot — Windows-приложение для управления воспроизведением локального видео на одном внешнем экране. Панель управления остаётся на ноутбуке, а видео выводится отдельным полноэкранным окном только на выбранный display target.

Текущий статус: этапы 0–6 реализованы; пользовательский UI и основной сценарий воспроизведения проверены. Реализованы обнаружение экранов, production-адаптер mpv, recovery journal, безопасное размещение окна на физическом display target и JavaFX-интерфейс без preview на ноутбуке. Ручные дополнительные проверки масштабирования, локальных hotkeys и реакции UI на физическое отключение HDMI остаются отдельными `NOT TESTED` пунктами; физическая проверка HDMI-аудио отложена.

## Требования для разработки

- Windows 10 22H2+ или Windows 11, x64;
- JDK 21;
- интернет только для первоначального разрешения Gradle-зависимостей;
- HDMI-экран нужен для полной ручной проверки прототипа.

Локальная Java установлена в составе будущего установщика; конечному пользователю отдельная Java не понадобится.

## Команды

После создания Gradle Wrapper:

```powershell
.\gradlew.bat clean test
.\gradlew.bat integrationTest
```

Запуск пользовательского интерфейса:

```powershell
.\gradlew.bat :screenpilot-app:run
```

Подключите внешний экран и включите в Windows режим «Расширить эти экраны». В UI выберите локальный файл и внешний target, подтвердите подготовку экрана, затем нажмите «Воспроизвести на внешнем экране». ScreenPilot проверяет фактические координаты окна mpv: при несовпадении с выбранным physical target вывод отменяется, а на встроенном экране видео не появляется. Кнопка «Остановить вывод» закрывает mpv и восстанавливает сохранённую конфигурацию; она не меняет Windows default audio.

Технический запуск stage 1 (без JavaFX-интерфейса):

```powershell
.\gradlew.bat :screenpilot-app:run --args="mpv-spike"
```

Техническая ручная проверка адаптера этапа 4 (не меняет настройки дисплея или Windows default audio):

```powershell
.\gradlew.bat :screenpilot-app:run --args="player-demo --media=C:\path\movie.mkv --hold-ms=15000"
.\gradlew.bat :screenpilot-player-mpv:integrationTest
```

Первой команде нужен существующий видеофайл. Она запускает отдельный mpv-процесс, выполняет load, pause, seek, play и stop, не меняя настройки Windows.

Диагностика этапа 3 (только чтение Windows; экран, режим, HDR и аудио не меняются):

```powershell
.\gradlew.bat :screenpilot-app:run --args="display-probe"
.\gradlew.bat :screenpilot-app:run --args="display-poll-smoke"
```

Первая команда выводит известные Windows display targets, GDI-имя, текущий и доступные режимы. Вторая на одну итерацию запускает фоновый `display-poller`: его лёгкая проверка topology выполняется раз в секунду, а полное перечисление режимов — лишь при изменении хеша.

### Аппаратная проверка этапа 5

Команды ниже меняют только явно выбранный внешний экран. Перед любой изменяющей командой создаётся атомарный snapshot в `%LOCALAPPDATA%\ScreenPilot\recovery`; встроенный экран и Windows default audio не меняются. Не запускайте одновременно второй экземпляр приложения. На текущем AMD-драйвере используется fallback GDI-связь, поэтому `--confirm` обязателен.

```powershell
# Только чтение: показать modes, которые драйвер подтвердил через DEVMODE.
.\gradlew.bat :screenpilot-app:run --args="display-mode-list"

# Временно применить подтверждённый режим внешнего экрана и автоматически восстановить snapshot.
.\gradlew.bat :screenpilot-app:run --args="display-mode-smoke --confirm --mode=1280x720@60 --hold-ms=3000"

# Убедиться, что активны раздельные internal/external desktop; команда работает и с
# подключённым, но неактивным или клонированным target и при необходимости временно просит Windows Extend.
.\gradlew.bat :screenpilot-app:run --args="display-extend-smoke --confirm --hold-ms=0"

# Если предыдущий процесс завершился до rollback, сначала выполняется только эта команда.
.\gradlew.bat :screenpilot-app:run --args="display-recover --confirm"
```

`display-mode-smoke` принимает только режим, перечисленный `display-mode-list`; дробная частота не округляется до целой. Если выбранный target был clone или неактивен, команда сначала сохраняет исходный snapshot, временно включает Extend и заново находит тот же Win32 target по `adapter LUID + target ID`. Если Windows включила другой экран, ScreenPilot делает rollback и не меняет режим. Если журнал остался активным, обычная работа блокируется до `display-recover --confirm` или явного `display-keep-current --confirm`. Для отдельной read-only проверки unplug есть `display-hot-unplug-watch --hold-ms=30000`; вынимать HDMI следует только когда команда уже сообщила, что наблюдение началось.

Чтобы повторить проверку этапа 5, нужен существующий локальный видеофайл. При единственном активном внешнем экране и уже подтверждённом сопоставлении mpv `screen=1` запускается команда:

```powershell
.\gradlew.bat :screenpilot-app:run --args="output-hot-unplug-smoke --media=C:\path\video.mp4 --screen=1 --hold-ms=30000"
```

Она не меняет режимы или topology Windows и не трогает системное устройство звука. Команда запускает production-адаптер mpv на указанном mpv-экране, проверяет присутствие выбранного внешнего target через `WindowsDisplayPoller`, а после физического отключения HDMI останавливает mpv и освобождает его Job Object. Отключать кабель нужно только после сообщения `Video is playing…`. Если внешних экранов несколько, сначала выполните `display-probe` и добавьте `--target=<display-id>`.

После того как пользователь включил режим «Расширить», ручная проверка выбранного экрана выполняется так:

```powershell
.\gradlew.bat :screenpilot-app:run --args="mpv-spike --without-job-object --screen=1 --repeat=10 --hold-ms=1000 --media=C:\path\video.mp4"
```

Эта команда не меняет параметры экранов или системное устройство звука Windows. Номер `--screen` пока временный; подробный чек-лист и ограничения находятся в [документации прототипа](spikes/mpv-windows/README.md).

Подробности о локальном, hash-проверенном runtime — в [vendor/mpv/README.md](vendor/mpv/README.md).

## Структура

- `screenpilot-domain` — чистые модели, правила и порты;
- `screenpilot-player-mpv` — mpv process и JSON IPC;
- `screenpilot-platform-windows` — Win32/JNA-адаптеры;
- `screenpilot-persistence` — JSON-хранилища;
- `screenpilot-app` — JavaFX-точка входа, FXML-интерфейс и application service.

## Реализовано

- точный выбор режима по рациональным частотам, включая семейства 24 000/1 001 и 60 000/1 001;
- машины состояний сессии вывода и плеера;
- fingerprint и правила сохранения позиции просмотра;
- атомарные JSON settings, resume и recovery journal с миграцией и защитой от повреждённых или более новых схем;
- unit-тесты чистой логики и файлового persistence.
- read-only Windows adapter: `QueryDisplayConfig`, запросы `DisplayConfigGetDeviceInfo`, `EnumDisplaySettingsExW`, физические bounds, HDMI/internal/DisplayPort classification и rational refresh;
- безопасный `display-poller` с интервалом 1 с, fake fixtures и unit-тестами: полная discovery выполняется только при изменении topology;
- отдельный hardware integration test и консольный probe; проверенная текущая конфигурация — HDMI target `\\.\DISPLAY2`, 1920×1080 @ 59,940 Гц.
- Stage 5: lossless snapshot `DISPLAYCONFIG_*` + активные `DEVMODEW`, проверяемая временная смена режима без `CDS_UPDATEREGISTRY`, rollback, persisted recovery journal, archive на семь дней, startup guard и отдельная техническая остановка mpv при потере внешнего target; подробности — в [ADR-0004](docs/adr/0004-display-mutation-recovery.md).
- Stage 6: JavaFX-интерфейс со снимками immutable state, responsive стартовым размером и прокруткой боковых панелей; выбор файла, внешнего target и режима, single-file playback, локальные hotkeys, пауза, перемотка, громкость, масштабирование, выбор mpv-аудиовыхода и отдельные команды «Стоп» / «Остановить вывод». Перед началом вывода `WindowsMpvWindowLocator` подтверждает координаты окна mpv на выбранном external target; детали — в [ADR-0005](docs/adr/0005-javafx-ui-state-and-output-placement.md).
- Stage 7: плейлист в памяти с защитой от дубликатов по fingerprint, добавление нескольких файлов, внешнее drag-and-drop, перестановка, previous/next и auto-next; отдельный headless mpv-probe читает параметры без картинки и звука. Подключены resume-диалог, сохранение последней папки и выбор внешних `.srt`/`.ass`/`.ssa`; решение — в [ADR-0006](docs/adr/0006-playlist-resume-and-metadata-probe.md). Реальная проверка этих сценариев на HDMI-output ещё не проводилась.

На эталонном AMD-драйвере Windows возвращает `ERROR_GEN_FAILURE` для запросов `DisplayConfigGetDeviceInfo`, хотя сами path-данные исправны. В этом случае ScreenPilot явно помечает `gdiMapping=FALLBACK`, использует `EnumDisplayDevicesW` только для read-only диагностики и не считает такую привязку достаточной для будущих автоматических изменений конфигурации. Решение и ограничения зафиксированы в [ADR-0002](docs/adr/0002-display-discovery-fallback.md).

## Документация

- [Функциональные требования](ScreenPilot_FT.md)
- [Техническое задание](ScreenPilot_TZ.md)
- [Инструкции агенту-разработчику](AGENTS.md)
- [ADR](docs/adr/README.md)
- [Проверка оборудования](docs/hardware-test-checklist.md)

## Лицензии и mpv

mpv не скачивается при первом запуске приложения. Перед добавлением Windows runtime необходимо зафиксировать в `vendor/mpv/README.md` источник, версию, SHA-256 каждого файла и тексты лицензий.
