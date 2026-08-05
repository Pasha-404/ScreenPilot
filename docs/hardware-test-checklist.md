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

Эталонная среда: Windows 10 Pro 22H2 build 19045; AMD Radeon Graphics driver `31.0.12046.15003`; mpv `v0.41.0-744-g304426c39`. Windows видит внешний `DISPLAY2` 1920×1080 в extended mode; тип подключения ещё не зафиксирован будущим read-only display adapter.
