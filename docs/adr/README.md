# Architecture Decision Records

В этом каталоге фиксируются решения, которые влияют на архитектуру, совместимость, лицензирование или поведение на реальном оборудовании.

Каждый ADR содержит:

1. Контекст и проблему.
2. Рассмотренные варианты.
3. Решение и причину выбора.
4. Последствия и риски.
5. Ссылки на тесты, команды и реальные аппаратные результаты.

Первый обязательный ADR: `0001-video-engine.md`.

- `0002-display-discovery-fallback.md` — работа с драйвером, который отдаёт paths, но отклоняет пакеты `DisplayConfigGetDeviceInfo`.
- `0003-mpv-overlapped-ipc.md` — асинхронный JSON IPC для Windows named pipe в production-адаптере mpv.
- `0004-display-mutation-recovery.md` — временные изменения topology/режима только после snapshot и с recovery journal.
- `0005-javafx-ui-state-and-output-placement.md` — единое состояние JavaFX и обязательная проверка размещения окна mpv на физическом target.
- `0006-playlist-resume-and-metadata-probe.md` — in-memory плейлист, resume и отдельный headless mpv для параметров файлов.
- `0007-release-packaging-and-hardening.md` — single instance, логи и диагностика, устранение stale pause, jpackage и проверка runtime.
