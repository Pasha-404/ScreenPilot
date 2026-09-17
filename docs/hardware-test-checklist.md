# Аппаратный чек-лист прототипа mpv/Windows

Статусы: `PASS`, `FAIL`, `NOT TESTED`, `BLOCKED`.

| Проверка | Статус | Фактический результат |
|---|---|---|
| JDK 21 и Gradle-каркас | PASS | JDK `21.0.10`; `clean test integrationTest` проходит. |
| Запуск mpv без пользовательского config | PASS | `mpv v0.41.0-744-g304426c39`, флаг `--no-config`. |
| JSON IPC по Windows named pipe | PASS | Проверены JSON command/response с `request_id`. |
| Production mpv adapter | PASS | Реальный `MpvPlayerAdapterIntegrationTest`: `mpv.exe` через Windows overlapped I/O выполнил load, pause, seek, play и stop с `--vo=null --ao=null`; настройки Windows не менялись. |
| H.264 playback | PASS | Локальный 640×360 H.264 fixture; `hwdec-current="d3d11va"`. |
| HEVC Main 10 playback | PASS | Локальный 640×360 HEVC Main 10 fixture; `hwdec-current="d3d11va"`. |
| Фактический hwdec | PASS | D3D11VA подтверждён для H.264 и HEVC Main 10. |
| HDR10 → SDR tone mapping | PASS | Синтетический HEVC HDR10 fixture: 10-bit P010, BT.2020, PQ, peak 1 000 нит; mpv использовал D3D11VA. На SDR external monitor наблюдатель подтвердил нормальное цветное изображение без серой пелены или сплошных белых областей. |
| Выбор HDMI-аудио | NOT TESTED | mpv нашёл display-audio WASAPI endpoint `P27FBB-RG (AMD High Definition Audio Device)` и выбрал его только для своего процесса. Физический вывод звука не проверен: у доступного монитора нет подтверждённых динамиков. Владелец проекта 05.08.2026 явно отложил эту проверку до появления подходящего оборудования. |
| Встроенные и внешние субтитры | PASS | Внешний SRT принят через `sub-add`; в сгенерированном временном MKV найдена embedded SubRip-дорожка (`external=false`) и выбрана через `sid`. |
| Окно на выбранном external target | PASS | Windows видит `DISPLAY2` в extended mode. Наблюдатель подтвердил: полноэкранное окно mpv появилось только на внешнем экране, на ноутбуке видео не было. |
| Десять повторных запусков окна | PASS | Десять запусков `--screen=1 --repeat=10` завершились успешно; наблюдатель подтвердил размещение только на внешнем экране, после теста `mpv.exe` не остался. |
| Job Object завершает mpv | PASS | В обычном PowerShell вне Codex Job Object назначен успешно; тестовый Java-процесс завершён через `Runtime.halt(86)`, после чего новый `mpv.exe` не остался. |
| Read-only HDMI discovery | PASS | `QueryDisplayConfig` обнаружил активный target `257` с `DISPLAYCONFIG_OUTPUT_TECHNOLOGY_HDMI`; console probe показал внешний `Generic PnP Monitor`, `\\.\DISPLAY2`, 1920×1080 @ 59,940 Гц и 80 подтверждённых режимов. Настройки Windows не менялись. |
| Точная частота внешнего экрана | PASS | Рациональная частота из Display Configuration API: 59,940 Гц; не сведена к 59 или 60 Гц во внутренней модели. |
| Опрос topology | PASS | `display-poll-smoke` получил 3 path(s) и опубликовал первый snapshot через поток `display-poller`; параметры Windows не менялись. |
| `DisplayConfigGetDeviceInfo` metadata активного internal target | PASS | 16.09.2026 исправлены порядок полей `DISPLAYCONFIG_DEVICE_INFO_HEADER` и полный 80-байтный `DISPLAYCONFIG_TARGET_PREFERRED_MODE`. Read-only `display-probe` успешно прочитал имя/source/preferred mode активного встроенного экрана. Прежний вывод об исключительной ошибке AMD-драйвера отозван. |
| `DisplayConfigGetDeviceInfo` metadata внешнего target после исправления layout | NOT TESTED | Для повторной проверки нужен подключённый HDMI-экран. |
| Snapshot до display mutation | PASS | Перед каждым реальным Stage 5 smoke test атомарно записан `RecoveryRecord` с lossless bytes active `DISPLAYCONFIG_PATH_INFO`/`DISPLAYCONFIG_MODE_INFO`, topology ID из CCD database, исходными `DEVMODEW`, target и SHA-256. Journal закрывался только после restore. |
| Временный режим только external target | PASS | На `\\.\DISPLAY2` драйвер принял `1280×720 @ 60 Гц` после `CDS_TEST`; read-back подтвердил ровно `1280×720 @ 60,000 Гц`. Встроенный `\\.\DISPLAY1` не получал новый `DEVMODEW`. Через 3 с восстановлены исходные `1920×1080 @ 59,940 Гц`. |
| Защита от округления дробной частоты | PASS | Первый безопасный smoke выявил несоответствие: текущие 59,940 Гц драйвер принимает в `DEVMODEW` только как 60 Гц. Операция была откатана. Реализация изменена: дробный `DisplayMode` не округляется и не передаётся в `ChangeDisplaySettingsExW`; разрешены только явно перечисленные драйвером modes. |
| Active Extend topology | PASS | `display-extend-smoke` сохранил snapshot, подтвердил отдельные active internal/external desktop и успешно восстановил snapshot. Исходная topology уже была Extend, поэтому вызов `SetDisplayConfig(SDC_TOPOLOGY_EXTEND)` не потребовался. |
| Recovery после аварийной остановки | PASS | После успешного применения `1280×720 @ 60 Гц` Java-процесс намеренно завершён `Runtime.halt(86)`. Следующий запуск `display-recover --confirm` восстановил исходную конфигурацию по persisted journal и закрыл его. |
| Переход из clone к Extend и rollback | PASS | HDMI target `257` в clone не имел отдельного GDI-имени и режима. `display-extend-smoke` сохранил snapshot, временно включил Extend, повторно обнаружил тот же target как `\\.\DISPLAY2`, удержал состояние 3 с и восстановил исходный clone. Journal закрыт. |
| Отключение HDMI во время polling | PASS | Во время `display-hot-unplug-watch --hold-ms=30000` кабель физически отключён. `display-poller` обнаружил исчезновение target `\\.\DISPLAY2` на своём секундном интервале; Java-процесс завершился штатно. |
| Отключение HDMI во время активного video output | PASS | `output-hot-unplug-smoke` запустил локальный MP4 на mpv screen `1` и сопоставил HDMI `\\.\DISPLAY2` по `DisplayTargetAddress`. При физическом отключении кабеля `WindowsDisplayPoller` увидел потерю target, mpv был остановлен, а Job Object освобождён. |
| UI этапа 6: запуск одного файла на выбранном HDMI-target | PASS | 07.08.2026: в JavaFX UI выбран локальный файл и `Generic PnP Monitor` (HDMI, `DISPLAY2`) в extended mode. Видео открылось на внешнем мониторе; на ноутбуке остался только UI. |
| UI этапа 6: пауза и перемотка ±10 секунд | PASS | 07.08.2026: пользователь подтвердил работу паузы, «+10 с» и «−10 с» во время реального воспроизведения. |
| UI этапа 6: «Остановить вывод» и сохранение extended mode | PASS | 07.08.2026: кнопка закрыла воспроизведение/mpv, оставила HDMI-монитор в extended mode и вернула UI в исходное состояние. После закрытия приложения journal recovery был пуст. |
| UI этапа 6: локальные hotkeys | PASS | Пользователь подтвердил работу горячих клавиш после ручной проверки 07.08.2026. |
| UI этапа 6: масштабирование и громкость | NOT TESTED | Controls реализованы, но ручная проверка на внешнем экране ещё не выполнялась. |
| UI этапа 6: отключение HDMI во время UI-сессии | NOT TESTED | Тот же production-контур mpv/poller уже проверен техническим `output-hot-unplug-smoke`; именно пользовательский UI-сценарий не повторялся. |
| UI этапа 6: повторный запуск после «Стоп» и смена выбранного режима | NOT TESTED | После сборки 0.1.0 исправлена явная команда `pause=false` при каждой загрузке mpv и добавлен регрессионный тест. Повторная физическая проверка с HDMI ещё нужна. |
| UI этапа 7: плейлист и auto-next | PASS | Пользователь вручную проверил добавление, дубликаты, перестановку, удаление и реальный auto-next двух видео на внешнем HDMI-мониторе. |
| UI этапа 7: resume | NOT TESTED | До исправления 0.1.0 после остановки повторный старт начинал файл с начала без вопроса. Теперь позиция перепроверяется перед стартом и есть unit-тест выбора «С начала», но физическая проверка на HDMI нужна. |
| UI этапа 7: внешние субтитры | NOT TESTED | Команда mpv `sub-add` и unit/integration tests реализованы; подходящий фильм с субтитрами пока не проверялся вручную. |
| UI этапа 7: drag-and-drop | PASS | Пользователь вручную проверил перетаскивание файлов и перестановку строк плейлиста. |
| Этап 8: single instance | PASS | Unit-тест проверяет file lock `%LOCALAPPDATA%\PashaApps\ScreenPilot\screenpilot.lock`, отказ второго владельца и освобождение после закрытия. |
| Этап 8: logs и диагностика | PASS | 0.1.0 пишет UTF-8 log с ротацией; unit-тест подтверждает вырезание Windows-путей из отчёта. |
| Этап 8: долгий запуск / UI hot unplug-replug / Windows 11 | NOT TESTED | Требуются отдельные физические проверки; их отсутствие не маскируется автоматическими тестами. |
| Этап 9: app image и per-user EXE | PASS | 29.08.2026 `buildWindowsInstaller` собрал self-contained app-image и Inno Setup EXE. Чистая установка, обновление и удаление остаются `NOT TESTED`. |
| Release 0.1.3: нативная иконка EXE | PASS | 17.09.2026 `verifyAppImageNativeIcon` открыл упакованный `ScreenPilot.exe` как ресурсный модуль Windows и подтвердил `RT_GROUP_ICON`, `RT_ICON`, слой 256×256. Визуальное соответствие иконки на чистой системе остаётся `NOT TESTED`. |
| Release 0.1.3: точное размещение окна mpv по PID и physical bounds | NOT TESTED | Автотесты проверяют фильтрацию по PID, exact `SetWindowPos` и read-back bounds без JavaFX logical coordinates. Физический монитор с mixed DPI после этой реализации не подключён. |
| Release 0.1.3: карточка незавершённого recovery | NOT TESTED | Есть unit-тест, что pending journal блокирует старт output, и реализация требует явного выбора «Восстановить» либо «Оставить текущую конфигурацию». Проверка на реальном stale journal/дисплее не выполнялась. |
| Этап 9: clean install/update/uninstall | NOT TESTED | EXE намеренно не устанавливался в рабочий профиль во время сборки. |

Эталонная среда: Windows 10 Pro 22H2 build 19045; AMD Radeon Graphics driver `31.0.12046.15003`; mpv `v0.41.0-744-g304426c39`. Windows видит внешний HDMI `DISPLAY2` 1920×1080 @ 59,940 Гц в extended mode.
