# ADR-0001: mpv как кандидат видеодвижка

- Статус: `ACCEPTED FOR MVP DEVELOPMENT`
- Дата: 2026-08-04
- Решение не считается принятым для MVP до закрытия всех `NOT TESTED` P0-пунктов ниже.

## Контекст

ScreenPilot должен вывести единственный видеопоток в отдельное окно на выбранном внешнем экране, без прав администратора и без изменения Windows default audio. До реализации JavaFX требовалось подтвердить, что Windows-сборка mpv управляется Java через JSON IPC, стабильно использует D3D11VA и может быть завершена без orphan process.

Проверка выполняется на эталонном ноутбуке: Windows 10 Pro 22H2 (build 19045), AMD Radeon Graphics driver `31.0.12046.15003`, JDK `21.0.10`. На 5 августа Windows видит внешний `DISPLAY2` 1920×1080 в extended mode; тип подключения ещё должен подтвердить будущий read-only display adapter.

## Рассмотренные варианты

1. Отдельный `mpv.exe` + JSON IPC named pipe.
2. `libmpv` через JNI/JNA.
3. VLCJ/VLC.

## Предварительное решение

Для продолжения исследований выбран вариант 1: отдельный поставляемый `mpv.exe`, запуск без пользовательского config и управление исключительно через локальный named pipe `\\.\pipe\screenpilot-mpv-<UUID>`. Он не требует embedding чужого native-окна в JavaFX и позволяет изолировать crash медиадвижка.

Выбран runtime `mpv-x86_64-20260610-git-304426c.7z` из [Windows builds by shinchiro](https://github.com/shinchiro/mpv-winbuild-cmake/releases/tag/20260610), который указан на [странице установки mpv](https://mpv.io/installation/). Версия и SHA-256 зафиксированы в [vendor/mpv/README.md](../../vendor/mpv/README.md).

Фактическая командная строка (суффикс pipe и title уникальны для запуска):

```text
mpv.exe --no-config --idle=yes --force-window=immediate --keep-open=yes --terminal=no --input-default-bindings=no --osc=no --no-border --ontop=yes --vo=gpu-next --gpu-context=d3d11 --hwdec=auto-safe --audio-client-name=ScreenPilot --input-ipc-server=\\.\pipe\screenpilot-mpv-<UUID> --title="ScreenPilot mpv spike <UUID>"
```

## Результаты gate

| P0-проверка | Статус | Фактический результат |
|---|---|---|
| Запуск без пользовательского config | PASS | `--no-config`; `mpv v0.41.0-744-g304426c39` стартует. |
| Java → JSON IPC named pipe | PASS | Команды коррелируются `request_id`, mpv отвечает. |
| `loadfile`, pause, seek, `time-pos` | PASS | На сгенерированном 3-секундном WAV: duration `3.0`, pause/seek/readback успешны. |
| H.264 | PASS | Сгенерированный `640×360` H.264 8-bit ролик декодирован, `hwdec-current="d3d11va"`. |
| HEVC Main 10 | PASS | Сгенерированный `640×360` HEVC Main 10 (`p010`) ролик декодирован, `hwdec-current="d3d11va"`. |
| `audio-device-list` и выбор audio device только в mpv | PASS | WASAPI device получен и выбран через `set_property audio-device`; Windows default audio не менялся. |
| Внешний SRT | PASS | Временный SRT принят командой `sub-add`. |
| Embedded subtitles | PASS | В сгенерированном временном MKV mpv вернул embedded SubRip-дорожку в `track-list` (`external=false`) и подтвердил её выбор через `sid=1`. |
| Окно только на выбранном external target | PASS | При активном `DISPLAY2` mpv запущен с `--screen=1`; наблюдатель подтвердил полноэкранное окно только на внешнем экране и отсутствие видео на ноутбуке. Stage 3 ещё не реализован. |
| 10 повторных запусков на target | PASS | Десять `--screen=1 --repeat=10` завершились без ошибок; наблюдатель подтвердил размещение на внешнем экране, а после теста `mpv.exe` не остался. |
| HDR10 → SDR tone mapping | PASS | На SDR external monitor воспроизведён синтетический HEVC HDR10 fixture: 10-bit P010, BT.2020, PQ, peak 1 000 нит; mpv использовал D3D11VA. Наблюдатель подтвердил нормальное цветное изображение без серой пелены или сплошных белых областей. Это smoke-проверка, а не замена художественного HDR10-контента перед релизом. |
| HDMI audio | NOT TESTED | mpv нашёл и выбрал только для своего процесса display-audio WASAPI endpoint `P27FBB-RG (AMD High Definition Audio Device)`. Физическое воспроизведение звука не проверено: у доступного монитора нет подтверждённых динамиков. Владелец проекта 05.08.2026 явно отложил эту проверку до появления подходящего оборудования. |
| Job Object убивает mpv при аварийном завершении Java | PASS | В обычном PowerShell вне Codex Job Object назначен успешно; spike намеренно завершил Java через `Runtime.halt(86)`, после чего проверка не нашла нового `mpv.exe`. |

## Последствия и риски

- Текущий код — технический прототип, не JavaFX UI. Основной поток: `MpvSpikeMain` → `MpvProcessLauncher` → `MpvIpcClient`; Windows Job Object изолирован в `screenpilot-platform-windows`.
- Runtime намеренно исключён из Git. Лицензионные notices пока недостаточны для установки пользователю: перед этапом installer нужен полный SBOM/набор notices для точной сборки.
- `--without-job-object` существует только для диагностики в окружении Codex. В нормальном пути он не применяется и не может считаться проверкой containment.
- Если hardware-check покажет ненадёжное размещение окна или выбор HDMI audio, нужно остановить принятие mpv и сравнить `libmpv`/VLCJ по фактическим данным.

## Решение gate

`ПРИНЯТО ДЛЯ РАЗРАБОТКИ MVP`. Все доступные P0-пункты прототипа прошли на эталонном ноутбуке и внешнем SDR-экране. Единственное исключение — физический звук: он явно отложен владельцем проекта и остаётся `NOT TESTED` до релизной приёмки.

### Зафиксированное исключение: физический звук

Владелец проекта 05.08.2026 разрешил продолжать разработку без физической проверки display-audio: подходящего телевизора или монитора с подтверждённо работающими динамиками в обозримом будущем нет. Это не отменяет функцию выбора аудиоустройства и не превращает проверку в `PASS`: она остаётся `NOT TESTED` и должна быть выполнена до релизной приёмки. Для решения текущего gate она считается отложенным ограничением, а не блокером.
