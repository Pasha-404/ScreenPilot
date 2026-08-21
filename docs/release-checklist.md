# Чек-лист выпуска 0.1.0

Статусы: `PASS`, `FAIL`, `NOT TESTED`, `BLOCKED`.

| Пункт | Статус | Результат |
|---|---|---|
| Чистая сборка и unit/component tests | PASS | `clean test integrationTest` проходит на JDK 21.0.10. |
| Известный дефект повторного старта после EOF | PASS | Добавлен регрессионный тест: при каждой обычной загрузке mpv получает `set_property pause false`. |
| Resume: «С начала» | PASS | Автотест подтверждает удаление сохранённой позиции после выбора «С начала». |
| Повреждённые settings/recovery | PASS | Тесты persistence quarantine повреждённые JSON и загружают безопасные defaults. |
| Один экземпляр | PASS | Автотест удерживает lock, отклоняет второго владельца и разрешает запуск после освобождения. |
| Логи и диагностика | PASS | Logback, ротация и redaction отчёта покрыты сборкой и unit-тестом. |
| Проверка hash mpv | PASS | `verifyMpvRuntime` сверил три зафиксированных SHA-256 до упаковки. |
| Self-contained app image | PASS | `packageAppImage` собран; скрытый startup smoke успешно запустил упакованный UI на Windows 10 без HDMI. |
| Per-user EXE installer | PASS | WiX 3.14 и `jpackage` собрали `ScreenPilot-0.1.0.exe`. |
| Чистая install/update/uninstall | NOT TESTED | Установщик собран, но в профиль пользователя не устанавливался во время разработки. |
| HDMI-аудио на устройстве с динамиками | NOT TESTED | У доступного монитора нет подтверждённых динамиков. |
| Реальный телевизор | NOT TESTED | Телевизор пока недоступен. |
| UI hot unplug/replug | NOT TESTED | Технический контур проверялся ранее; новый UI/release build физически не проверялся. |
| Непрерывное воспроизведение 4 часа | NOT TESTED | Требует длительной аппаратной проверки. |
| Windows 11 smoke | NOT TESTED | Текущая среда — Windows 10 build 19045. |
