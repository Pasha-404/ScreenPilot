# ADR-0008: стандарт AppFleet и Inno Setup

- Статус: принято
- Дата: 2026-08-29

## Контекст

Первый локальный установщик ScreenPilot создавался `jpackage --type exe` через WiX. Новый стандарт AppFleet требует другой публичный формат: конечный per-user EXE на Inno Setup, стабильный идентификатор приложения, проверяемые release assets и регистрацию для AppFleet.

На машине разработки не обнаружено установленной прежней jpackage-версии ScreenPilot, а предыдущий EXE не публиковался на GitHub. Поэтому первый AppFleet-релиз не удаляет неизвестные старые установки: такой поиск и удаление запрещены стандартом.

## Решение

1. `jpackage --type app-image` остаётся источником self-contained Java Runtime, JavaFX, приложения, mpv и notices. Inno Setup 6 — единственный генератор конечного Windows EXE.
2. Постоянный `AppId` `c4cc60ea-a3e8-4ff1-8d94-e79f3b791df4`, техническое имя `ScreenPilot` и canonical repository URL фиксируются в `gradle.properties`. Версия задаётся ровно в одном месте: Gradle property `version` с проверкой SemVer.
3. Inno Setup устанавливает только для текущего пользователя в `%LOCALAPPDATA%\Programs\PashaApps\ScreenPilot`, записывает AppFleet-ключ в `HKCU\Software\PashaApps\<AppId>`, поддерживает стандартную тихую установку и обновляет только известные заменяемые элементы app-image (`ScreenPilot.exe`, `app`, `runtime`, `icons`). Широкое удаление каталога установки не применяется.
4. Задача `buildWindowsInstaller` запускает тесты, сверяет metadata финального JAR, собирает app-image и Inno Setup EXE, пишет SHA-256 и `appfleet-manifest.json`, затем проверяет ровно три файла в `dist/release/<version>`. В manifest фиксируются `desktopShortcutTask: "desktopicon"` и baseline `minimumAppFleetVersion: "2.0.0"`. После download между GitHub Actions jobs отдельный PowerShell-скрипт повторно проверяет имена, размер, SHA-256 и manifest.
5. Переносимые `settings.json` и `resume.json` находятся в `%APPDATA%\PashaApps\ScreenPilot`; логи, recovery и lock — в `%LOCALAPPDATA%\PashaApps\ScreenPilot`. При первом запуске данные из старого `%LOCALAPPDATA%\ScreenPilot` только копируются при отсутствии целевого файла. Старые данные не удаляются и новые не перезаписываются.

## Последствия

- WiX не требуется для нового конвейера.
- Стандарт содержит пример директивы `UninstallDisplayVersion`, но Inno Setup 6.7.1 её не поддерживает. Версию записи Windows Uninstall Inno берёт из обязательной `AppVersion`; отдельную неподдерживаемую директиву не добавляем.
- Пользователь получает стандартное обновление поверх будущих AppFleet-релизов без второй записи Windows и без потери настроек.
- Установщик можно собрать локально с Inno Setup 6; его фактическая чистая установка, обновление и удаление требуют отдельной ручной проверки.
- Workflow GitHub Actions получает версию из того же `gradle.properties`, а при теге однократно переопределяет её номером тега; перед сборкой он скачивает архив exact pinned mpv из официально рекомендованного Windows build, проверяет SHA-256 архива и каждого нужного файла, затем собирает те же три артефакта. Push в `main` сохраняет artifact; тег `vMAJOR.MINOR.PATCH` создаёт GitHub Release. Runtime не находится в истории Git, поскольку `mpv.exe` превышает лимит обычного GitHub Git в 100 МБ. Лицензионные notices остаются обязательной проверкой перед первым публичным тегом.
