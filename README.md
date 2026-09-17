# ScreenPilot

ScreenPilot — личное Windows-приложение для воспроизведения локальных видеофайлов на одном выбранном внешнем экране. Управление остаётся на ноутбуке; предпросмотра и второго видеопотока на встроенном дисплее нет.

Версия: **0.1.0**. Целевая система — Windows 10/11 x64. Приложение не меняет системное устройство звука по умолчанию и не включает HDR Windows.

![Ориентир по интерфейсу](screen.png)

## Что умеет

- выводит видео через отдельный полноэкранный процесс mpv только на выбранный внешний экран;
- распознаёт HDMI и другие внешние экраны, которые Windows предоставляет через Display Configuration API;
- при необходимости временно включает расширенный рабочий стол и меняет только режим выбранного внешнего экрана, затем восстанавливает исходную конфигурацию;
- воспроизводит плейлист, предотвращает дубликаты, поддерживает drag-and-drop, порядок, переходы между файлами и автоматический переход к следующему;
- сохраняет последнюю папку и позицию просмотра, предлагает продолжить просмотр или начать заново;
- поддерживает внешние `.srt`, `.ass`, `.ssa`, выбор дорожек и аудиовыхода mpv;
- даёт паузу, остановку видео без закрытия вывода, перемотку, громкость, масштабирование и локальные горячие клавиши;
- хранит настройки и позиции просмотра в `%APPDATA%\PashaApps\ScreenPilot`, а логи, recovery и lock — в `%LOCALAPPDATA%\PashaApps\ScreenPilot`; не допускает второй экземпляр и копирует обезличенную диагностику в буфер обмена.

## Быстрый старт

Для запуска из исходников нужны Windows, JDK 21 и локальный runtime mpv из [vendor/mpv/README.md](vendor/mpv/README.md). Runtime не хранится в Git: один файл `mpv.exe` больше лимита обычного GitHub Git (100 МБ). Его восстанавливает hash-checked скрипт сборки; само приложение никогда ничего не скачивает.

```powershell
.\gradlew.bat clean test integrationTest
.\gradlew.bat :screenpilot-app:run
```

Подключите внешний экран, в Windows включите «Расширить эти экраны», выберите файл и экран в ScreenPilot, подтвердите подготовку, затем нажмите «Воспроизвести на внешнем экране».

Горячие клавиши действуют только в окне приложения: `Space` — пауза, `←`/`→` — ±10 секунд, `Ctrl+←`/`Ctrl+→` — ±60 секунд, `↑`/`↓` — громкость, `Esc` — остановить вывод, `Ctrl+O` — открыть файлы.

## Установщик

ScreenPilot следует стандарту AppFleet для Windows: `jpackage` создаёт self-contained app-image со встроенной Java Runtime, а Inno Setup 6 собирает конечный EXE для текущего пользователя. Java и права администратора пользователю не нужны.

Одна команда собирает и проверяет выпуск. Базовая версия задаётся только свойством `version` в `gradle.properties`; для конкретного release-кандидата её можно однократно переопределить параметром Gradle в формате `MAJOR.MINOR.PATCH`.

```powershell
.\gradlew.bat clean buildWindowsInstaller "-Pversion=0.1.0"
```

В `dist\release\0.1.0\` появятся ровно три обязательных файла:

- `ScreenPilot-Setup-0.1.0-x64.exe`;
- `ScreenPilot-Setup-0.1.0-x64.exe.sha256`;
- `appfleet-manifest.json`.

Установщик создаёт запись Windows, ярлык в меню «Пуск» и необязательный ярлык на рабочем столе. Он устанавливает заменяемые файлы в `%LOCALAPPDATA%\Programs\PashaApps\ScreenPilot`, а при обновлении сохраняет каталог установки, ярлыки, настройки и пользовательские данные. Удаление программы не удаляет settings, resume, логи или recovery без отдельного действия пользователя.

Первый запуск новой версии копирует существующие settings, resume, recovery и логи из старого расположения `%LOCALAPPDATA%\ScreenPilot` в стандартные каталоги, не удаляя исходные файлы и не перезаписывая уже существующие новые данные.

Для быстрой проверки без установки используется `:screenpilot-app:packageAppImage`. Сборочная машина должна иметь Inno Setup 6; путь к `ISCC.exe` можно передать как `-PinnoCompiler=C:\path\to\ISCC.exe` или через переменную окружения `INNO_SETUP_COMPILER`.

GitHub Actions повторяет эту же сборку: на `main` создаёт проверяемый artifact, а при теге `vMAJOR.MINOR.PATCH` публикует три release asset в GitHub Releases. Перед созданием первого публичного тега проверьте комплект уведомлений для поставляемой сборки mpv, описанный в [vendor/mpv/THIRD_PARTY_NOTICES.md](vendor/mpv/THIRD_PARTY_NOTICES.md).

## Проверка качества

Автоматическая проверка:

```powershell
.\gradlew.bat --no-daemon clean test integrationTest
.\gradlew.bat --no-daemon clean buildWindowsInstaller "-Pversion=0.1.0"
```

`buildWindowsInstaller` сам запускает unit/component и integration tests, затем создаёт app-image, Inno Setup EXE, SHA-256 и манифест AppFleet. WiX больше не нужен. Аппаратные результаты и оставшиеся проверки находятся в [чек-листе](docs/hardware-test-checklist.md), выпускной статус — в [release checklist](docs/release-checklist.md).

## Устройство проекта

- `screenpilot-domain` — модели, правила и state machines без JavaFX, JNA, mpv и файловой системы;
- `screenpilot-player-mpv` — дочерний процесс mpv и JSON IPC по локальному Windows named pipe;
- `screenpilot-platform-windows` — Win32/JNA: экраны, временные режимы, recovery и Job Object;
- `screenpilot-persistence` — JSON settings, resume и recovery journal;
- `screenpilot-app` — JavaFX-интерфейс, единое состояние, упаковка и lifecycle приложения.

Существенные решения зафиксированы в [ADR](docs/adr/README.md). Полное техническое задание — [ScreenPilot_TZ.md](ScreenPilot_TZ.md), исходные требования — [ScreenPilot_FT.md](ScreenPilot_FT.md).

## Ограничения версии 0.1.0

- Воспроизведение на реальном телевизоре и физический HDMI-аудиовывод ещё не проверены.
- Нет автоматического обновления, телеметрии, сети, preview на ноутбуке, HDR passthrough и одновременного вывода на несколько экранов.
- Исходный код опубликован для прозрачности личного проекта. Лицензия на повторное использование ScreenPilot пока не предоставляется; для этого потребуется отдельное решение автора.
