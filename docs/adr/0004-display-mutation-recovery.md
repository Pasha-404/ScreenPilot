# ADR-0004: Временное изменение дисплея только с журналом восстановления

- Статус: `ACCEPTED`
- Дата: 2026-08-05 (уточнён 2026-09-16)

## Контекст

Этап 5 должен временно подготовить один выбранный внешний экран для вывода видео, а затем вернуть исходную конфигурацию Windows. Изменение topology или режима без сохранённого состояния опасно: приложение может быть остановлено, драйвер — отклонить режим, а монитор — отключиться во время операции.

Историческая запись о том, что AMD-драйвер всегда возвращает `ERROR_GEN_FAILURE` для `DisplayConfigGetDeviceInfo(GET_SOURCE_NAME)`, отозвана: её основой было неверное JNA-описание native-пакетов (ADR-0002). Изменение экрана по-прежнему допускается только после фактической проверки связи target с `\\.\DISPLAYn`; `FALLBACK` остаётся недостаточным основанием для автоматического действия.

## Решение

1. Перед первым изменением в сессии ScreenPilot захватывает lossless-снимок: байты активных `DISPLAYCONFIG_PATH_INFO` и `DISPLAYCONFIG_MODE_INFO`, topology ID из CCD database, текущие `DEVMODEW` всех активных source, выбранный target и fingerprint topology.
2. Снимок сериализуется в версионированный payload, защищённый SHA-256 существующего `RecoveryRecord`, и атомарно записывается в `%LOCALAPPDATA%\ScreenPilot\recovery\display-session.json` **до** вызова изменяющего Win32 API. Незавершённая запись не перезаписывается.
3. Переход к Extend использует только временные флаги `SDC_APPLY | SDC_TOPOLOGY_EXTEND`; `SDC_SAVE_TO_DATABASE` не применяется.
4. Режим проверяется вызовом `ChangeDisplaySettingsExW(..., CDS_TEST, ...)`, применяется динамически без `CDS_UPDATEREGISTRY` и затем проверяется через повторное read-only обнаружение с точностью частоты ±0,02 Гц.
5. Восстановление сначала валидирует supplied snapshot (`SDC_VALIDATE | SDC_USE_SUPPLIED_DISPLAY_CONFIG`), затем применяет его. При невалидном снимке выполняется fallback `SDC_APPLY | SDC_USE_DATABASE_CURRENT`. После fallback ScreenPilot **не** применяет сохранённый `DEVMODEW`: старое GDI-имя может уже относиться к другому output. Журнал закрывается только после подтверждённого восстановления; иначе сохраняется для следующего запуска и ручного действия `Win + P`.
6. `DISPLAYCONFIG` source ID не интерпретируется как индекс `EnumDisplayDevicesW`. При недоступном `GET_SOURCE_NAME` GDI-связь остаётся `UNAVAILABLE`; опасные операции с режимом допустимы только после повторного read-only обнаружения active target с `AUTHORITATIVE` связью. Явное подтверждение пользователя не повышает степень достоверности идентификатора.
7. На следующем запуске незавершённый журнал блокирует обычную работу до выбора: восстановить, оставить конфигурацию как есть или посмотреть детали. Закрытые записи хранятся в архиве семь дней.
8. `DEVMODEW` не может выразить дробную частоту как `60 000/1 001`. Такой режим нельзя округлять при вызове `ChangeDisplaySettingsExW`: техническая команда и будущая логика режима принимают только подтверждённое драйвером целочисленное значение либо отказываются от операции.
9. Выбранный target в исходном snapshot может быть неактивен или участвовать в clone, поэтому у него может не быть GDI-имени и `DEVMODEW`. После временного Extend ScreenPilot повторно находит именно этот target по `adapter LUID + target ID`, требует отдельный active source и только тогда меняет его режим. При rollback неактивный исходный target проверяется по исходному fingerprint topology, а не по несуществующему GDI-режиму.
10. Перед тем как native bytes из recovery payload попадут в `SetDisplayConfig`, проверяются версия, exact-length массивов `DISPLAYCONFIG_*`, число и индексы mode entries, активность path, присутствие selected target в topology и размер/уникальность сохранённых `DEVMODEW`. Подпись журнала не заменяет эту семантическую проверку.

Текущий технический CLI требует флаг `--confirm` для любой команды, меняющей дисплей. Будущий JavaFX-диалог заменит этот флаг явной кнопкой подтверждения, но сохранит те же инварианты.

## Последствия

- Нормальный тест не вызывает API изменения дисплея. Управляемая аппаратная проверка запускается отдельной явной командой при подключённом внешнем экране.
- Встроенная панель никогда не получает новый `DEVMODEW` и не становится target операции.
- При отключении монитора во время операции допустимо, что supplied snapshot устареет. Тогда журнал остаётся активным до успешного fallback/recovery; это безопаснее ложного сообщения об успехе.
- При недоступной authoritative GDI-связи ScreenPilot не меняет режим target; пользователь видит безопасную причину и может настроить topology в Windows, затем обновить список экранов.
- Если после `SDC_TOPOLOGY_EXTEND` Windows активировала не выбранный target, ScreenPilot не продолжает к смене режима: выполняется rollback, а пользователь должен повторно выбрать экран. Это безопаснее неявного вывода на другой монитор.

## Аппаратная проверка

На эталонном Windows 10 build 19045 HDMI target в режиме clone не имел отдельного GDI-имени. `display-extend-smoke --confirm --hold-ms=3000` временно активировал тот же target как `\\.\DISPLAY2`, затем восстановил исходный clone и закрыл recovery journal. Отдельная команда polling также увидела физическое отключение HDMI на следующем секундном опросе. `output-hot-unplug-smoke` подтвердил безопасную остановку активного mpv-процесса после отключения выбранного target: `WindowsDisplayPoller` обнаружил потерю HDMI, mpv остановлен, Job Object освобождён. Команда не меняла display configuration. Детали — в [hardware-test-checklist.md](../hardware-test-checklist.md).

## Источники

- [Microsoft: SetDisplayConfig](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-setdisplayconfig)
- [Microsoft: ChangeDisplaySettingsExW](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-changedisplaysettingsexw)
- [Microsoft: QueryDisplayConfig](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-querydisplayconfig)
