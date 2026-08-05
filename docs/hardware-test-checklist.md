# Аппаратный чек-лист прототипа mpv/Windows

Статусы: `PASS`, `FAIL`, `NOT TESTED`, `BLOCKED`.

| Проверка | Статус | Фактический результат |
|---|---|---|
| JDK 21 и Gradle-каркас | PASS | JDK `21.0.10`; `clean test integrationTest` проходит. |
| Запуск mpv без пользовательского config | PASS | `mpv v0.41.0-744-g304426c39`, флаг `--no-config`. |
| JSON IPC по Windows named pipe | PASS | Проверены JSON command/response с `request_id`. |
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
| `DisplayConfigGetDeviceInfo` metadata | PASS | Вызовы реализованы и выполнены. На текущем AMD-драйвере все пакеты `GET_*` вернули `ERROR_GEN_FAILURE` (31), поэтому имя/EDID/preferred mode/Advanced Color отсутствуют, а GDI-связь явно помечена `FALLBACK`; read-only fallback через `EnumDisplayDevicesW` и `EnumDisplaySettingsExW` подтвердил `DISPLAY2`. |
| Отключение HDMI во время polling | NOT TESTED | Монитор был нужен владельцу, кабель во время проверки не отсоединялся. Нужна отдельная ручная проверка обнаружения отключения не позднее 2 секунд. |

Эталонная среда: Windows 10 Pro 22H2 build 19045; AMD Radeon Graphics driver `31.0.12046.15003`; mpv `v0.41.0-744-g304426c39`. Windows видит внешний HDMI `DISPLAY2` 1920×1080 @ 59,940 Гц в extended mode.
