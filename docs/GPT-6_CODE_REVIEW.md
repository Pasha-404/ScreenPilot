# ScreenPilot — GPT-6 code review

Review date: 2026-09-16. Review target: the current working tree on `codex/release-0.1.0`, based on commit `f8c89d8`, **including existing uncommitted AppFleet, packaging, path-migration and documentation changes**. This is a development review, not release acceptance.

## Executive assessment

The existing module split, immutable domain models, separate mpv process, correlated IPC replies, recovery journal and explicit output states are useful foundations. A rewrite is not warranted. However, the current implementation has safety defects at the native and lifecycle boundaries that passing tests do not cover.

The most important result is independently reproduced: `DisplayConfigGetDeviceInfo` packets have an incorrect ABI. On the same machine and target, the project's header ordering returns error 31; the SDK ordering succeeds. The preferred-mode packet is also undersized. The historical explanation that the driver rejected valid packets is therefore not reliable evidence for the present implementation.

This report identifies **15 current defects**, followed by four potential problems and three suboptimal solutions. Missing installer completion, unimplemented product features and the future hardware acceptance campaign are separately classified as **1.0 Readiness**, not counted as bugs.

Priorities: P1 = resolve before depending on the affected safety/data path; P2 = resolve before 1.0 usability/support acceptance. Confidence describes the evidence, not how often a failure occurs. `NOT TESTED` is used wherever physical execution or a particular failure injection was not performed.

The companion [UI/UX review](C:/MyProjects/ScreenPilot/docs/GPT-6_UI_UX_REVIEW.md) covers the supplied screenshots and the actual JavaFX implementation.

## Scope, architecture and evidence

### Reconstructed product contract

ScreenPilot controls local video playback on one chosen external Windows display. The laptop is a control surface, without preview, thumbnails or another video stream. The application owns a separate bundled mpv process and local JSON named pipe. Display changes are temporary and must be restored. It must not change the built-in display, make the external display primary, change Windows' default audio device or enable system HDR. HEVC Main 10 with HDR-to-SDR output belongs to the MVP; native HDR is deferred.

The five modules have these actual responsibilities:

| Module | Actual ownership |
| --- | --- |
| `screenpilot-domain` | Display/media/settings values, playlist and resume policy, output/player transitions, persistence/probe/containment ports. No JavaFX or native dependency. |
| `screenpilot-platform-windows` | DisplayConfig/GDI discovery, temporary display mutation, snapshot serialization, polling, mpv-window check, Job Objects. |
| `screenpilot-player-mpv` | Process launch, one IPC reader, correlated commands, player serial executor, headless metadata probes, typed notifications. |
| `screenpilot-persistence` | JSON settings/resume and active/archive recovery journal. |
| `screenpilot-app` | JavaFX rendering, application serial executor, concrete native/player composition, diagnostics, single-instance guard and technical CLI entry points. |

There are **two different executors both named `application-serial`**: one in the application service and one in each player adapter. The service frequently blocks waiting for the player executor. `UiStateStore` is the UI projection, not an authoritative observation of Windows. The missing reconciliation and operation-identity boundaries matter more than the module names.

Source navigation for the findings below:

- Native: [WindowsDisplayDiscovery.java](C:/MyProjects/ScreenPilot/screenpilot-platform-windows/src/main/java/ru/pavelkuzmin/screenpilot/platform/windows/WindowsDisplayDiscovery.java), [WindowsDisplayMutator.java](C:/MyProjects/ScreenPilot/screenpilot-platform-windows/src/main/java/ru/pavelkuzmin/screenpilot/platform/windows/WindowsDisplayMutator.java), [WindowsDisplaySnapshot.java](C:/MyProjects/ScreenPilot/screenpilot-platform-windows/src/main/java/ru/pavelkuzmin/screenpilot/platform/windows/WindowsDisplaySnapshot.java), [WindowsDisplayPoller.java](C:/MyProjects/ScreenPilot/screenpilot-platform-windows/src/main/java/ru/pavelkuzmin/screenpilot/platform/windows/WindowsDisplayPoller.java).
- Player: [MpvIpcClient.java](C:/MyProjects/ScreenPilot/screenpilot-player-mpv/src/main/java/ru/pavelkuzmin/screenpilot/player/mpv/MpvIpcClient.java), [MpvPlayerAdapter.java](C:/MyProjects/ScreenPilot/screenpilot-player-mpv/src/main/java/ru/pavelkuzmin/screenpilot/player/mpv/MpvPlayerAdapter.java).
- Application/UI: [ScreenPilotApplicationService.java](C:/MyProjects/ScreenPilot/screenpilot-app/src/main/java/ru/pavelkuzmin/screenpilot/app/ui/ScreenPilotApplicationService.java), [ScreenPilotApplication.java](C:/MyProjects/ScreenPilot/screenpilot-app/src/main/java/ru/pavelkuzmin/screenpilot/app/ui/ScreenPilotApplication.java), [MainViewController.java](C:/MyProjects/ScreenPilot/screenpilot-app/src/main/java/ru/pavelkuzmin/screenpilot/app/ui/MainViewController.java), [DiagnosticReport.java](C:/MyProjects/ScreenPilot/screenpilot-app/src/main/java/ru/pavelkuzmin/screenpilot/app/ui/DiagnosticReport.java).
- Data: [UserDataDirectories.java](C:/MyProjects/ScreenPilot/screenpilot-app/src/main/java/ru/pavelkuzmin/screenpilot/app/UserDataDirectories.java), [FileRecoveryJournal.java](C:/MyProjects/ScreenPilot/screenpilot-persistence/src/main/java/ru/pavelkuzmin/screenpilot/persistence/FileRecoveryJournal.java), [JsonResumeRepository.java](C:/MyProjects/ScreenPilot/screenpilot-persistence/src/main/java/ru/pavelkuzmin/screenpilot/persistence/JsonResumeRepository.java).

### Sources and limitations

Reviewed current source, tests, Gradle/configuration, requirements, README, Markdown documentation and ADRs, project lessons, the 11-commit Git history, installer scaffold, AppFleet standard/template and both supplied screenshots. The screenshots establish the empty/no-target visual state only; they do not establish the scaling factor, keyboard behavior or actual external playback.

The requested skills were used as engineering checklists: `windows-native-integration`, `windows-ipc`, `windows-desktop-lifecycle`, `core-state-machine-workflows`, `core-idempotency-concurrency`, `core-long-running-operation`, `windows-installer-update`, and `core-release-verification`. Documentation and Russian-text skills informed the report workflow and user-facing communication. Skills and project instructions are not evidence that a defect exists.

