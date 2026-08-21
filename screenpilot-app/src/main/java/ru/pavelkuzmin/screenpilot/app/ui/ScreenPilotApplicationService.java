package ru.pavelkuzmin.screenpilot.app.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.pavelkuzmin.screenpilot.app.ApplicationPaths;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayInfo;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayMode;
import ru.pavelkuzmin.screenpilot.domain.port.ProcessContainment;
import ru.pavelkuzmin.screenpilot.domain.port.RecoveryJournal;
import ru.pavelkuzmin.screenpilot.domain.port.MediaProbe;
import ru.pavelkuzmin.screenpilot.domain.port.ResumeRepository;
import ru.pavelkuzmin.screenpilot.domain.port.SettingsRepository;
import ru.pavelkuzmin.screenpilot.domain.recovery.RecoveryRecord;
import ru.pavelkuzmin.screenpilot.domain.media.MediaFingerprint;
import ru.pavelkuzmin.screenpilot.domain.media.MediaInfo;
import ru.pavelkuzmin.screenpilot.domain.media.Playlist;
import ru.pavelkuzmin.screenpilot.domain.media.PlaylistItem;
import ru.pavelkuzmin.screenpilot.domain.media.PlaylistMutation;
import ru.pavelkuzmin.screenpilot.domain.media.ResumeEntry;
import ru.pavelkuzmin.screenpilot.domain.media.ResumePolicy;
import ru.pavelkuzmin.screenpilot.domain.session.OutputSessionEvent;
import ru.pavelkuzmin.screenpilot.domain.session.OutputSessionState;
import ru.pavelkuzmin.screenpilot.domain.session.OutputSessionStateMachine;
import ru.pavelkuzmin.screenpilot.domain.session.PlayerNotification;
import ru.pavelkuzmin.screenpilot.domain.session.PlayerState;
import ru.pavelkuzmin.screenpilot.domain.settings.ScalingMode;
import ru.pavelkuzmin.screenpilot.domain.settings.AppSettings;
import ru.pavelkuzmin.screenpilot.platform.windows.WindowsDisplayMutator;
import ru.pavelkuzmin.screenpilot.platform.windows.WindowsDisplayPoller;
import ru.pavelkuzmin.screenpilot.platform.windows.WindowsDisplaySnapshot;
import ru.pavelkuzmin.screenpilot.platform.windows.WindowsMpvWindowLocator;
import ru.pavelkuzmin.screenpilot.platform.windows.WindowsProcessContainment;
import ru.pavelkuzmin.screenpilot.platform.windows.WindowsDisplayDiscovery;
import ru.pavelkuzmin.screenpilot.persistence.FileRecoveryJournal;
import ru.pavelkuzmin.screenpilot.persistence.JsonResumeRepository;
import ru.pavelkuzmin.screenpilot.persistence.JsonSettingsRepository;
import ru.pavelkuzmin.screenpilot.player.mpv.MpvMediaProbe;
import ru.pavelkuzmin.screenpilot.player.mpv.MpvPlayerAdapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.BufferedReader;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Serial application boundary for UI commands. Windows discovery and file access never run on the
 * JavaFX Application Thread. It is also the sole owner of the output-session state machine.
 */
