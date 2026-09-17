# ScreenPilot — GPT-6 UI/UX review

Review date: 2026-09-16. Target: current working tree based on `f8c89d8`, including existing uncommitted changes. Companion: [technical review](C:/MyProjects/ScreenPilot/docs/GPT-6_CODE_REVIEW.md).

## Assessment and evidence boundary

The interface communicates the basic purpose: choose a local video and play it on an external display. The absence of a laptop preview is intentional and should remain. Separating media/transport controls from output configuration is a useful starting point; it does not need a cosmetic redesign or framework replacement.

The substantive problems are readiness feedback, action/state consistency, keyboard operation and use of limited vertical space. The two supplied screenshots show a no-file/no-external-target state. The second image exposes playlist controls below the first image's visible area. They do **not** prove behavior at any particular DPI scale or during actual playback.

Reviewed implementation: `main-view.fxml`, `screenpilot.css`, `MainViewController`, `ApplicationState`, `PlaybackUiState`, `ResponsiveWindowSize`, `ScreenPilotApplication` and the application service. Current FXML-load and window-size arithmetic tests passed; these are not rendered usability or accessibility tests. No UI code was changed and no live application interaction acceptance was performed.

Priorities: P1 = safety/action correctness; P2 = essential usability before 1.0. Confidence refers to evidence, not the strength of a stylistic preference.

## U01 — P2: Make readiness and the next action explicit

**Location:** FXML top-status/output panel, CSS `.status-title`/`.top-status`, `MainViewController.applyState`, `ApplicationState.withDisplays`.

**Observed:** The screenshot has a green badge saying “Внешний экран не выбран”, empty disabled display/mode selectors, many other disabled controls, and a disabled start action. The useful instruction to connect HDMI is separated into a large status card. The green appearance is fixed in CSS rather than derived from state. “Waiting for HDMI” is also used when there may simply be multiple unselected targets.

**Consequence:** The user must infer whether hardware is absent, discovery is running, selection is needed, an error occurred or the app is ready. Most unavailable controls do not explain their prerequisite.

**Proposed solution:** Use truthful state-specific text and neutral/warning/error/success styling. Distinguish “Ищу экраны…”, “Внешний экран не подключён”, “Выберите экран”, “Экран выбран”, “Подготавливаю вывод…” and discovery failure. Place one concise prerequisite explanation next to the primary action. Empty selectors need explanatory placeholders; errors must not masquerade as empty hardware lists. Show detected connection type rather than calling every external connection HDMI.

**Regression/acceptance:** No display, one available display, multiple targets, discovery busy/failure, file missing and ready state each have an unambiguous next action. Ensure color is never the only signal.

**Confidence:** High for the screenshot and static styling. **Confirmed:** Empty-state appearance and source rules. **NOT TESTED:** Rendered connected/busy/error variants.

## U02 — P2: Keep essential controls reachable at ordinary window sizes

**Location:** `main-view.fxml` center/right ScrollPanes, card padding, fixed transport HBox and playlist height; `ResponsiveWindowSize` minimum 960×540; `ScreenPilotApplication.start`.

**Observed:** At the supplied large window size, large cards and whitespace push playlist actions below the visible area. The empty playlist occupies a substantial blank region. The main column and output panel scroll independently. The right panel already has its own ScrollPane, which is useful; the issue is not absence of scrolling.

**Consequence:** Adding/removing/reordering files requires navigating away from transport/status. At the minimum size, the nonwrapping six-button transport row and reserved output width compete for space. The width/height calculator clamps to its minimum even when the available logical desktop is smaller; it does not prove a usable layout at high scaling.

**Proposed solution:** Reduce empty-state card height and vertical padding; keep current media, transport and concise feedback compact. Give the playlist remaining space and put its toolbar beside its heading. Keep the primary start/stop area predictably reachable, while allowing secondary output settings to scroll. Add a narrow-layout behavior for transport controls rather than relying on horizontal overflow. Limit long device/file labels with a way to inspect the full value; do not allow the header badge to force the window wider.

**Regression/acceptance:** Render actual FXML/CSS at minimum and typical logical sizes, maximized and restored; include long Unicode filenames/device names and 100/125/150/200% scale. All essential controls must remain reachable without horizontal scrolling or accidental overlap. If the desktop is below the supported minimum, define a usable fallback instead of silently exceeding it.

**Confidence:** High for current screenshot inefficiency; medium for specific minimum-size overflow. **Confirmed:** Screenshot and fixed layout constraints. **NOT TESTED:** Rendered minimum-size and mixed-DPI layouts. Do not infer the screenshot's scaling factor from image dimensions.

