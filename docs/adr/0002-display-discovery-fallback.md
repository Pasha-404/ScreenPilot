# ADR-0002: Read-only discovery при недоступном `DisplayConfigGetDeviceInfo`

- Статус: `ACCEPTED`
- Дата: 2026-08-05

## Контекст

Этап 3 обязан получать topology через Windows Display Configuration API, вызывать `DisplayConfigGetDeviceInfo`, получать GDI-имя и режимы, классифицировать HDMI и обнаруживать изменения раз в секунду. Это нужно выполнить без каких-либо изменений конфигурации Windows.

На эталонном ноутбуке Windows 10 build 19045 с AMD Radeon Graphics `QueryDisplayConfig` успешно вернул активные internal и HDMI paths. Внешний target имеет ID `257`, `DISPLAYCONFIG_OUTPUT_TECHNOLOGY_HDMI`, а его текущая частота из path — 59,940 Гц. Однако каждый запрос `DISPLAYCONFIG_DEVICE_INFO_GET_TARGET_NAME`, `GET_SOURCE_NAME`, `GET_TARGET_PREFERRED_MODE` и `GET_ADVANCED_COLOR_INFO` завершился `ERROR_GEN_FAILURE` (31). Независимый одноразовый P/Invoke-проверочный вызов дал тот же результат, поэтому это не считается ошибкой JNA layout.

## Варианты

1. Считать любую ошибку `DisplayConfigGetDeviceInfo` фатальной и не показывать topology.
2. Заменить Display Configuration API реестром, WMI или сторонней библиотекой.
3. Оставить Display Configuration API авторитетным источником paths, всегда пытаться получить metadata и при отказе драйвера безопасно перейти к GDI-only read-only диагностике.

## Решение

Выбран вариант 3.

- Для каждого цикла вызываются `GetDisplayConfigBufferSizes` и `QueryDisplayConfig(QDC_ALL_PATHS | QDC_VIRTUAL_MODE_AWARE)` с retry при `ERROR_INSUFFICIENT_BUFFER`.
- Для каждого target выполняются все четыре предусмотренных `DisplayConfigGetDeviceInfo` запроса. Успешные данные имеют приоритет.
- Если драйвер отклоняет metadata, connection type, active/available state, target ID и рациональная текущая частота остаются данными из `DISPLAYCONFIG_PATH_INFO`.
- `EnumDisplayDevicesW` и `EnumDisplaySettingsExW` применяются только как fallback для безопасного имени, GDI-диагностики, bounds и списка режимов.
- В модели есть `GdiMappingConfidence`: `AUTHORITATIVE`, `FALLBACK` или `UNAVAILABLE`. На эталонном драйвере оба активных экрана имеют `FALLBACK`.
- Poller каждые 1 с вычисляет SHA-256 значимых path-полей. Полное перечисление GDI-режимов происходит только на первом снимке или при изменении fingerprint.

## Последствия и ограничения

- При `FALLBACK` идентификатор строится из adapter LUID и target ID, поэтому он не считается постоянным между перезагрузками.
- До этапа 5 ScreenPilot не выполняет display mutation. На этапе 5 автоматическое изменение topology или размещение output не должно опираться только на `FALLBACK`: потребуется авторитетная привязка либо дополнительная явная проверка до действия.
- Неактивные targets без metadata остаются видимыми, но не получают выдуманные preferred mode, HDR или EDID-значения.
- Этот fallback не заменяет проверку hot unplug: она остаётся `NOT TESTED`, пока кабель не будет безопасно отключён во время ручного теста.

## Проверка

Выполнены:

```powershell
.\gradlew.bat --no-daemon :screenpilot-platform-windows:test
.\gradlew.bat --no-daemon :screenpilot-platform-windows:integrationTest
.\gradlew.bat --no-daemon :screenpilot-app:run --args="display-probe"
.\gradlew.bat --no-daemon :screenpilot-app:run --args="display-poll-smoke"
```

Все команды прошли. Подробный фактический результат — в [hardware-test-checklist.md](../hardware-test-checklist.md).

## Источники

- [Microsoft: QueryDisplayConfig](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-querydisplayconfig)
- [Microsoft: DisplayConfigGetDeviceInfo](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-displayconfiggetdeviceinfo)
- [Microsoft: DISPLAYCONFIG_TARGET_DEVICE_NAME](https://learn.microsoft.com/en-us/windows/win32/api/wingdi/ns-wingdi-displayconfig_target_device_name)
- [Microsoft: EnumDisplaySettingsExW](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-enumdisplaysettingsexw)
