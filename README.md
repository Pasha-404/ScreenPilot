# ScreenPilot

> Обновление от 05.08.2026: этап 4 реализован. Есть production-адаптер mpv с JSON IPC, событиями, тайм-аутами, контролируемым завершением и однократным fallback с hardware decoding на software decoding. Полноценный JavaFX-интерфейс пока намеренно не начат.

ScreenPilot — Windows-приложение для управления воспроизведением локального видео на одном внешнем экране. Панель управления остаётся на ноутбуке, а видео выводится отдельным полноэкранным окном только на выбранный display target.

Текущий статус: этапы 0–4 завершены; mpv/Windows gate принят для разработки MVP. Реализованы read-only обнаружение экранов и опрос топологии, а также production-адаптер плеера. Полноценный интерфейс и пользовательские функции ещё не реализованы.

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

Технический запуск stage 1 (без JavaFX-интерфейса):

```powershell
.\gradlew.bat :screenpilot-app:run --args="mpv-spike"
```

Техническая ручная проверка адаптера этапа 4 (не меняет настройки дисплея или Windows default audio):

```powershell
.\gradlew.bat :screenpilot-app:run --args="player-demo --media=C:\path\movie.mkv --hold-ms=15000"
.\gradlew.bat :screenpilot-player-mpv:integrationTest
```

Первой команде нужен существующий видеофайл. Она запускает отдельный mpv-процесс, выполняет load, pause, seek, play и stop. Пока выбор external display ещё не связан с этим адаптером — это задача этапа 5.

Диагностика этапа 3 (только чтение Windows; экран, режим, HDR и аудио не меняются):

```powershell
.\gradlew.bat :screenpilot-app:run --args="display-probe"
.\gradlew.bat :screenpilot-app:run --args="display-poll-smoke"
```

Первая команда выводит известные Windows display targets, GDI-имя, текущий и доступные режимы. Вторая на одну итерацию запускает фоновый `display-poller`: его лёгкая проверка topology выполняется раз в секунду, а полное перечисление режимов — лишь при изменении хеша.

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
- `screenpilot-app` — будущая JavaFX-точка входа.

## Реализовано на этапах 2–3

- точный выбор режима по рациональным частотам, включая семейства 24 000/1 001 и 60 000/1 001;
- машины состояний сессии вывода и плеера;
- fingerprint и правила сохранения позиции просмотра;
- атомарные JSON settings, resume и recovery journal с миграцией и защитой от повреждённых или более новых схем;
- unit-тесты чистой логики и файлового persistence.
- read-only Windows adapter: `QueryDisplayConfig`, запросы `DisplayConfigGetDeviceInfo`, `EnumDisplaySettingsExW`, физические bounds, HDMI/internal/DisplayPort classification и rational refresh;
- безопасный `display-poller` с интервалом 1 с, fake fixtures и unit-тестами: полная discovery выполняется только при изменении topology;
- отдельный hardware integration test и консольный probe; проверенная текущая конфигурация — HDMI target `\\.\DISPLAY2`, 1920×1080 @ 59,940 Гц.

На эталонном AMD-драйвере Windows возвращает `ERROR_GEN_FAILURE` для запросов `DisplayConfigGetDeviceInfo`, хотя сами path-данные исправны. В этом случае ScreenPilot явно помечает `gdiMapping=FALLBACK`, использует `EnumDisplayDevicesW` только для read-only диагностики и не считает такую привязку достаточной для будущих автоматических изменений конфигурации. Решение и ограничения зафиксированы в [ADR-0002](docs/adr/0002-display-discovery-fallback.md).

## Документация

- [Функциональные требования](ScreenPilot_FT.md)
- [Техническое задание](ScreenPilot_TZ.md)
- [Инструкции агенту-разработчику](AGENTS.md)
- [ADR](docs/adr/README.md)
- [Проверка оборудования](docs/hardware-test-checklist.md)

## Лицензии и mpv

mpv не скачивается при первом запуске приложения. Перед добавлением Windows runtime необходимо зафиксировать в `vendor/mpv/README.md` источник, версию, SHA-256 каждого файла и тексты лицензий.