Relevant history: `6163100` introduced discovery/fallback; `3bdd55f` replaced synchronous pipe access with overlapped IPC; `bb095ed`/`09ba831` added recovery and inactive-target snapshot handling; `8b0be21` added unplug handling; `bfa25bf`/`096bf95` introduced UI and playlist/resume; `f8c89d8` added release hardening, including explicit unpause after file load. These explain the current layering and existing positive tests, but none proves that failure paths are covered. No revert commit was found in the available 11-commit history. Current AppFleet/migration work is outside that committed history.

No application source, UI, requirements, existing lessons, installer contract or manifest template was edited. No installer was built or installed, release published, display mode/topology changed, or real user recovery record replayed. Additional probes used read-only Win32 calls or synthetic files/fakes in temporary directories outside the repository.

### Executed verification

| Check | Result | What it establishes |
| --- | --- | --- |
| `gradlew.bat --no-daemon test integrationTest --rerun-tasks` | PASS | Build successful, 25 tasks executed. XML reports: 69 unit tests passed; 3 integration tests passed; 1 integration test skipped. |
| Read-only display discovery integration | PASS | Enumeration returned displays on this host. It does not validate metadata correctness or target/source association. |
| Two real-mpv null-output integration tests | PASS | Basic IPC controls and independent metadata reading work with the provisioned runtime. No HDMI/audio/video quality acceptance. |
| Job Object integration | NOT TESTED | Skipped after `AssignProcessToJobObject` returned error 5 in this host. The skip message attributes this to host containment; that attribution was not independently established. |
| Independent raw-buffer `DisplayConfigGetDeviceInfo` probe | FAIL for current layout | See D01. The read-only probe itself completed normally. |
| Synthetic service fault/playlist probes | FAIL for current behavior | B receives A's resume position; a resume exception exits stop before native cleanup and loses active ownership. |
| Synthetic migration/schema/redaction/fallback probes | FAIL for current behavior | See D10, D12–D14. These are isolated behavioral reproductions, not hardware tests. |
| Display mutation/rollback, unplug, mixed DPI, sleep/RDP, installer/upgrade | NOT TESTED | Deliberately left for controlled post-fix acceptance. |

Temporary probe sources are named `NativeProbe.ps1`, `ReviewProbe.java`, `FallbackProbe.java` and `ServiceProbe.java` under the review's temporary directory. The scenarios and outputs below are sufficient to turn them into permanent regression tests; they were not added to production or repository tests.

## 1. Defects / Must Fix

### D01 — P1: Device-info packets violate the Windows ABI

**Location:** `WindowsDisplayDiscovery.DisplayConfigDeviceInfoHeader` (around line 592), `TargetPreferredMode` (around line 615), metadata query methods; `WindowsDisplayDiscoveryLayoutTest.matchesFixedSizeWindowsSdkStructures`.

**Scenario and consequence:** All source/target/preferred/advanced-color queries share a header declared as `adapterId, id, type, size`. Windows expects `type, size, adapterId, id`. The total size remains 20 bytes, so the current size-only assertion passes while the requests are invalid. Preferred mode additionally substitutes a rational/scanline tail for the full target-mode structure, producing 40 bytes instead of the required x64 80-byte packet. Discovery loses authoritative names, identity, capabilities and preferred mode, then operates using fallback associations.

**Confirmed:** On one active internal target, independent read-only raw packets produced:

| Request | Project header order | SDK header order |
| --- | --- | --- |
| Source name, 84 bytes | 31 | 0; `\\.\DISPLAY1` |
| Target name, 420 bytes | 31 | 0 |
| Preferred mode, corrected header, 40 bytes | — | 87 |
| Preferred mode, corrected header, 80 bytes | — | 0; 1920×1080 |