public final class ScreenPilotApplicationService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ScreenPilotApplicationService.class);

    private final DisplayDiscovery displayDiscovery;
    private final UiStateStore store;
    private final ExecutorService serial;
    private final WindowsDisplayMutator displayMutator;
    private final RecoveryJournal recoveryJournal;
    private final WindowsMpvWindowLocator windowLocator;
    private final SettingsRepository settingsRepository;
    private final ResumeRepository resumeRepository;
    private final MediaProbe mediaProbe;
    private ActiveOutput activeOutput;
    private AppSettings settings = AppSettings.defaults();
    private Instant lastResumeSavedAt = Instant.MIN;
    /** Choices made during this process prevent the same persisted entry from being offered twice. */
    private final Map<MediaFingerprint, ResumeEntry> resumeDecisions = new HashMap<>();
    private PendingStart pendingStartAfterResumeChoice;
    private boolean endOfFilePending;
    private boolean suppressNextIdleTransition;

    public ScreenPilotApplicationService(UiStateStore store) {
        this(
                new WindowsDisplayDiscovery()::discoverDisplays,
                store,
                newApplicationSerialExecutor(),
                new WindowsDisplayMutator(),
                new FileRecoveryJournal(applicationDataDirectory()),
                new WindowsMpvWindowLocator(),
                new JsonSettingsRepository(applicationDataDirectory()),
                new JsonResumeRepository(applicationDataDirectory()),
                new MpvMediaProbe(findMpvExecutable())
        );
    }

    ScreenPilotApplicationService(
            DisplayDiscovery displayDiscovery,
            UiStateStore store,
            ExecutorService serial,
            WindowsDisplayMutator displayMutator,
            RecoveryJournal recoveryJournal,
            WindowsMpvWindowLocator windowLocator
    ) {
        this(displayDiscovery, store, serial, displayMutator, recoveryJournal, windowLocator,
                new JsonSettingsRepository(applicationDataDirectory()),
                new JsonResumeRepository(applicationDataDirectory()),
                new MpvMediaProbe(findMpvExecutable()));
    }

    ScreenPilotApplicationService(
            DisplayDiscovery displayDiscovery,
            UiStateStore store,
            ExecutorService serial,
            WindowsDisplayMutator displayMutator,
            RecoveryJournal recoveryJournal,
            WindowsMpvWindowLocator windowLocator,
            SettingsRepository settingsRepository,
            ResumeRepository resumeRepository,
            MediaProbe mediaProbe
    ) {
        this.displayDiscovery = Objects.requireNonNull(displayDiscovery, "displayDiscovery");
        this.store = Objects.requireNonNull(store, "store");
        this.serial = Objects.requireNonNull(serial, "serial");
        this.displayMutator = Objects.requireNonNull(displayMutator, "displayMutator");
        this.recoveryJournal = Objects.requireNonNull(recoveryJournal, "recoveryJournal");
        this.windowLocator = Objects.requireNonNull(windowLocator, "windowLocator");
        this.settingsRepository = Objects.requireNonNull(settingsRepository, "settingsRepository");
        this.resumeRepository = Objects.requireNonNull(resumeRepository, "resumeRepository");
        this.mediaProbe = Objects.requireNonNull(mediaProbe, "mediaProbe");
    }

    public void start() {
        serial.execute(() -> {
            LOG.info("ScreenPilot {} started on {} {}; Java {}",
                    System.getProperty("screenpilot.version", "dev"), System.getProperty("os.name"),
                    System.getProperty("os.version"), System.getProperty("java.runtime.version"));
            loadSettingsOnSerial();
            refreshDisplays();
        });
    }

    public void refreshDisplays() {
        serial.execute(() -> {
            ApplicationState state = store.current();
            if (activeOutput != null || isOutputTransitionInProgress(state.outputState())) {
                store.publish(state.withUserMessage(
                        "Нельзя обновлять список экранов, пока ScreenPilot управляет внешним выводом."));
                return;
            }
            store.publish(state.withRefreshInProgress());
            try {
                store.publish(store.current().withDisplays(displayDiscovery.discover()));
            } catch (IOException exception) {
                LOG.warn("Display discovery failed", exception);
                store.publish(store.current().withUserMessage(
                        "Не удалось прочитать конфигурацию экранов. Попробуйте обновить список."));
            }
        });
    }

    public void selectTarget(DisplayInfo target) {
        serial.execute(() -> {
            try {
                store.publish(store.current().withSelectedTarget(target));
            } catch (IllegalArgumentException exception) {
                store.publish(store.current().withUserMessage("Выбранный внешний экран больше недоступен."));
            }
        });
    }

    public void selectMode(DisplayMode mode) {
        serial.execute(() -> {
            try {
                store.publish(store.current().withSelectedMode(mode));
            } catch (IllegalArgumentException | IllegalStateException exception) {
                store.publish(store.current().withUserMessage("Выбранный режим больше недоступен."));
            }
        });
    }

    public void selectMedia(Path media) {
        addMediaFiles(media == null ? List.of() : List.of(media));
    }

    public void addMediaFiles(List<Path> mediaFiles) {
        List<Path> files = List.copyOf(Objects.requireNonNullElse(mediaFiles, List.of()));
        serial.execute(() -> {
            if (files.isEmpty()) {
                return;
            }
            int added = 0;
            int duplicates = 0;
            for (Path candidate : files) {
                try {
                    PlaylistItem item = PlaylistItem.pending(fingerprint(candidate));
                    PlaylistMutation mutation = addPlaylistItem(item);
                    if (mutation.added()) {
                        added++;
                        scheduleProbe(item);
                    } else {
                        duplicates++;
                    }
                } catch (IOException | IllegalArgumentException exception) {
                    store.publish(store.current().withUserMessage("Не удалось открыть выбранный видеофайл."));
                }
            }
            if (added > 0 || duplicates > 0) {
                String message = added > 0
                        ? "Добавлено файлов: " + added + (duplicates > 0 ? "; повторов: " + duplicates + "." : ".")
                        : "Этот файл уже есть в плейлисте.";
                store.publish(store.current().withUserMessage(message));
            }
        });
    }

    public void selectPlaylistItem(int index) {
        serial.execute(() -> {
            try {
                pendingStartAfterResumeChoice = null;
                Playlist playlist = store.current().playlist().select(index);
                PlaylistItem item = playlist.selectedItem().orElseThrow();
                store.publish(store.current().withPlaylist(playlist, resumeOffer(item), "Выбран файл из плейлиста."));
            } catch (IllegalArgumentException exception) {
                store.publish(store.current().withUserMessage("Выбранный элемент плейлиста больше недоступен."));
            }
        });
    }

    public void removeSelectedPlaylistItem() {
        serial.execute(() -> {
            ApplicationState state = store.current();
            if (state.playback().controlsAvailable()) {
                store.publish(state.withUserMessage("Сначала остановите видео или выберите другой файл после остановки."));
                return;
            }
            Playlist playlist = state.playlist().removeSelected();
            store.publish(state.withPlaylist(playlist, null,
                    playlist.items().isEmpty() ? "Плейлист пуст." : "Элемент удалён из плейлиста."));
        });
    }

    public void moveSelectedPlaylistItem(int offset) {
        serial.execute(() -> {
            Playlist playlist = store.current().playlist().moveSelectedBy(offset);
            if (playlist == store.current().playlist()) {
                return;
            }
            store.publish(store.current().withPlaylist(playlist, null, "Порядок плейлиста изменён."));
        });
    }

    public void movePlaylistItem(int sourceIndex, int destinationIndex) {
        serial.execute(() -> {
            Playlist playlist = store.current().playlist().move(sourceIndex, destinationIndex);
            if (playlist == store.current().playlist()) {
                return;
            }
            store.publish(store.current().withPlaylist(playlist, null, "Порядок плейлиста изменён."));
        });
    }

    public void resolveResume(boolean resume) {
        serial.execute(() -> {
            ApplicationState state = store.current();
            ResumeEntry offer = state.resumeOffer();
            PlaylistItem item = state.selectedPlaylistItem().orElse(null);
            if (offer == null || item == null) {
                return;
            }
            if (resume) {
                resumeDecisions.put(item.fingerprint(), offer);
            } else {
                resumeRepository.markCompleted(item.fingerprint());
                resumeDecisions.remove(item.fingerprint());
            }
            store.publish(state.withResumeDecision(resume));

            PendingStart pendingStart = pendingStartAfterResumeChoice;
            pendingStartAfterResumeChoice = null;
            if (pendingStart == null) {
                return;
            }
            if (pendingStart.mpvScreenCandidate() == null) {
                startPlaybackOnSerial(true);
            } else {
                startOutputOnSerial(pendingStart.mpvScreenCandidate(), true);
            }
        });
    }

    public void startOutput(int mpvScreenCandidate) {
        if (mpvScreenCandidate < 0) {
            throw new IllegalArgumentException("mpv screen candidate must be non-negative");
        }
        serial.execute(() -> startOutputOnSerial(mpvScreenCandidate));
    }

    /** Loads the selected file into a still-prepared, black mpv output session. */
    public void startPlayback() {
        serial.execute(this::startPlaybackOnSerial);
    }

    public void stopOutput() {
        serial.execute(() -> stopOutputOnSerial("Вывод на внешний экран остановлен."));
    }

    public void stopOutput(Runnable completed) {
        Objects.requireNonNull(completed, "completed");
        serial.execute(() -> {
            stopOutputOnSerial("ScreenPilot завершает работу.");
            completed.run();
        });
    }

    /** Builds a support report off the JavaFX thread, then hands the text to the UI clipboard action. */
    public void copyDiagnostics(Consumer<String> completed) {
        Objects.requireNonNull(completed, "completed");
        serial.execute(() -> {
            String report = DiagnosticReport.render(store.current(), recentLogLines());
            store.publish(store.current().withUserMessage("Диагностика скопирована в буфер обмена."));
            completed.accept(report);
        });
    }

    public void togglePause() {
        commandActivePlayer("Не удалось изменить состояние воспроизведения.", player -> {
            PlayerState playerState = player.state();
            return switch (playerState) {
                case PLAYING -> player.pause();
                case PAUSED -> player.play();
                default -> throw new IllegalStateException("Воспроизведение ещё не готово к паузе");
            };
        });
    }

    /** Stops only the media stream; mpv and the prepared external output remain black and active. */
    public void stopPlayback() {
        serial.execute(() -> {
            ActiveOutput output = activeOutput;
            ApplicationState state = store.current();
            if (output == null || state.outputState() != OutputSessionState.OUTPUT_ACTIVE
                    || !state.playback().controlsAvailable()) {
                store.publish(state.withUserMessage("Воспроизведение ещё не готово к остановке."));
                return;
            }
            try {
                persistResume(true);
                output.player().stop().toCompletableFuture()
                        .get(COMMAND_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                transition(OutputSessionEvent.FILE_STOPPED,
                        "Видео остановлено. Внешний экран остаётся чёрным; можно выбрать другой файл.");
                store.publish(store.current().withPlayback(
                        store.current().playback().withPlayerState(PlayerState.IDLE)));
            } catch (Exception exception) {
                store.publish(store.current().withUserMessage(
                        "Не удалось остановить воспроизведение. " + conciseMessage(exception)));
            }
        });
    }

    public void playRelativePlaylistItem(int offset) {
        if (offset == 0) {
            return;
        }
        serial.execute(() -> playRelativePlaylistItemOnSerial(offset));
    }

    public void playSelectedPlaylistItem() {
        serial.execute(() -> playSelectedPlaylistItemOnSerial());
    }

    public void seekRelative(Duration offset) {
        Objects.requireNonNull(offset, "offset");
        commandActivePlayer("Не удалось перемотать видео.", player -> player.seekRelative(offset));
    }

    public void seek(Duration position) {
        Objects.requireNonNull(position, "position");
        commandActivePlayer("Не удалось перемотать видео.", player -> player.seek(position));
    }

    public void setVolume(int volumePercent) {
        if (volumePercent < 0 || volumePercent > 100) {
            throw new IllegalArgumentException("volumePercent must be between 0 and 100");
        }
        commandActivePlayer("Не удалось изменить громкость.", player -> player.setVolume(volumePercent),
                current -> current.withVolumePercent(volumePercent));
    }

    public void setScaling(ScalingMode scalingMode) {
        Objects.requireNonNull(scalingMode, "scalingMode");
        commandActivePlayer("Не удалось изменить масштабирование.", player -> player.setScaling(scalingMode),
                current -> current.withScalingMode(scalingMode));
    }

    public void selectAudioOutput(String deviceId) {
        commandActivePlayer("Не удалось изменить аудиовывод.", player -> player.selectAudioOutput(deviceId));
    }

    public void selectAudioTrack(int trackId) {
        commandActivePlayer("Не удалось изменить аудиодорожку.", player -> player.selectAudioTrack(trackId));
    }

    public void selectSubtitle(OptionalInt trackId) {
        commandActivePlayer("Не удалось изменить субтитры.", player -> player.selectSubtitle(trackId));
    }

    public void addExternalSubtitle(Path subtitleFile) {
        serial.execute(() -> {
            ActiveOutput output = activeOutput;
            if (output == null || !store.current().playback().controlsAvailable()) {
                store.publish(store.current().withUserMessage("Сначала запустите видео, затем подключите субтитры."));
                return;
            }
            try {
                output.player().addExternalSubtitle(subtitleFile).toCompletableFuture()
                        .get(COMMAND_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                store.publish(store.current().withUserMessage("Файл субтитров подключён."));
            } catch (Exception exception) {
                store.publish(store.current().withUserMessage("SUB-001: Не удалось подключить файл субтитров."));
            }
        });
    }

    @Override
    public void close() {
        serial.shutdownNow();
        mediaProbe.close();
    }

    private void startOutputOnSerial(int mpvScreenCandidate) {
        startOutputOnSerial(mpvScreenCandidate, false);
    }

    private void startOutputOnSerial(int mpvScreenCandidate, boolean resumeDecisionAlreadyMade) {
        ApplicationState state = store.current();
        if (!state.readyForOutput()) {
            store.publish(state.withUserMessage("Сначала выберите доступный внешний экран и видеофайл."));
            return;
        }
        if (!resumeDecisionAlreadyMade && requireResumeDecision(state, new PendingStart(mpvScreenCandidate))) {
            return;
        }
        if (activeOutput != null || state.outputState() == OutputSessionState.PREPARING_DISPLAY
                || state.outputState() == OutputSessionState.RESTORING_DISPLAY) {
            store.publish(state.withUserMessage("Предыдущая команда вывода ещё выполняется."));
            return;
        }
        if (recoveryJournal.findUnfinished().isPresent()) {
            store.publish(state.withOutputState(OutputSessionState.OUTPUT_ERROR,
                    "Сначала восстановите конфигурацию экранов из незавершённой предыдущей сессии."));
            return;
        }

        if (state.outputState() != OutputSessionState.TARGET_READY) {
            store.publish(state.withUserMessage("Сначала завершите восстановление предыдущего вывода."));
            return;
        }

        DisplayInfo requestedTarget = state.selectedTarget().orElseThrow();
        Path media = state.selectedMedia();
        mediaProbe.cancelAll();
        transition(OutputSessionEvent.START_REQUESTED, "Подготавливаю внешний экран…");
        WindowsDisplaySnapshot snapshot = null;
        RecoveryRecord record = null;
        MpvPlayerAdapter player = null;
        WindowsDisplayPoller poller = null;
        try {
            snapshot = displayMutator.captureSnapshot(requestedTarget, true);
            record = RecoveryRecord.begin(UUID.randomUUID(), Instant.now(), snapshot.toRecoveryPayload());
            recoveryJournal.begin(record);

            DisplayInfo activeTarget = displayMutator.ensureExtendedTopology(requestedTarget, true);
            DisplayMode selectedMode = state.selectedMode();
            if (selectedMode != null && !selectedMode.equals(activeTarget.currentMode())) {
                displayMutator.applyTemporaryMode(snapshot, activeTarget, selectedMode);
                activeTarget = findTarget(activeTarget);
            }

            player = new MpvPlayerAdapter(findMpvExecutable(), processContainment(), mpvScreenCandidate);
            LOG.info("Starting external output on target {} with mode {}", requestedTarget.friendlyName(), selectedModeLabel(state.selectedMode()));
            player.start().toCompletableFuture().get(START_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            String title = player.windowTitle().orElseThrow(() -> new IOException("mpv did not expose its window title"));
            if (!windowLocator.waitForWindowCenteredOn(title, activeTarget.bounds(), WINDOW_TIMEOUT)) {
                throw new IOException("mpv window was not placed on the selected external target");
            }

            poller = createDisplayPoller(activeTarget);
            PlayerNotifications notifications = new PlayerNotifications();
            player.notifications().subscribe(notifications);
            activeOutput = new ActiveOutput(player, poller, snapshot, record, activeTarget, notifications);
            transition(OutputSessionEvent.DISPLAY_PREPARED, "Экран подготовлен. Открываю видео…");
            poller.start();
            player.load(media, state.requestedStartPosition()).toCompletableFuture()
                    .get(LOAD_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            transition(OutputSessionEvent.FILE_LOADED, "Видео воспроизводится на выбранном внешнем экране.");
        } catch (Exception exception) {
            LOG.warn("External output start failed", exception);
            ActiveOutput unfinished = activeOutput;
            if (unfinished != null && unfinished.player() == player) {
                activeOutput = null;
                closeQuietly(unfinished.notifications());
                closeQuietly(unfinished.poller());
                closeQuietly(unfinished.player());
            } else {
                closeQuietly(poller);
                closeQuietly(player);
            }
            restoreAfterFailedStart(snapshot, record, exception);
        }
    }

    private void startPlaybackOnSerial() {
        startPlaybackOnSerial(false);
    }

    private void startPlaybackOnSerial(boolean resumeDecisionAlreadyMade) {
        ApplicationState state = store.current();
        ActiveOutput output = activeOutput;
        if (output == null || state.outputState() != OutputSessionState.OUTPUT_IDLE) {
            store.publish(state.withUserMessage("Сначала подготовьте вывод на внешний экран."));
            return;
        }
        if (!resumeDecisionAlreadyMade && requireResumeDecision(state, new PendingStart(null))) {
            return;
        }
        Path media = state.selectedMedia();
        if (media == null || !Files.isRegularFile(media) || !Files.isReadable(media)) {
            store.publish(state.withUserMessage("Выберите доступный видеофайл перед воспроизведением."));
            return;
        }
        try {
            store.publish(state.withUserMessage("Открываю видео на подготовленном внешнем экране…"));
            LOG.info("Opening media file {}", media.getFileName());
            mediaProbe.cancelAll();
            output.player().load(media, state.requestedStartPosition()).toCompletableFuture()
                    .get(LOAD_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            transition(OutputSessionEvent.FILE_LOADED, "Видео воспроизводится на выбранном внешнем экране.");
        } catch (Exception exception) {
            LOG.warn("Loading media into prepared output failed", exception);
            store.publish(store.current().withUserMessage(
                    "Не удалось открыть выбранный видеофайл. Внешний экран остаётся подготовленным. "
                            + conciseMessage(exception)));
        }
    }

    private WindowsDisplayPoller createDisplayPoller(DisplayInfo target) {
        return new WindowsDisplayPoller(new WindowsDisplayDiscovery(), new WindowsDisplayPoller.Listener() {
            @Override
            public void onTopologyChanged(ru.pavelkuzmin.screenpilot.domain.display.DisplayTopologyChanged event) {
                boolean present = event.displays().stream()
                        .anyMatch(display -> display.targetAddress().equals(target.targetAddress()) && display.active());
                if (!present) {
                    serial.execute(() -> handleTargetLostOnSerial());
                }
            }

            @Override
            public void onPollingFailure(IOException exception) {
                serial.execute(() -> store.publish(store.current().withUserMessage(
                        "Не удалось обновить состояние внешнего экрана. Остановите вывод.")));
            }
        });
    }

    private void handleTargetLostOnSerial() {
        if (activeOutput == null) {
            return;
        }
        try {
            activeOutput.player().pause().toCompletableFuture().get(COMMAND_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // The target loss can cause mpv to close its IPC channel before pause arrives.
        }
        failActiveOutputOnSerial(OutputSessionEvent.TARGET_LOST,
                "Внешний экран отключён. Воспроизведение остановлено.");
    }

    private void stopOutputOnSerial(String successMessage) {
        ActiveOutput output = activeOutput;
        if (output == null) {
            return;
        }
        activeOutput = null;
        LOG.info("Stopping external output and restoring the display snapshot");
        transition(OutputSessionEvent.STOP_OUTPUT_REQUESTED, "Восстанавливаю исходную конфигурацию экранов…");
        persistResume(true);
        closeQuietly(output.notifications());
        closeQuietly(output.poller());
        closeQuietly(output.player());
        try {
            displayMutator.restore(output.snapshot());
            recoveryJournal.markRestored(output.recoveryRecord().sessionId());
            List<DisplayInfo> displays = displayDiscovery.discover();
            transition(OutputSessionEvent.RESTORE_SUCCEEDED, successMessage);
            store.publish(store.current().withDisplays(displays).withUserMessage(successMessage));
        } catch (Exception exception) {
            LOG.error("Display restoration failed", exception);
            transition(OutputSessionEvent.RESTORE_FAILED,
                    "Не удалось полностью восстановить экраны. Откройте восстановление при следующем запуске.");
        }
    }

    private void failActiveOutputOnSerial(OutputSessionEvent failure, String message) {
        ActiveOutput output = activeOutput;
        if (output == null) {
            return;
        }
        activeOutput = null;
        transition(failure, message);
        persistResume(true);
        closeQuietly(output.notifications());
        closeQuietly(output.poller());
        closeQuietly(output.player());
        try {
            displayMutator.restore(output.snapshot());
            recoveryJournal.markRestored(output.recoveryRecord().sessionId());
            store.publish(store.current().withDisplays(displayDiscovery.discover()).withUserMessage(message));
        } catch (Exception exception) {
            store.publish(store.current().withOutputState(OutputSessionState.OUTPUT_ERROR,
                    message + " Не удалось полностью восстановить экраны; восстановите их при следующем запуске."));
        }
    }

    private void restoreAfterFailedStart(
            WindowsDisplaySnapshot snapshot,
            RecoveryRecord record,
            Exception originalFailure
    ) {
        String userMessage = startFailureMessage(originalFailure);
        if (store.current().outputState() == OutputSessionState.PREPARING_DISPLAY) {
            transition(OutputSessionEvent.PREPARATION_FAILED, userMessage);
        } else if (store.current().outputState() == OutputSessionState.OUTPUT_IDLE
                || store.current().outputState() == OutputSessionState.OUTPUT_ACTIVE) {
            transition(OutputSessionEvent.OUTPUT_FAILED, userMessage);
        }
        if (snapshot == null || record == null) {
            store.publish(store.current().withOutputState(OutputSessionState.OUTPUT_ERROR, userMessage));
            return;
        }
        try {
            displayMutator.restore(snapshot);
            recoveryJournal.markRestored(record.sessionId());
            store.publish(store.current().withDisplays(displayDiscovery.discover()).withUserMessage(userMessage));
        } catch (Exception restoreFailure) {
            originalFailure.addSuppressed(restoreFailure);
            store.publish(store.current().withOutputState(OutputSessionState.OUTPUT_ERROR,
                    "Не удалось подготовить вывод и восстановить экраны. Восстановите их при следующем запуске."));
        }
    }

    private PlaylistMutation addPlaylistItem(PlaylistItem item) throws IOException {
        ApplicationState state = store.current();
        pendingStartAfterResumeChoice = null;
        PlaylistMutation mutation = state.playlist().addOrSelect(item);
        ResumeEntry offer = resumeOffer(mutation.playlist().selectedItem().orElseThrow());
        store.publish(state.withPlaylist(mutation.playlist(), offer,
                mutation.added() ? "Файл добавлен в плейлист." : "Этот файл уже есть в плейлисте."));
        rememberLastMediaFolder(item.source().getParent());
        return mutation;
    }

    private void scheduleProbe(PlaylistItem item) {
        mediaProbe.probe(item.source()).whenComplete((mediaInfo, failure) -> serial.execute(() -> {
            PlaylistItem current = store.current().playlist().items().stream()
                    .filter(candidate -> candidate.fingerprint().equals(item.fingerprint()))
                    .findFirst()
                    .orElse(null);
            if (current == null || current.probeStatus() != ru.pavelkuzmin.screenpilot.domain.media.MediaProbeStatus.PENDING) {
                return;
            }
            PlaylistItem updated = failure == null && mediaInfo != null
                    ? current.withMediaInfo(mediaInfo)
                    : current.withProbeFailure();
            store.publish(store.current().withProbedPlaylistItem(updated));
        }));
    }

    private ResumeEntry resumeOffer(PlaylistItem item) {
        ResumeEntry candidate = resumeRepository.find(item.fingerprint())
                .filter(ResumePolicy::shouldOfferResume)
                .orElse(null);
        return candidate != null && !candidate.equals(resumeDecisions.get(item.fingerprint())) ? candidate : null;
    }

    private boolean requireResumeDecision(ApplicationState state, PendingStart pendingStart) {
        PlaylistItem item = state.selectedPlaylistItem().orElse(null);
        if (item == null) {
            return false;
        }
        ResumeEntry offer = resumeOffer(item);
        if (offer == null) {
            return false;
        }
        pendingStartAfterResumeChoice = pendingStart;
        store.publish(state.withPlaylist(state.playlist(), offer,
                "Для выбранного видео сохранена позиция. Выберите, продолжить просмотр или начать с начала."));
        return true;
    }

    private static MediaFingerprint fingerprint(Path candidate) throws IOException {
        Path source = Objects.requireNonNull(candidate, "candidate").toAbsolutePath().normalize();
        if (!Files.isRegularFile(source) || !Files.isReadable(source)) {
            throw new IOException("Media file is not a readable regular file: " + source);
        }
        return new MediaFingerprint(source.toString(), Files.size(source), Files.getLastModifiedTime(source).toInstant());
    }

    private void loadSettingsOnSerial() {
        try {
            settings = settingsRepository.load();
            String folder = settings.lastFolder();
            if (folder != null && !folder.isBlank()) {
                Path path = Path.of(folder);
                if (Files.isDirectory(path) && Files.isReadable(path)) {
                    store.publish(store.current().withLastMediaFolder(path));
                }
            }
        } catch (RuntimeException exception) {
            settings = AppSettings.defaults();
            store.publish(store.current().withUserMessage("Настройки повреждены. Загружены безопасные значения."));
        }
    }

    private void rememberLastMediaFolder(Path folder) {
        if (folder == null || !Files.isDirectory(folder) || !Files.isReadable(folder)) {
            return;
        }
        Path normalized = folder.toAbsolutePath().normalize();
        settings = new AppSettings(
                AppSettings.CURRENT_SCHEMA_VERSION,
                normalized.toString(),
                settings.lastTargetId(),
                settings.automaticModeSelection(),
                settings.manualDisplayMode(),
                settings.scalingMode(),
                settings.audioDeviceId(),
                settings.volumePercent(),
                settings.muted(),
                settings.prioritizeSmoothness(),
                settings.windowWidth(),
                settings.windowHeight(),
                settings.windowX(),
                settings.windowY(),
                settings.playerProfile()
        );
        try {
            settingsRepository.save(settings);
            store.publish(store.current().withLastMediaFolder(normalized));
        } catch (RuntimeException exception) {
            store.publish(store.current().withUserMessage("Не удалось сохранить последнюю папку."));
        }
    }

    private void persistResume(boolean force) {
        ApplicationState state = store.current();
        PlaylistItem item = state.selectedPlaylistItem().orElse(null);
        if (item == null || state.playback().duration().isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        if (!force && Duration.between(lastResumeSavedAt, now).compareTo(Duration.ofSeconds(10)) < 0) {
            return;
        }
        Duration position = state.playback().position();
        Duration duration = state.playback().duration().orElseThrow();
        if (ResumePolicy.isCompleted(position, duration)) {
            resumeRepository.markCompleted(item.fingerprint());
        } else if (position.compareTo(ResumePolicy.MINIMUM_RESUMABLE_POSITION) >= 0) {
            resumeRepository.save(new ResumeEntry(item.fingerprint(), position, duration, now));
        }
        lastResumeSavedAt = now;
    }

    private void commandActivePlayer(String failureMessage, PlayerCommand command) {
        commandActivePlayer(failureMessage, command, UnaryOperator.identity());
    }

    private void commandActivePlayer(
            String failureMessage,
            PlayerCommand command,
            UnaryOperator<PlaybackUiState> stateUpdate
    ) {
        serial.execute(() -> {
            ActiveOutput output = activeOutput;
            if (output == null || !store.current().playback().controlsAvailable()) {
                store.publish(store.current().withUserMessage("Воспроизведение ещё не готово к управлению."));
                return;
            }
            try {
                command.execute(output.player()).toCompletableFuture()
                        .get(COMMAND_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                PlaybackUiState updated = stateUpdate.apply(store.current().playback());
                store.publish(store.current().withPlayback(updated));
            } catch (Exception exception) {
                store.publish(store.current().withUserMessage(failureMessage + " " + conciseMessage(exception)));
            }
        });
    }

    private void handlePlayerNotificationOnSerial(PlayerNotification notification) {
        if (activeOutput == null) {
            return;
        }
        ApplicationState current = store.current();
        if (notification instanceof PlayerNotification.EndOfFile) {
            endOfFilePending = true;
            store.current().selectedPlaylistItem().ifPresent(item -> resumeRepository.markCompleted(item.fingerprint()));
            persistResume(true);
            return;
        }
        if (notification instanceof PlayerNotification.StateChanged changed && changed.state() == PlayerState.IDLE) {
            if (suppressNextIdleTransition) {
                suppressNextIdleTransition = false;
            } else if (current.outputState() == OutputSessionState.OUTPUT_ACTIVE) {
                transition(OutputSessionEvent.FILE_STOPPED,
                        endOfFilePending
                                ? "Файл завершён. Перехожу к следующему элементу плейлиста…"
                                : "Видео остановлено. Внешний экран остаётся чёрным; можно выбрать другой файл.");
                current = store.current();
            }
        }
        store.publish(current.withPlayback(current.playback().withNotification(notification)));
        if (notification instanceof PlayerNotification.PlaybackProgress) {
            persistResume(false);
        } else if (notification instanceof PlayerNotification.StateChanged changed && changed.state() == PlayerState.PAUSED) {
            persistResume(true);
        } else if (notification instanceof PlayerNotification.MediaLoaded loaded) {
            updateSelectedPlaylistMetadata(loaded.media());
        }
        if (notification instanceof PlayerNotification.Diagnostic diagnostic) {
            LOG.warn("Player diagnostic {}: {}", diagnostic.code(), diagnostic.message());
            store.publish(store.current().withUserMessage(diagnostic.message()));
        } else if (notification instanceof PlayerNotification.Failure failure) {
            LOG.error("Player failure {}: {}", failure.code(), failure.message());
            failActiveOutputOnSerial(OutputSessionEvent.OUTPUT_FAILED,
                    "Видеоплеер остановлен из-за ошибки: " + failure.message());
        }
        if (notification instanceof PlayerNotification.StateChanged changed && changed.state() == PlayerState.IDLE
                && endOfFilePending) {
            endOfFilePending = false;
            playRelativePlaylistItemOnSerial(1);
        }
    }

    private void updateSelectedPlaylistMetadata(MediaInfo mediaInfo) {
        PlaylistItem item = store.current().selectedPlaylistItem().orElse(null);
        if (item != null && item.source().equals(mediaInfo.source())) {
            store.publish(store.current().withProbedPlaylistItem(item.withMediaInfo(mediaInfo)));
        }
    }

    private void playRelativePlaylistItemOnSerial(int offset) {
        ApplicationState state = store.current();
        int destination = state.playlist().selectedIndex() + offset;
        if (destination < 0 || destination >= state.playlist().items().size()) {
            store.publish(state.withUserMessage(offset < 0 ? "Это первый файл плейлиста." : "Это последний файл плейлиста."));
            return;
        }
        Playlist playlist = state.playlist().select(destination);
        PlaylistItem item = playlist.selectedItem().orElseThrow();
        if (activeOutput == null) {
            store.publish(state.withPlaylist(playlist, resumeOffer(item), "Выбран файл из плейлиста."));
            return;
        }
        if (state.outputState() == OutputSessionState.OUTPUT_ACTIVE) {
            try {
                persistResume(true);
                suppressNextIdleTransition = true;
                activeOutput.player().stop().toCompletableFuture()
                        .get(COMMAND_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                transition(OutputSessionEvent.FILE_STOPPED, "Переключаю файл…");
            } catch (Exception exception) {
                suppressNextIdleTransition = false;
                store.publish(state.withUserMessage("Не удалось переключить файл. " + conciseMessage(exception)));
                return;
            }
        }
        store.publish(store.current().withPlaylist(playlist, null, "Открываю следующий файл…"));
        loadSelectedIntoPreparedOutput();
    }

    private void playSelectedPlaylistItemOnSerial() {
        ApplicationState state = store.current();
        if (state.selectedPlaylistItem().isEmpty()) {
            store.publish(state.withUserMessage("Сначала выберите файл в плейлисте."));
            return;
        }
        if (activeOutput == null) {
            store.publish(state.withUserMessage("Подготовьте внешний экран для воспроизведения."));
            return;
        }
        if (state.outputState() == OutputSessionState.OUTPUT_ACTIVE) {
            playRelativePlaylistItemOnSerial(0);
            return;
        }
        loadSelectedIntoPreparedOutput();
    }

    private void loadSelectedIntoPreparedOutput() {
        loadSelectedIntoPreparedOutput(false);
    }

    private void loadSelectedIntoPreparedOutput(boolean resumeDecisionAlreadyMade) {
        ApplicationState state = store.current();
        if (activeOutput == null || state.outputState() != OutputSessionState.OUTPUT_IDLE || state.selectedMedia() == null) {
            return;
        }
        if (!resumeDecisionAlreadyMade && requireResumeDecision(state, new PendingStart(null))) {
            return;
        }
        try {
            mediaProbe.cancelAll();
            activeOutput.player().load(state.selectedMedia(), state.requestedStartPosition()).toCompletableFuture()
                    .get(LOAD_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            transition(OutputSessionEvent.FILE_LOADED, "Видео воспроизводится на выбранном внешнем экране.");
        } catch (Exception exception) {
            store.publish(store.current().withUserMessage("Не удалось открыть выбранный видеофайл. " + conciseMessage(exception)));
        }
    }

    private void transition(OutputSessionEvent event, String message) {
        ApplicationState current = store.current();
        OutputSessionState next = OutputSessionStateMachine.transition(current.outputState(), event);
        LOG.info("Output transition {} -> {} ({})", current.outputState(), next, event);
        store.publish(current.withOutputState(next, message));
    }

    private static boolean isOutputTransitionInProgress(OutputSessionState state) {
        return state == OutputSessionState.PREPARING_DISPLAY
                || state == OutputSessionState.OUTPUT_IDLE
                || state == OutputSessionState.OUTPUT_ACTIVE
                || state == OutputSessionState.RESTORING_DISPLAY;
    }

    private DisplayInfo findTarget(DisplayInfo target) throws IOException {
        return displayDiscovery.discover().stream()
                .filter(display -> display.targetAddress().equals(target.targetAddress()))
                .filter(DisplayInfo::active)
                .findFirst()
                .orElseThrow(() -> new IOException("Selected target disappeared during display preparation"));
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception ignored) {
            // A later recovery attempt still has the persisted journal.
        }
    }

    private static String conciseMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "неизвестная ошибка" : message;
    }

    static String startFailureMessage(Exception exception) {
        String technicalMessage = conciseMessage(exception);
        if (technicalMessage.contains("modeMatch=false")) {
            return "DSP-005: Windows установила другой режим. Исходные настройки восстановлены. Выберите другой режим.";
        }
        if (technicalMessage.contains("ChangeDisplaySettingsExW(CDS_TEST) rejected")
                || technicalMessage.contains("ChangeDisplaySettingsExW could not apply")) {
            return "DSP-004: Экран или драйвер не принял выбранный режим. Исходные настройки восстановлены.";
        }
        return "Запуск вывода отменён: " + technicalMessage;
    }

    private static ProcessContainment processContainment() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? new WindowsProcessContainment()
                : ProcessContainment.disabled();
    }

    private static Path findMpvExecutable() {
        return ApplicationPaths.findMpvExecutable(ScreenPilotApplicationService.class);
    }

    private static Path applicationDataDirectory() {
        return ApplicationPaths.applicationDataDirectory();
    }

    private static String selectedModeLabel(DisplayMode mode) {
        return mode == null ? "current" : mode.width() + "x" + mode.height() + "@" + mode.refreshRate().hertz();
    }

    private static List<String> recentLogLines() {
        Path logFile = ApplicationPaths.logDirectory().resolve("screenpilot.log");
        if (!Files.isRegularFile(logFile)) {
            return List.of();
        }
        try (BufferedReader reader = Files.newBufferedReader(logFile)) {
            java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>(100);
            for (String line; (line = reader.readLine()) != null; ) {
                if (tail.size() == 100) {
                    tail.removeFirst();
                }
                tail.addLast(line);
            }
            return List.copyOf(tail);
        } catch (IOException exception) {
            LOG.warn("Could not read diagnostic log tail", exception);
            return List.of();
        }
    }

    private record ActiveOutput(
            MpvPlayerAdapter player,
            WindowsDisplayPoller poller,
            WindowsDisplaySnapshot snapshot,
            RecoveryRecord recoveryRecord,
            DisplayInfo target,
            PlayerNotifications notifications
    ) {
    }

    /** {@code null} screen means loading media into an already prepared black output window. */
    private record PendingStart(Integer mpvScreenCandidate) {
        private PendingStart {
            if (mpvScreenCandidate != null && mpvScreenCandidate < 0) {
                throw new IllegalArgumentException("mpv screen candidate must be non-negative");
            }
        }
    }

    private final class PlayerNotifications implements Flow.Subscriber<PlayerNotification>, AutoCloseable {

        private Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription nextSubscription) {
            this.subscription = nextSubscription;
            nextSubscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(PlayerNotification notification) {
            serial.execute(() -> handlePlayerNotificationOnSerial(notification));
        }

        @Override
        public void onError(Throwable throwable) {
            serial.execute(() -> failActiveOutputOnSerial(OutputSessionEvent.OUTPUT_FAILED,
                    "Канал управления видеоплеером завершился с ошибкой."));
        }

        @Override
        public void onComplete() {
            // Normal completion follows an explicit close, which has already started restoration.
        }

        @Override
        public void close() {
            if (subscription != null) {
                subscription.cancel();
            }
        }
    }

    private static final Duration START_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration WINDOW_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration LOAD_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(2);

    private static ExecutorService newApplicationSerialExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "application-serial");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadExecutor(factory);
    }

    @FunctionalInterface
    interface DisplayDiscovery {
        List<DisplayInfo> discover() throws IOException;
    }

    @FunctionalInterface
    private interface PlayerCommand {
        java.util.concurrent.CompletionStage<Void> execute(MpvPlayerAdapter player);
    }
}
