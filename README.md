# ScreenPilot

ScreenPilot — Windows-приложение для управления воспроизведением локального видео на одном внешнем экране. Панель управления остаётся на ноутбуке, а видео выводится отдельным полноэкранным окном только на выбранный display target.

Текущий статус: этапы 0–2 завершены; mpv/Windows gate принят для разработки MVP. Следующий этап — read-only Windows display adapter. Полноценный интерфейс и пользовательские функции ещё не реализованы.

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

## Реализовано на этапе 2

- точный выбор режима по рациональным частотам, включая семейства 24 000/1 001 и 60 000/1 001;
- машины состояний сессии вывода и плеера;
- fingerprint и правила сохранения позиции просмотра;
- атомарные JSON settings, resume и recovery journal с миграцией и защитой от повреждённых или более новых схем;
- unit-тесты чистой логики и файлового persistence.

## Документация

- [Функциональные требования](ScreenPilot_FT.md)
- [Техническое задание](ScreenPilot_TZ.md)
- [Инструкции агенту-разработчику](AGENTS.md)
- [ADR](docs/adr/README.md)
- [Проверка оборудования](docs/hardware-test-checklist.md)

## Лицензии и mpv

mpv не скачивается при первом запуске приложения. Перед добавлением Windows runtime необходимо зафиксировать в `vendor/mpv/README.md` источник, версию, SHA-256 каждого файла и тексты лицензий.
