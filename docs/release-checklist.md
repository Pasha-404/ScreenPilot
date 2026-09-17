# Чек-лист выпуска 0.1.2

Статусы: `PASS`, `FAIL`, `NOT TESTED`, `BLOCKED`.

| Пункт | Статус | Результат |
|---|---|---|
| Чистая сборка и unit/component tests | PASS | 17.09.2026 `clean test integrationTest` прошла на JDK 21.0.10. |
| Известный дефект повторного старта после EOF | PASS | Добавлен регрессионный тест: при каждой обычной загрузке mpv получает `set_property pause false`. |
| Resume: «С начала» | PASS | Автотест подтверждает удаление сохранённой позиции после выбора «С начала». |
| Повреждённые settings/recovery | PASS | Тесты persistence quarantine повреждённые JSON и загружают безопасные defaults. |
| Один экземпляр | PASS | Автотест удерживает lock, отклоняет второго владельца и разрешает запуск после освобождения. |
| Логи и диагностика | PASS | Logback, ротация и redaction отчёта покрыты сборкой и unit-тестом. |
| Проверка hash mpv | PASS | `verifyMpvRuntime` сверил три зафиксированных SHA-256 до упаковки. |
| Self-contained app image и нативная иконка | PASS | 17.09.2026 `packageAppImage` собрал self-contained image; `verifyAppImageNativeIcon` открыл финальный `ScreenPilot.exe` как resource module и подтвердил `RT_GROUP_ICON`, `RT_ICON` и слой 256×256. Запуск упакованного UI после последних изменений — `NOT TESTED`. |
| Per-user EXE installer | PASS | 17.09.2026 Inno Setup 6.7.1 собрал `ScreenPilot-Setup-0.1.2-x64.exe` из self-contained app-image. Это подтверждает упаковку, но не заменяет проверку установки. |
| AppFleet manifest и SHA-256 | PASS | `buildWindowsInstaller` и отдельный `packaging/verify-release-assets.ps1` подтвердили ровно три asset, их размер, имя, SHA-256, manifest schema 1, `desktopShortcutTask=desktopicon`, `minimumAppFleetVersion=2.0.0` и metadata финального JAR. |
| GitHub Actions: сборка и GitHub Release | NOT TESTED | Тег `v0.1.1` подтвердил успешную cloud-сборку, но выявил ошибку workflow: в задаче публикации отсутствовал checkout скрипта проверки. Исправление выпускается как новый неизменяемый тег `v0.1.2`; hardware-тесты запускаются только с `-PincludeHardwareTests=true`. |
| Notices и исходники для публичной поставки mpv | NOT TESTED | Закреплённая Windows-сборка mpv — отдельный сторонний binary; `THIRD_PARTY_NOTICES.md` содержит известные ограничения, а формальная юридическая проверка полного комплекта notices/SBOM и условий для `d3dcompiler_43.dll` не выполнялась. Владелец проекта явно подтвердил публикацию установщика на GitHub. |
| Чистая install/update/uninstall | NOT TESTED | Не выполнять в рабочем профиле без отдельной ручной проверки; нужны чистый профиль или VM. |
| HDMI-аудио на устройстве с динамиками | NOT TESTED | У доступного монитора нет подтверждённых динамиков. |
| Реальный телевизор | NOT TESTED | Телевизор пока недоступен. |
| UI hot unplug/replug | NOT TESTED | Технический контур проверялся ранее; новый UI/release build физически не проверялся. |
| Непрерывное воспроизведение 4 часа | NOT TESTED | Требует длительной аппаратной проверки. |
| Windows 11 smoke | NOT TESTED | Текущая среда — Windows 10 build 19045. |