## U03 — P1: Represent preparation, cancellation, restoration and recovery as real workflows

**Location:** `MainViewController.applyState/startOutput/confirmDisplayPreparation`, `ScreenPilotApplication` close handler, service output/recovery transitions.

**Observed in source:** During preparation/restoration, most controls are disabled and a text message changes, but Stop Output is enabled only for idle/active output. The window-close path can bypass pending restoration on a second close (technical D07). After a crash, the user is instructed to restore an earlier session but the main UI provides no recovery action. The confirmation promises restoration without explaining a possible partial failure.

**Consequence:** The user cannot distinguish slow work from a stuck operation or understand what closing will do. Recovery messages name an action they cannot take in this window.

**Proposed solution:** Show an explicit phase, such as “Проверяю экран”, “Запускаю плеер”, “Открываю файл” or “Восстанавливаю экраны”, with indeterminate progress where appropriate. Provide cancel/stop only where the operation owner can honor it safely; show “Остановка…” until confirmed. On close, keep cleanup visible and do not treat repeated close as completed cleanup. Add startup recovery choices with the actual saved/current configuration and clear failure handling. Explain the distinction between Stop Playback (black prepared output remains) and Stop Output (close player and restore Windows).

**Regression/acceptance:** Long load, failed start, unplug, delayed restore, repeated close, pending/corrupt/incompatible recovery and failed recovery. The UI must reflect confirmed native outcomes, preserve retry/decision paths and never say “restored” while restoration is still pending.

**Confidence:** High for current control rules and absent recovery UI. **Confirmed:** Static state-to-action mapping; related service cleanup fault reproduced in technical D06. **NOT TESTED:** Real hardware failure rendering. Startup recovery UI is unfinished 1.0 work, not a claim that the installer is defective.

## U04 — P2: Restore normal keyboard behavior before adding more shortcuts

**Location:** `MainViewController.handleShortcut`, scene key filter, FXML slider handlers. Technical finding D15.

**Problem:** The scene-level filter consumes Space/arrows/Esc for almost every focused control. Only text inputs are excluded. This conflicts with playlist navigation, combobox selection, slider adjustment and button activation. Slider commits are tied to mouse release.

**Proposed solution:** Let controls and open popups own their normal keys. Scope transport shortcuts to a deliberate noninteractive context; avoid firing stop when the user is only dismissing a popup. Support keyboard commits for volume/progress, with concise numeric/readable values and focus feedback. Ensure tab order follows the actual task sequence.

**Regression/acceptance:** Complete file selection, target/mode selection, play/pause, seek, volume, track/subtitle selection and stopping without a mouse. Arrow navigation must not cause unrelated playback changes. Space/Enter activates the focused button; Escape follows popup/dialog ownership. Verify behavior during asynchronous state updates.

**Confidence:** High. **Confirmed:** Source event-filter and commit rules. **NOT TESTED:** Actual Narrator/keyboard acceptance; do not label the entire UI accessible based on standard JavaFX controls alone.

## U05 — P1: Keep displayed media, playing media and action availability consistent

**Location:** `MainViewController.applyState/openMedia/dropMediaFiles/handleShortcut`, application playlist commands and resume logic. Technical D08–D10.

**Problem:** Open/playlist buttons are disabled while playing, but Ctrl+O and root drop still import files. Selection can then change while the previous file plays; the top “current file” label follows selection rather than actual loaded media. This is a correctness issue, not a request for more playlist features. Existing dialogs can also return a resume choice without a file/session token.

**Proposed solution:** Apply one service-owned action policy to buttons, keyboard, double-click and drop. If imports are disallowed, make all paths consistent and explain why. If selection during playback is permitted, distinguish “Сейчас воспроизводится” from “Выбрано в плейлисте” and retain separate identities. Resume prompts must name the file and apply only to the matching pending operation; closing the dialog should have a deliberate, documented meaning.

**Regression/acceptance:** Add/select/drop while active/loading/idle; add a duplicate; next/previous/EOF; resume dialog after file/target changes; delayed old notification. The title, transport state and saved position must refer to the same playing file.

**Confidence:** High. **Confirmed:** Synthetic service reproduction saved A's progress for B; screenshot/source control availability inspected. **NOT TESTED:** Full GUI interaction with real playback.

## U06 — P2: Replace internal terminology and ambiguous unavailable states with user outcomes

**Location:** FXML placeholders, `MainViewController.applyState`, track/device cells, `PlaybackUiState`.

