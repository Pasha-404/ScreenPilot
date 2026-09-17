# Runtime mpv

`mpv.exe` и связанные с ним файлы не хранятся в Git: один `mpv.exe` имеет размер больше лимита обычного GitHub Git в 100 МБ. Сценарий [prepare-mpv-runtime.ps1](../../packaging/prepare-mpv-runtime.ps1) скачивает точно закреплённый архив, сверяет SHA-256 архива и файлов runtime, затем кладёт их в `runtime/`. Приложение никогда не скачивает mpv во время работы.

Задача `buildWindowsInstaller` повторно сверяет хэши, кладёт runtime и notices в self-contained app-image и передаёт их Inno Setup. GitHub Actions запускает тот же сценарий до сборки релиза.

| Поле | Значение |
|---|---|
| Источник build | [shinchiro/mpv-winbuild-cmake, release 20260610](https://github.com/shinchiro/mpv-winbuild-cmake/releases/tag/20260610) |
| Почему выбран | Этот источник указан в [официальной инструкции mpv для Windows](https://mpv.io/installation/). |
| Архив | `mpv-x86_64-20260610-git-304426c.7z` |
| SHA-256 архива | `facac536baa73c7b925771af5e39a3c9cb16b8d75b59a6e9800de89799dffca7` (опубликован GitHub Release API) |
| Версия mpv | `v0.41.0-744-g304426c39`, built 2026-06-10 |
| SHA-256 `mpv.exe` | `b0bb2dc1928e6d86cc26d950815c80c977440081e814c6a46e93f6e9e99c276d` |
| SHA-256 `d3dcompiler_43.dll` | `4b074a3976399dc735484f5d43d04b519b7bdee8ac719d9ab8ed6bd4e6be0345` |
| SHA-256 `mpv/fonts.conf` | `f141c1b89b172d22f213531646c21e288f0ebf3ec46484698896e1b33c626756` |
| Дата загрузки | 2026-08-04 |
| Лицензии | См. `THIRD_PARTY_NOTICES.md`; перед первым публичным тегом требуется проверить комплект уведомлений. |

Проверка hash выполняется до распаковки. Архив и распакованный runtime в репозиторий не добавляются.

Восстановить runtime вручную:

```powershell
.\packaging\prepare-mpv-runtime.ps1
```

Ручной запуск проверки:

```powershell
.\gradlew.bat :screenpilot-app:run --args="mpv-spike"
```

В текущем хосте Codex дочерний процесс уже принадлежит внешнему Windows Job Object. Чтобы отдельно проверить mpv/IPC, не скрывая это ограничение, разрешён только для технической диагностики явный флаг:

```powershell
.\gradlew.bat :screenpilot-app:run --args="mpv-spike --without-job-object"
```