The SDK declarations are explicit: [device-info header](https://learn.microsoft.com/en-us/windows/win32/api/wingdi/ns-wingdi-displayconfig_device_info_header), [preferred-mode structure](https://learn.microsoft.com/en-us/windows/win32/api/wingdi/ns-wingdi-displayconfig_target_preferred_mode).

**Confidence:** High; runtime reproduction plus source/specification comparison. **NOT TESTED:** Corrected JNA implementation, external targets and other GPU drivers.

**Proposed solution:** Correct every nested structure, field ordering and alignment together. Add an independent SDK/native-layout oracle and offset assertions, including header offsets 0/4/8/16 and preferred-mode target payload alignment. Query failures must retain their true return code and capability confidence.

**Regression tests:** Native metadata succeeds where the independent SDK probe succeeds; source names join correctly; preferred packet size/offsets match SDK; failure injection still produces explicitly limited capabilities. Do not bless the existing literal `40` as an oracle.

**Historical correction required later:** ADR-0002 and the first lesson in `PROJECT_LESSONS.md` currently attribute error 31 to the AMD driver and claim layout was excluded. The prior P/Invoke probe's exact bytes are not available here. A second implementation can repeat the same declaration error. Preserve this history, but revise its conclusion after the fix; do not propagate it into a reusable skill as a proven driver quirk.

### D02 — P1: Embedded outputs can be offered as external targets

**Location:** `WindowsDisplayDiscovery.discoverDisplays` (`internal` assignment around line 82), `connectionType`; `ApplicationState.externalTargets`; `WindowsDisplayMutator.isUsableExtendedTarget`.

**Scenario and consequence:** A laptop reports its panel as LVDS, embedded DisplayPort or embedded UDI. Only `INTERNAL` sets `internal=true`; embedded DisplayPort becomes DisplayPort and LVDS/embedded UDI become USB-C/adapter. The panel consequently enters external-target selection. Conversely, the mutator may fail to find an internal source and reject a legitimate external setup.

**Confirmed:** These enum values describe internal connections in the [Windows output-technology contract](https://learn.microsoft.com/en-us/windows/win32/api/wingdi/ne-wingdi-displayconfig_video_output_technology). Source filters rely directly on the incorrect boolean. **Confidence:** High. **NOT TESTED:** A device exposing an embedded enum rather than `INTERNAL`.

**Proposed solution:** Classify internal/embedded technologies conservatively and separately classify physical, indirect/virtual and unknown targets. Connection naming must not determine mutation permission. Unknown is not affirmative evidence of an external physical display.

**Regression tests:** Parameterized classification for INTERNAL, LVDS, DP embedded, UDI embedded, HDMI, external DP, indirect/virtual and unknown values; verify selection and mutation rejection for every built-in case, including single-display systems.

### D03 — P1: Database-current rollback uses the wrong flag

**Location:** `WindowsDisplayMutator.SDC_USE_DATABASE_CURRENT` (line 38), `restore` (around lines 140–154).

**Scenario and consequence:** A saved topology cannot be validated/applied after unplug or another system change. The fallback passes `SDC_APPLY | 0x400`. `0x400` is `SDC_ALLOW_CHANGES`, not database-current. The required topology/supplied-configuration selector is absent; this is not a valid fallback request. See [SetDisplayConfig flags](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-setdisplayconfig).

**Confirmed:** Constant and call-site comparison; correct database-current value is the OR of the four topology flags, `0xF`. **Confidence:** High. **NOT TESTED:** Mutating fallback execution; it was intentionally not invoked.

**Proposed solution:** Correct the flag and test the exact bitmask through a fake native boundary. Keep exact snapshot restoration distinct from a safe alternative topology; a successful fallback is not necessarily exact restoration. Address R02 before replaying old DEVMODE data after fallback.

**Regression tests:** Snapshot validation failure, application failure, fallback success/failure and missing target; expected flags; journal remains open until the actual outcome is verified or explicitly accepted.

### D04 — P1: UI requires an active screen mapping before it can activate the target

**Location:** `MainViewController.startOutput` (around lines 272–296), `MpvScreenResolver.resolve`, `ScreenPilotApplicationService.startOutputOnSerial`.

**Scenario and consequence:** A connected but inactive external target is selectable. Discovery gives it no current mode/bounds; the controller attempts to resolve it against current JavaFX screens and returns before `ensureExtendedTopology` can run. For a cloned target the candidate comes from the pre-extend screen list, but is reused after topology changes. The implemented activation path is unreachable for the first case and uses a stale candidate for the second.

**Confirmed:** Source control flow and inactive-target representation. **Confidence:** High for the inactive-target rejection. **NOT TESTED:** Physical clone-to-extend placement on this run.

**Proposed solution:** Authorize preparation using physical target identity, capture/journal state, prepare topology, rediscover the target, then calculate and verify output placement before loading media. Do not require active desktop bounds to authorize activation. See R01 for coordinate-space requirements.

**Regression tests:** Inactive HDMI target can reach preparation; clone-to-extend invalidates prior screen indices; placement failure closes the blank player and rolls back without playing on the laptop.

### D05 — P1: Active output is not reconciled after display changes

**Location:** `WindowsDisplayDiscovery.currentTopologyFingerprint` (around lines 124–135), `WindowsDisplayPoller.pollOnce`, `ScreenPilotApplicationService.createDisplayPoller` (552–568).

**Scenario and consequence:** Windows changes resolution, position, rotation or refresh while retaining path identity. The fingerprint omits mode geometry and refresh, so no full discovery occurs. Even when a topology change is detected, the service checks only whether the old target address is active; it ignores changed source assignment, clone state, bounds and returned display details. UI and playback can remain tied to an obsolete topology without verifying that output is still exclusively external.

**Confirmed:** Omitted fingerprint inputs and presence-only listener. Polling failures merely change a message while playback continues. **Confidence:** High for missing reconciliation; external leakage depends on OS/window behavior. **NOT TESTED:** Physical rearrangement/clone/sleep scenarios.

**Proposed solution:** Define a relevant configuration generation including geometry/mode/source ownership. Reconcile native observation with selected target and player window, pausing/stopping safely on uncertain ownership. Do not silently resume after reconnect. Keep last-known and current-observed topology distinct.

**Regression tests:** Same address with changed geometry/refresh, extend→clone, re-enumerated source, query failure, disconnect/reconnect. Assert no media continues on an unverified output and UI never labels stale data current.

### D06 — P1: Resume persistence failure aborts critical output cleanup

**Location:** `ScreenPilotApplicationService.stopOutputOnSerial` (584–606), `failActiveOutputOnSerial` (609–627), `persistResume` (760–777).

**Scenario and consequence:** `resume.json` cannot be read/written or uses an unsupported schema. Both stop paths clear `activeOutput`, transition state, and call `persistResume(true)` **before** closing notifications/poller/player or entering the restore try/catch. A persistence exception skips all remaining cleanup. Another stop sees no active owner and returns, although output resources may still exist.

**Confirmed:** Isolated synthetic state/fault injection produced `PersistenceException`, `activeOutput=null`, `outputState=RESTORING_DISPLAY`, before any native cleanup. No real display was used. **Confidence:** High. **NOT TESTED:** Real disk-full/permission failures during playback.

**Proposed solution:** Make resume saving best-effort relative to safety cleanup. Retain an output/recovery owner until mandatory cleanup and restoration reach an explicit terminal outcome. Use independently guarded cleanup stages with aggregated errors; a failure in one stage must not suppress later safety work.

**Regression tests:** Throw from resume save/completion, unsubscribe, player close, restore and journal archive independently. Assert player shutdown and restoration are attempted, ownership remains recoverable, and completion/failure is delivered exactly once.

### D07 — P1: A second window-close request bypasses restoration

**Location:** `ScreenPilotApplication.start` close handler (around lines 94–102), `stop`; `ScreenPilotApplicationService.close` (431–434).

**Scenario and consequence:** The first close is consumed and queues output shutdown. A second click/Alt+F4 sees `closing=true` and allows the window to close immediately. JavaFX shutdown then calls `service.close`, which interrupts its executor and closes only the metadata probe. Active player/restore work can be abandoned and the instance lock released before cleanup finishes.

**Confirmed:** Close-handler branch and shutdown implementation. **Confidence:** High. **NOT TESTED:** Repeated close during real native restoration or Windows logoff.

**Proposed solution:** Separate `closeRequested` from `cleanupCompleted`; consume all user close requests until completion or an explicit failure decision. Make cleanup a single idempotent operation shared by window close and other exit paths. Normal exit must not interrupt its own restore. Crash recovery remains necessary because forced termination cannot guarantee cleanup.

**Regression tests:** Repeated close during prepare/load/restore, cleanup failure, late callback and partial initialization; verify one cleanup owner, one completion and no early lock release.

### D08 — P1: Old output callbacks can affect a new session

**Location:** `ScreenPilotApplicationService.PlayerNotifications.onNext/onError` (around lines 1050–1057), `handlePlayerNotificationOnSerial`, `createDisplayPoller`, `handleTargetLostOnSerial`.

**Scenario and consequence:** A callback from output A is enqueued behind an already queued stop-A/start-B sequence. Cancelling A's subscription does not remove work already queued in the application executor. The callback carries no session identity; the handler tests only that some `activeOutput` exists. Old progress/EOF/failure or target-loss can update B, advance its playlist or stop it.

**Confirmed:** Missing identity checks at both application callback boundaries. The player adapter itself correctly captures its IPC/process instances before accepting callbacks; that protection is lost at the next layer. **Confidence:** High for the race path. **NOT TESTED:** Controlled production event interleaving.

**Proposed solution:** Carry output-session and media-load generation through queued callbacks. Compare at execution time, not just subscription time. Cancel deferred resume/start decisions when their file/target/generation changes.

**Regression tests:** Queue A progress/EOF/error/disconnect, replace with B before dispatch, then release A events; assert no B state or native action changes. Repeat with poller and resume-dialog completions.

### D09 — P1: Ctrl+O/drop can associate playing progress with another file

**Location:** `MainViewController.handleShortcut`, `dropMediaFiles`; service `addMediaFiles`, `addPlaylistItem` (657–665), `persistResume`, EOF handling.

**Scenario and consequence:** Normal playlist/open controls are disabled while playing, but Ctrl+O and root file-drop remain active. The service accepts additions without an output-state guard; `addOrSelect` changes selection to B while A continues playing. Resume save uses the **selected** item's fingerprint with the existing playback position/duration. EOF completion and next-item selection also use that selection.

**Confirmed:** An isolated service probe in synthetic A-playing state added B and saved `B.mkv at PT2M`, using A's position. **Confidence:** High. **NOT TESTED:** Full GUI gesture plus real playback; the underlying service path was executed.

**Proposed solution:** Enforce allowed actions in the service, not only disabled widgets. Either prohibit imports consistently during playback or maintain separate selected-item and currently-playing identities. Persist/complete progress by the loaded media identity and generation.

**Regression tests:** Ctrl+O, drop, duplicate-add and queued selection while playing/loading; verify A's resume remains A's, B stays unchanged, and EOF follows the playing item. Include commands submitted just before controls become disabled.

### D10 — P1: Decoder fallback loses an outstanding load future

**Location:** `MpvPlayerAdapter.attemptSoftwareFallback`, `closeProcessResources`, `completePendingLoad`, `failPendingLoadIfUnchanged`.

**Scenario and consequence:** A hardware-decoder error occurs during the initial `LOADING` phase. Fallback closes the process; cleanup sets `pendingLoad=null` without completing the caller's future. The new process loads successfully but has no future to complete. The original timeout also checks `pendingLoad == expected`, so it no longer resolves that request. The application-level wait times out and may tear down successful playback.

**Confirmed:** Fake-process/IPC reproduction emitted the warning before initial `file-loaded`, then successfully loaded the software session: `state=PLAYING originalLoadDone=false`. Existing test emits the warning only after the original load completed. **Confidence:** High. **NOT TESTED:** Real GPU decoder failure.

**Proposed solution:** Give the logical media-load operation its own lifetime across process attempts, or explicitly fail it and start a new identified operation. Complete every future on stop/shutdown/failure. Re-arm a bounded fallback-load timeout and verify replacement-window ownership before allowing media output.

**Regression tests:** Fallback during loading, after playback, while paused, missing replacement `file-loaded`, second decode failure and shutdown during load; every public future reaches exactly one terminal result.

### D11 — P1: IPC command timeout does not bound the pipe write

**Location:** `MpvIpcClient.command` (around lines 100–145), `transfer` (around lines 240–276), `close`; `MpvPlayerAdapter.close` and service waits.

**Scenario and consequence:** mpv or a pipe peer stops reading. The scheduled timeout completes the reply future, but the calling thread is still inside the synchronized write or `GetOverlappedResult(..., true)`. It never reaches the bounded `reply.get`. Player shutdown is queued on that blocked executor; the service may proceed after its own timeout without having closed the pipe/process. Likewise, interrupting a Java executor is not a native cancellation/completion protocol.

**Confirmed:** Deadline applies only to reply bookkeeping, not lock acquisition/native I/O. `close` calls cancel and closes the handle without explicitly waiting for outstanding operations to finish. Microsoft documents that [CancelIoEx does not wait for cancellation completion](https://learn.microsoft.com/en-us/windows/win32/api/ioapiset/nf-ioapiset-cancelioex). **Confidence:** High for the unbounded wait; exact native race outcome is platform-dependent. **NOT TESTED:** A deliberately stalled real pipe peer or native-handle stress.

**Proposed solution:** Apply one monotonic deadline to queueing, write and reply. On timeout cancel the exact overlapped operation, await its completion, then release its buffer/event. Allow an independent shutdown owner to close/cancel a blocked session. Treat a sent-but-unacknowledged mutation as uncertain and reconcile, not blindly retry.

**Regression tests:** Peer accepts without reading, partial write, close during write/read, delayed/duplicate/stale reply, disconnect before listener registration, shutdown after timeout. Assert bounded completion and no native use-after-close or leaked handle.

### D12 — P1: Repeated migration resurrects an archived recovery journal

**Location:** `UserDataDirectories.prepareAndMigrateLegacyData`, `copyTreeIfMissing`; `ApplicationPaths.configureLogging`; `BootstrapMain.main`.

**Scenario and consequence:** Legacy recovery is copied to the new local data directory. After restoration the new active journal is archived/deleted; the legacy copy remains. Every later preparation runs the migration again, sees the destination missing, and copies the old active journal back. Output is blocked by an already-resolved session. Missing settings/resume files can likewise be reintroduced instead of remaining intentionally absent.

**Confirmed:** Synthetic valid journal: after archive `unfinished=false`; after repeat migration `unfinished=true`. **Confidence:** High. **NOT TESTED:** Installed upgrade from a real previous package. This is a defect in existing migration code, not a complaint that the installer is unfinished.

**Proposed solution:** Use a durable migration version/completion record and explicit recovery-session adoption under the instance lock. Stage/validate copied files before publication; distinguish never migrated from intentionally removed/archived. Preserve legacy data for recovery without replaying it indefinitely. Currently preparation also runs before the UI instance lock, so concurrent launches need serialization.

**Regression tests:** Migrate→restore/archive→restart, keep-current→restart, partial copy/retry, two starts, corrupt source, destination already newer and downgrade. Assert retired sessions do not return.

### D13 — P1: Future-schema data can be quarantined as corruption

**Location:** `FileRecoveryJournal.findUnfinished`, `JsonResumeRepository.readStore`; shared `JsonFiles.mapper`.

**Scenario and consequence:** A future version writes schema 2 with a new field. These repositories bind the full object before checking `schemaVersion`. Binding rejects the unknown field, enters the corruption handler, moves the file aside and returns an empty journal/store. Recovery can now proceed as though no unfinished session existed; resume data can be replaced by a fresh store. Files are quarantined, not irretrievably deleted, but active safety state is lost.

**Confirmed:** Synthetic schema-2 recovery JSON with one additional field returned `unfinished=false`; the original active path no longer existed. Existing future-schema tests do not cover a changed schema shape. **Confidence:** High. **NOT TESTED:** A real downgrade between released versions.

**Proposed solution:** Read a minimal envelope/schema first, refuse unsupported versions without moving/writing them, then bind the supported schema. Define same-version unknown-field policy explicitly. Keep corrupt recovery distinct from no recovery; require a visible safe decision, not silent clearance of the mutation gate.

**Regression tests:** Future schema with extra fields, changed nested shapes and missing old fields; verify byte-for-byte preservation and refusal to mutate. Also test malformed current schema and corrupt checksum without silently granting a new session.

### D14 — P2: Diagnostic path redaction leaves private path components visible

**Location:** `DiagnosticReport.WINDOWS_PATH` (around line 21), `redact`; `DiagnosticReportTest.reportRedactsWindowsPathsAndIncludesRecentErrorCodes`.

**Scenario and consequence:** A diagnostic log contains `C:\Users\SyntheticPerson\Videos\private.mkv`. The lazy final quantifier has no terminating context, so it consumes only the first character after the drive prefix. The result still contains `sers\SyntheticPerson\Videos\private.mkv`. Replacing `user.home` afterwards cannot match the altered prefix. A user copying the supposedly redacted report can share profile/folder information.

**Confirmed:** Direct invocation reproduced the remaining path. Current tests only check that the original full path disappeared, which passes for this broken masking. **Confidence:** High. **NOT TESTED:** Every possible path spelling/escaping; no actual private path was needed for the probe.

**Proposed solution:** Prefer structured diagnostic fields with explicit safe representations. Where free-text redaction remains, handle drive, UNC, extended, quoted and escaped paths with clear delimiters. Do not claim privacy protection solely because a prefix changed.

**Regression tests:** Assert sensitive individual components are absent, not merely full-string inequality. Include spaces, Unicode, multiple paths, forward slashes, UNC and JSON-escaped strings while preserving useful error codes.

### D15 — P2: Scene-wide shortcuts consume normal keyboard control input

**Location:** `ScreenPilotApplication` scene `KEY_PRESSED` filter; `MainViewController.handleShortcut` (around lines 349–394); slider mouse-release handlers in `main-view.fxml`.

**Scenario and consequence:** Focus a playlist/combobox/slider/button and use arrows or Space. The scene filter exempts only `TextInputControl`, so it consumes keys before the focused control handles them: Up/Down changes volume, Left/Right seeks, Space toggles pause. Playlist/combobox keyboard navigation and button activation are disrupted. Sliders commit only on mouse release; other key-driven value changes are not consistently sent to the service.

**Confirmed:** Event-filter ordering and unconditional consumption in source. **Confidence:** High. **NOT TESTED:** Live keyboard and screen-reader session in this review.

**Proposed solution:** Scope transport shortcuts to noninteractive focus contexts; let focused controls/popups own their normal keys. Provide keyboard-accessible slider commits, accessible names and visible focus. Esc must close a popup/dialog before acting as an output-stop shortcut where appropriate.

**Regression tests:** Tab through all controls; operate comboboxes and playlist with arrows, buttons with Space/Enter, sliders with keyboard; assert no unrelated seek/volume/playback action. See companion UI report.

## 2. Potential Problems

### R01 — P1: Placement mixes physical pixels, JavaFX logical bounds and mpv numbering

**Location:** `MpvScreenResolver.resolve/contains`, `WindowsDisplayDiscovery.queryBounds`, `WindowsMpvWindowLocator.waitForWindowCenteredOn`; launch-profile screen arguments.

**Scenario/consequence:** At mixed 125/150/200% scaling or negative monitor origins, a center calculated from GDI physical coordinates is compared directly with JavaFX scaled coordinates. An index in `Screen.getScreens()` is then treated as an mpv index. This can reject a valid screen or produce a wrong candidate; the later center check reduces risk but does not establish full containment or coordinate consistency. [JavaFX Screen bounds are output-scale adjusted](https://openjfx.io/javadoc/21/javafx.graphics/javafx/stage/Screen.html), whereas [GetWindowRect can be DPI-virtualized](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-getwindowrect).

**Confidence/confirmed:** High that distinct coordinate/index domains are directly combined; medium on the precise failing layout for the packaged launcher. **NOT TESTED:** Effective process/thread DPI awareness and mixed-DPI hardware behavior.

**Solution:** Keep display identity and placement verification in a documented native coordinate space; validate actual DPI awareness for both launchers/worker calls. Recompute mapping after topology changes, check the owned PID/window and full visible output containment. Do not implement a universal multiply-all-origins-by-scale shortcut.

**Regression tests:** Primary/nonprimary external, left/top negative origins, portrait, nonmatching DPI and mpv enumeration order; tests must distinguish physical and logical coordinate types.

### R02 — P1: Recovery/fallback needs stronger identity and native-payload validation

**Location:** `WindowsDisplayDiscovery.findGdiDisplay/stableDisplayId`; `WindowsDisplaySnapshot` constructor/fromRecoveryPayload; `WindowsDisplayMutator.captureSnapshot/restore/restoreTargetDevMode`.

**Scenario/consequence:** Adapter-local source IDs are used to index a global GDI list when metadata is unavailable, without an adapter join. A generic confirmation authorizes this FALLBACK mapping. Snapshots persist LUID/target ID and GDI names; restoration later replays the saved GDI name even after a database-current fallback. These are not sufficient proof that the same physical monitor still owns the name after reboot/re-enumeration. Global `SDC_TOPOLOGY_EXTEND` may also affect nonselected sources; current read-back checks only a subset of the required invariants.

Native payload validation only checks positive counts/nonempty arrays, not `pathCount × structureSize`, mode indices, DEVMODE length, limits or a consistent snapshot generation. A checksum detects accidental payload changes, not semantic/native safety.

**Confidence/confirmed:** High for missing joins/guards; medium for the particular wrong-monitor outcome. **NOT TESTED:** Multi-GPU, changed hardware/reboot replay or malformed buffers passed to native code. Do not run such mutation tests on a user's desktop.

**Solution:** After D01, allow mutating operations only with a proven target↔source association; fallback metadata may remain useful for display/diagnostics. Save durable monitor evidence and boot/session context, rediscover before replay, validate all buffer lengths/counts/indices with bounded arithmetic, and compare built-in/nonselected source invariants before accepting success. Never apply stale GDI mode bytes merely because a fallback API succeeded.

**Regression tests:** Two adapters each with source ID 0; renumbered GDI names; replaced same-model monitor; invalid counts/lengths; changed built-in mode; rejected snapshot with safe alternative topology. Assert zero unauthorized native calls.

### R03 — P1: Safety response and child containment are not end-to-end guarantees

**Location:** Service blocking `start/load/command` waits, `createDisplayPoller`; `MpvPlayerAdapter.startProcess/close`; `MpvMediaProbe.probeOnQueue/cancelAll`; `WindowsJobObject`.

**Scenario/consequence:** Target loss is queued behind up to 15-second media loads and other blocking operations, so a one-second poll interval does not guarantee the required prompt stop. Job assignment failure is downgraded to a diagnostic; metadata probes explicitly disable containment. A hard parent exit may therefore leave a process in some environments. Cancellation can race between the probe's generation check and `active.set`, allowing a short-lived obsolete probe to start.

**Confidence/confirmed:** High for queueing and containment policy; medium for actual orphan/timing outcome. **NOT TESTED:** Forced host termination, long blocked media, sleep/wake, lock/unlock, RDP and Windows shutdown. The skipped Job test is not PASS.

**Solution:** Use an interruptible operation owner and independent bounded safety-stop path; do not block the state reducer on long I/O. Define acceptable degraded-containment policy visibly, contain probes where supported, and confirm all children are gone before declaring clean shutdown. Reconcile after wake/session changes; do not silently continue uncertain playback.

**Regression tests:** Unplug during each preparation/load/command phase; stop during a queued probe; no-job environment; forced exit in a controlled test host; handle/process counts over repeated starts. Measure detection-to-stop latency, not polling interval alone.

### R04 — P2: Atomic replacement is not a complete crash-durability contract

**Location:** `JsonFiles.writeAtomically`; migration direct `Files.copy`; recovery journal `begin/closeAndArchive`.

**Scenario/consequence:** Power loss or interrupted copying can leave a recovery record less durable than the native change it protects, or leave a partial migration target that future runs regard as present. Atomic rename prevents ordinary half-JSON readers but does not itself prove durable storage. The current tests cover normal save/reload and corruption, not interruption between stages.

**Confidence/confirmed:** Medium; missing durability/fault tests are established, actual data loss is not. **NOT TESTED:** Power-loss/storage/filesystem behavior.

**Solution:** Define the persistence guarantee before display mutation; use staged validated writes and the appropriate flush/commit policy for that guarantee. Preserve active journal ownership if archive publication fails. Make migration resumable without treating mere target existence as completion.

**Regression tests:** Faults before write, after write, before/after replace and during archive; missing/full/read-only destination; restart at each checkpoint. Test only synthetic profiles.

## 3. Suboptimal Solutions

### S01 — P2: One native read/event/allocation per byte

**Location:** `MpvIpcClient.readUtf8Line/readByte/transfer`.

**Scenario/consequence:** Progress and metadata traffic creates a native event, OVERLAPPED and JNA buffer for each byte. Memory cleanup is GC-mediated and line size is unbounded. This is unnecessary overhead and makes stress behavior harder to bound, although normal integration playback passed.

**Confidence/confirmed:** High for allocation/read strategy. **NOT TESTED:** Measured CPU/handle/native-memory cost under sustained traffic.

**Solution:** Buffered chunk reads with newline accumulation, bounded frame size and explicit native resource lifetime; preserve UTF-8 across chunk boundaries. **Regression tests:** Split multibyte characters, multiple lines per read, large/oversized/malformed frames, EOF mid-frame, sustained progress; measure resources before/after repeated sessions.

### S02 — P2: Every progress event rebuilds the whole UI projection

**Location:** `UiStateStore`, `MainViewController.applyState`, player `SubmissionPublisher` and application executor.

**Scenario/consequence:** Every progress update schedules a JavaFX task and recreates item lists for target/mode/audio/subtitle/playlist selectors, resets selection and updates unrelated controls. This can cause redundant layout, popup/focus churn and growing queues when rendering or I/O stalls.

**Confidence/confirmed:** High for the repeated work; performance/jitter is unmeasured. **NOT TESTED:** Large playlists and sustained rendering at multiple scales.

**Solution:** Update collection controls only when their data changes; coalesce progress snapshots while preserving ordered state transitions/failures. Keep one authoritative state, not a second controller model. **Regression tests:** Popup remains usable during progress, selection/focus remains stable, bounded queued updates under a deliberately slow consumer.

### S03 — P2: Diagnostics discard the detail needed to distinguish native failures

**Location:** Service `handlePlayerNotificationOnSerial` (836–842), `failActiveOutputOnSerial`, poller failure callback; `MpvProcessLauncher`; `DiagnosticReport`; `WindowsJobObject.create`.

**Scenario/consequence:** Typed player failures contain `technicalDetail`, but application logs retain only code/message. Some restore/poll failures omit exception details; child stdout/stderr is discarded. Current summaries lack session/generation, native API result, mapping confidence and effective DPI context. In Job creation, the error code is read after closing the handle, rather than captured immediately at failure. Rare failures can again be attributed to a driver without sufficient evidence.

**Confidence/confirmed:** High for dropped fields/call order. **NOT TESTED:** Real production support incidents and complete packaged log capture.

**Solution:** Structured, bounded logs with operation/session/media generation, native function and immediate return/error, expected/observed state and rollback result; safely retain bounded mpv failure diagnostics. Redact at export (D14), not by erasing all technical meaning. Name the two serial executors distinctly. **Regression tests:** Inject start/IPC/restore/poll failures and assert the support bundle explains both original failure and cleanup outcome without private paths.

## Test coverage assessment and architectural direction

Current tests are useful for domain transitions, playlist/resume rules, persistence basics, structure sizes, topology predicates and happy-path real mpv IPC. Important gaps are behavioral, not simply test counts:

| Existing coverage | Important missing evidence |
| --- | --- |
| Native layout sizes | Independent field offsets/full preferred-mode layout; real metadata contents. |
| Read-only enumeration with any internal display | Embedded-panel variants, adapter-specific mapping, incomplete metadata and unavailable/virtual targets. |
| Mutator policy/mode helper tests | Exact flags and full capture→validate→apply→verify→rollback failure sequencing. |
| Player fallback after completed load | Fallback during pending load; cancelled/replaced loads; bounded real-pipe stalls. |
| Three service tests | Active-output stop failures, stale callbacks, concurrent commands, shutdown, Windows reconciliation. |
| Persistence future version/corruption | Future shape, corrupt active recovery actionability, migration replay and interrupted writes. |
| FXML loading, window-size arithmetic | Actual layout/focus/keyboard behavior, high DPI, accessibility and clipping. |
| Diagnostic test excludes original full path | Sensitive components are actually absent. |

Introduce small injectable ports for display mutation/window placement/player creation and operation scheduling, not a broad framework rewrite. Keep the service as a serialized state reducer; run long work outside it and return identified completions. A session context should own player, subscriptions, poller, current media, recovery record and pending cleanup. Define legal actions centrally so keyboard/drop callbacks cannot bypass UI restrictions.

Suggested acceptance invariants:

- No media is loaded until the owned window is verified on a proven external target.
- A callback is actionable only for its current output and media generation.
- A failure to save resume data cannot prevent stopping playback/restoring displays.
- No new mutation starts while recovery is unresolved, corrupted or incompatible.
- Every asynchronous request finishes once; timeout does not imply that an external side effect was cancelled.
- Clean shutdown means verified child cleanup plus verified restoration or an explicitly retained unresolved journal.
- Built-in and nonselected display invariants are checked after every global topology operation.

### IPC versioning and real trust boundaries

The wire protocol is mpv's JSON IPC, not an independently deployed ScreenPilot client/server protocol. Adding a proprietary version handshake is not needed for this MVP. Pin and record the mpv build, test required properties/commands, validate message shape/framing, and define behavior for unknown optional events. Keep `request_id` for replies and add session/media identity inside ScreenPilot; the latter is not solved by wire versioning.

Existing good boundaries include argument-list `ProcessBuilder` launch without a shell, unique pipe names, disabled user mpv configuration, a hash-pinned packaged runtime, local JSON and no application HTTP listener/telemetry. Do not invent remote-authentication work for this application.

Real remaining surfaces are local media/subtitles handled by a native decoder, named-pipe messages, native parameters and recovery bytes, configuration files, development executable override and technical CLI entry points. Define whether UNC files belong to the local-file contract; `Files.isRegularFile` alone does not enforce local storage. Package only verified runtime files; fail clearly on missing/corrupt binaries. Treat same-user overrides as an explicit development capability, not as a remote exploit. Before 1.0, make technical mutating CLI commands use the same instance/recovery ownership rules, or exclude them from the end-user entry point.

## 4. 1.0 Readiness

### Must complete before 1.0

These are completion/acceptance requirements unless explicitly linked to a current defect above.

| Area | Required completion | Acceptance evidence |
| --- | --- | --- |
| Safety defects | Resolve D01–D13; resolve D14–D15 before support/usability acceptance. Close R01–R03 with implementation or an explicit safe refusal policy. | Regression tests plus controlled native failure matrix. |
| Startup recovery | The UI currently blocks on an unfinished journal but offers no restore/keep-current action; the capability exists only in technical CLI paths. Present pending/corrupt/incompatible recovery at startup, before ordinary output actions, with honest outcomes. | Crash→restart→restore, keep-current, missing monitor, restore failure, incompatible data. No implicit journal deletion. |
| Automatic display mode | `DisplayModeSelector` exists but is not wired into the production service. Integrate metadata/FPS selection, explain chosen vs actual mode, preserve rational rates and implement a truthful path for unsupported fractional mutations. Audit selector against the documented resolution and ≤120 Hz fallback rules before integration. | 23.976/24/25/29.97/50/59.94/60 FPS, unknown FPS, no exact match, progressive-only choices, actual read-back and rollback. |
| Settings/product completeness | Only last-folder settings are applied/persisted by the service. Complete the agreed volume/mute/scaling/audio/display/window settings, or explicitly resolve the product contract before 1.0; do not treat the presence of JSON fields as a finished feature. | Restart preserves supported preferences; stale device references fall back safely; window restored onto available desktop. |
| Idle discovery | Poller is created only for active output. Complete automatic discovery/reconciliation on connect/disconnect while idle, initialization and wake; retain a manual refresh fallback. | First launch with no HDMI, later plug-in, unplug idle, repeated reconnect; clear error vs no-device state. |
| Output/media acceptance | Verify required Main 10 and HDR→SDR behavior on representative real media and the minimum Windows target, external HDMI audio selection and track/subtitle behavior. Never change system audio/HDR defaults. | Controlled visual/audio evidence, current driver/runtime versions, long-duration and repeated-session tests. |
| Failure handling/support | Bounded teardown, safe restart recovery, session-aware errors, useful redacted logs and user-actionable restore failures. | Fault-injection suite and support bundle inspection. |
| UI usability | Implement the substantive items in the companion report, especially state feedback, keyboard input and minimum-size/scaling behavior. | Actual rendered interaction matrix, not FXML-load success alone. |
| Configuration compatibility | Envelope-first schema policy, staged migration with durable completion and isolated data roots. | Old→new→restart, interrupted migration, newer-data downgrade refusal, user data preserved on uninstall. |
| Installer/AppFleet | Finish the current scaffold against the supplied current standard; see contract table below. | Isolated clean install/update/uninstall, parsed artifacts and registry checks. |

### Installer/AppFleet implementation contract

`docs/Windows_Installer_Standard.md` is the controlling supplied contract. `docs/appfleet-manifest.json` is a **generic reference template**, not the production ScreenPilot manifest; its example product/version/UUID are not bugs to replace during this review. Existing Inno/CI code is preparatory and uncommitted. Installer incompleteness is not included in the defect count.

| Contract item | Current state and recommendation |
| --- | --- |
| Permanent identity | Keep `c4cc60ea-a3e8-4ff1-8d94-e79f3b791df4`, `ScreenPilot`, `ScreenPilot.exe` and the repository URL consistent across Inno, manifest, metadata and detection. Verify values rather than deriving identity from a versioned filename. |
| Install/data roots | Scaffold uses `%LOCALAPPDATA%\Programs\PashaApps\ScreenPilot`; settings/resume use `%APPDATA%\PashaApps\ScreenPilot`; operational data uses `%LOCALAPPDATA%\PashaApps\ScreenPilot`. This aligns structurally. Fix D12 and prove migration; do not put user data beneath install root. |
| Application image | jpackage app-image input contains application/dependency JARs and bundled mpv; jpackage creates the embedded runtime. Complete the standard's reproducible minimal Java 21 runtime step with verified `jlink` modules. Verify final `ScreenPilot.exe`, `app`, `runtime`, icon resources and launch without an independently installed JDK. Use the intended Java 21 packaging toolchain explicitly, not whichever `jpackage` happens to be on PATH. |
| Installer policy | Bare AppId, per-user privileges, x64-compatible settings, previous directory/tasks, close-applications and no auto-restart are represented in `packaging/screenpilot.iss`. Keep them. Existing cleanup targets known replaceable binary subtrees; test files-in-use/failed upgrade without expanding deletion to a profile/root. |
| Desktop shortcut | Optional unchecked `desktopicon` task and task-gated `autodesktop` shortcut exist. Add `installer.desktopShortcutTask: "desktopicon"` to the generated manifest. Preserve shortcut choice during update; do not add `/TASKS=desktopicon` to the manifest's universal silent arguments. |
| Minimum AppFleet | Generated manifest currently says `1.0.0`; the supplied standard requires `2.0.0`. Correct before shipping and validate through a parsed schema. |
| Registry/detection | HKCU64 AppId key, version, executable, install location, process, repository, installer type and installed-by metadata are represented. Verify actual registry values, quoting and version after successful file copy; verify cleanup on uninstall. Inno uninstall registration and AppFleet registration must agree. |
| Version source | Root Gradle falls back to `0.1.0`; `gradle.properties` lacks persistent `version`; branch CI also hardcodes `0.1.0`. Use the standard's authoritative version property, explicit release override and tag/version checks, with no stale fallback. Verify metadata inside the built JAR, not just runtime `-Dscreenpilot.version`. |
| JAR/native metadata | Add/verify required built-JAR version, repository and AppId metadata. Current window-icon tests do not establish the executable's native `RT_ICON`/`RT_GROUP_ICON` sizes. Verify final EXE resources at 16/32/48/64/128/256. |
| Artifacts/checksum | Names and final SHA-256 sidecar format are broadly aligned. Parse the generated UTF-8/no-BOM manifest, validate all required values and enforce the exact three-asset set at local verification and publish time. Current `verifyReleaseAssets` uses substring checks; publish workflow already checks file count. Hash the final bytes after any signing step. |
| Publishing | Tag workflow uses `gh release create` and `--latest`; publishing was not executed. Gate release promotion on the explicit acceptance decision, matching immutable version/tag and the tested artifacts. Never silently replace assets for an existing version. Keep preview builds out of automatic stable promotion. |
| Silent lifecycle | Exercise base silent args, first install with/without explicit desktop task, both shortcut upgrade paths, running-app close failure, silent uninstall, rerun and interrupted upgrade. Preserve settings/resume/recovery. Do not force-kill past unfinished display restoration. |
| Signing | Record signed/unsigned status honestly. Certificate purchase/signing is a separate owner choice, not authorization given by this review and not proof that an unsigned binary is corrupt. |

No assumption is made that a previously published upgrade baseline exists: Git/docs describe local packaging work, not a proven public installer history. Establish supported old-package inputs before migration acceptance.

### Should complete before 1.0

- Coalesce progress/UI work and reduce per-byte IPC overhead (S01–S02), guided by measurements.
- Add a repeat-session endurance check recording child process, handle, native-memory and JavaFX queue behavior.
- Distinguish operational logs by session and executor; document diagnostic collection and recovery decisions (S03).
- Prefer fixed/reviewed packaging tool versions and record the exact JDK, mpv, dependency and build provenance used for accepted artifacts.
- Reconcile README/ADR/lessons after verified fixes, particularly the disproven metadata-driver explanation and claims about bounded IPC timeouts. Preserve historical observations without presenting obsolete conclusions as current facts.

### Safe to defer after 1.0

- Native HDR, additional expensive connector-specific support and any explicitly deferred non-MVP features.
- A custom ScreenPilot wire protocol/version negotiation when there is no independent ScreenPilot IPC client.
- A new UI framework, generalized plugin architecture, in-app self-updater or cosmetic redesign.
- Sophisticated monitor heuristics where the safe 1.0 behavior can be explicit refusal of an ambiguous target. Do not defer the refusal itself.

## Recommended post-fix acceptance matrix

All unexecuted rows below are **NOT TESTED**, not release failures discovered by this review. Final acceptance follows implementation.

| Dimension | Cases and required observation |
| --- | --- |
| Windows/hardware | Windows 10 build 19045 baseline and Windows 11; actual GPU/driver identity; laptop internal only, HDMI active/inactive/cloned, multi-GPU/multiple external, virtual/remote session. |
| Geometry/DPI | 100/125/150/200%, mixed scaling, primary vs nonprimary external, left/top negative origins, portrait and Windows rearrangement. Confirm physical output ownership before and during playback. |
| Lifecycle | Startup, repeated launch, initialization failure, normal stop, repeated close, forced termination, sleep/wake, lock/unlock and logoff/shutdown. Distinguish process containment from display recovery. |
| Fault timing | Disconnect during capture, extend, mode apply, player start, load, seek, EOF and restore; delayed/stale events; disk/access failure. Verify built-in display unchanged and journal truthful. |
| Media | Required codecs/bit depths/HDR→SDR, broken/truncated media, Unicode/long paths, unavailable file, tracks/subtitles and actual HDMI sound. No laptop preview. |
| State continuity | Pause/stop/EOF/next/resume, decoder fallback, double actions, queued import/drop, old notification after a new session. |
| Installer | Clean user install without admin/JDK, silent variants, upgrade and interrupted upgrade, running app, both shortcut choices, uninstall/data retention, AppFleet detection, exact artifacts and final EXE/JAR metadata. |

## Recommended order of work to 1.0

1. **Repair and independently verify native contracts:** D01–D03, embedded-target eligibility, safe authoritative mapping and snapshot validation. Re-evaluate historical fallback assumptions before adding hardware workarounds.
2. **Make output operations and teardown testable:** introduce narrow injected ports and a session/operation owner; fix D06–D08 and D11. Establish deterministic shutdown/failure tests before UI expansion.
3. **Repair media and persistent-state ownership:** D09–D10, D12–D14; complete pending recovery UI and schema/migration policy. Add tests for all reproduced failures.
4. **Finish placement/reconciliation and agreed MVP behavior:** D04–D05, R01–R03, idle discovery, automatic mode selection, settings/audio/subtitle contracts. Verify one external output and built-in invariants.
5. **Complete substantive UI/UX work:** D15 and companion priorities; exercise real keyboard, resizing, scale and unavailable-device flows. Add targeted layout/interaction tests.
6. **Complete installer/AppFleet pipeline:** one version/identity source, parsed manifest, final native/JAR metadata and exact artifact validation; isolated install/upgrade/uninstall evidence. Do not publish merely because CI is green.
7. **Run the controlled hardware and release acceptance matrix:** retain PASS/FAIL/NOT TESTED per case, resolve mandatory failures and missing evidence, then make an explicit 1.0 release decision using the exact tested artifacts.

The project is a viable continuing development effort, but current test success is not evidence that native safety or 1.0 readiness has been achieved.