**Observed:** The empty-state instruction says to open one MKV/MP4, while the app supports multiple selections and more extensions. Placeholders refer to starting “mpv”, an implementation detail. A disabled “Пауза” dominates the empty transport. Audio unavailability, missing tracks and not-yet-loaded metadata are not clearly distinguished. Volume has no numeric value in the screenshot.

**Proposed solution:** Use consistent copy such as “Добавьте видеофайлы” and “Доступно после запуска видео”. Separate “Загружаю дорожки”, “Аудиодорожек нет”, “Устройство недоступно” and “Не удалось получить список”. Provide full device/track text on demand; clarify the selected actual audio output without implying a system-default change. Show volume value and implement the agreed mute/settings contract. Only display a scaling selection as applied when the command is confirmed; distinguish saved preference from current player state.

**Regression/acceptance:** Empty, metadata pending/failed, audio-only/no-audio file, missing selected audio device, long multilingual track/device names and command failure. Check displayed values after player restart/fallback and across application restart.

**Confidence:** High for strings and visible controls; medium for their impact on unfamiliar users. **Confirmed:** Screenshot/FXML/controller. **NOT TESTED:** Representative user testing and all media/device variants.

## U07 — P2: Verify accessibility, focus and theme behavior explicitly

**Location:** `screenpilot.css`, FXML labels/buttons/sliders/comboboxes, cell factories and repeated `applyState` list replacement.

**Problem/risk:** The dark palette is fixed. No explicit high-contrast/system-theme adaptation or label-to-control associations are visible in the FXML. Arrow-only reorder buttons lack an explicit descriptive accessible name. Disabled text is faint in the screenshot, and focus styling is not deliberately specified. Full control-list replacement during progress may disturb keyboard/popup interaction. None of this alone proves a specific contrast ratio or screen-reader failure.

**Proposed solution:** Add semantic names/label associations, explicit focus indicators and a high-contrast-friendly treatment for essential controls/status. Test the default and Windows high-contrast configurations; follow system conventions where they materially aid readability. Use descriptive accessible text for “Переместить выше/ниже”, slider role/value and output actions. Do not solve disabled-state opacity merely by making unavailable controls look enabled. Avoid resetting stable lists on progress updates.

**Regression/acceptance:** Narrator names/roles/values, visible keyboard focus, contrast measurements for essential enabled content, high-contrast mode, system text scaling, long labels and active popup during playback updates. Record exact results instead of claiming compliance from visual inspection.

**Confidence:** High for missing explicit metadata/style handling; medium for runtime accessibility impact. **Confirmed:** Source and screenshot inspection. **NOT TESTED:** Screen reader, focus visuals, measured contrast and Windows theme variants.

## Required UI state/action matrix

Use this as an implementation and test contract, not just a visual checklist.

| State | Main explanation | Primary action | Safety/recovery action |
| --- | --- | --- | --- |
| Discovering | Reading display configuration | File selection may remain available | No mutation until discovery completes |
| No target | Connect an external display; explain refresh | Add files / refresh | No start on the built-in display |
| Multiple targets | Choose the intended physical output | Select target | Inspect identification/confidence |
| Target ready, no file | Output is selected; add a video | Add files | No misleading active playback controls |
| Ready | File and target named together | Start external playback | Confirm temporary changes where required |
| Preparing/loading | Current phase; not yet playing | Busy state | Safe cancellation/stop request, not immediate success |
| Playing/paused | Actual loaded file, position, actual target | Pause/resume | Stop Playback vs Stop Output clearly distinguished |
| Prepared idle | Black external output remains owned | Play selected file | Stop Output and restore |
| Target lost/uncertain | Playback stopped/stopping; reason | Reconcile or choose again | No silent reconnect/resume |
| Restoring | Windows restoration still in progress | Busy state | Repeated close cannot bypass cleanup |
| Recovery needed/failed | Previous session and current outcome | Restore or explicitly keep current where safe | Preserve journal and offer diagnostics |

## Recommended implementation order

1. Fix service-level safety/identity/cleanup defects first; the UI must not paper over inconsistent state.
2. Implement recovery, readiness and progress/action feedback (U01, U03, U05).
3. Repair keyboard and accessible control semantics (U04, U07).
4. Compact empty/normal layout and make long labels/minimum-size behavior robust (U02).
5. Reconcile copy, selected preferences and actual playback/device state (U06).
6. Perform rendered interaction testing across target/window/scaling states, then hardware-backed end-to-end acceptance.

The goal is a predictable Windows control application: the user should always know what is playing, where it is playing, what is still running and how to return Windows to a safe state.
