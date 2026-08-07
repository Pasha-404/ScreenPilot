package ru.pavelkuzmin.screenpilot.app.ui;

import ru.pavelkuzmin.screenpilot.domain.display.DisplayInfo;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayMode;
import ru.pavelkuzmin.screenpilot.domain.port.ProcessContainment;
import ru.pavelkuzmin.screenpilot.domain.port.RecoveryJournal;
import ru.pavelkuzmin.screenpilot.domain.recovery.RecoveryRecord;
import ru.pavelkuzmin.screenpilot.domain.session.OutputSessionEvent;
import ru.pavelkuzmin.screenpilot.domain.session.OutputSessionState;
import ru.pavelkuzmin.screenpilot.domain.session.OutputSessionStateMachine;
import ru.pavelkuzmin.screenpilot.domain.session.PlayerNotification;
import ru.pavelkuzmin.screenpilot.domain.session.PlayerState;
import ru.pavelkuzmin.screenpilot.domain.settings.ScalingMode;
import ru.pavelkuzmin.screenpilot.platform.windows.WindowsDisplayMutator;
import ru.pavelkuzmin.screenpilot.platform.windows.WindowsDisplayPoller;
import ru.pavelkuzmin.screenpilot.platform.windows.WindowsDisplaySnapshot;
import ru.pavelkuzmin.screenpilot.platform.windows.WindowsMpvWindowLocator;
import ru.pavelkuzmin.screenpilot.platform.windows.WindowsProcessContainment;
import ru.pavelkuzmin.screenpilot.platform.windows.WindowsDisplayDiscovery;
import ru.pavelkuzmin.screenpilot.persistence.FileRecoveryJournal;
import ru.pavelkuzmin.screenpilot.player.mpv.MpvPlayerAdapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadFactory;
import java.util.function.UnaryOperator;

/**
 * Serial application boundary for UI commands. Windows discovery and file access never run on the
 * JavaFX Application Thread. It is also the sole owner of the output-session state machine.
 */
public final class ScreenPilotApplicationService implements AutoCloseable {

    private final DisplayDiscovery displayDiscovery;
    private final UiStateStore store;
    private final ExecutorService serial;
    private final WindowsDisplayMutator displayMutator;
    private final RecoveryJournal recoveryJournal;
    private final WindowsMpvWindowLocator windowLocator;
    private ActiveOutput activeOutput;

    public ScreenPilotApplicationService(UiStateStore store) {
        this(
                new WindowsDisplayDiscovery()::discoverDisplays,
                store,
                newApplicationSerialExecutor(),
                new WindowsDisplayMutator(),
                new FileRecoveryJournal(applicationDataDirectory()),
                new WindowsMpvWindowLocator()
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
        this.displayDiscovery = Objects.requireNonNull(displayDiscovery, "displayDiscovery");
        this.store = Objects.requireNonNull(store, "store");
        this.serial = Objects.requireNonNull(serial, "serial");
        this.displayMutator = Objects.requireNonNull(displayMutator, "displayMutator");
        this.recoveryJournal = Objects.requireNonNull(recoveryJournal, "recoveryJournal");
        this.windowLocator = Objects.requireNonNull(windowLocator, "windowLocator");
    }

    public void start() {
        refreshDisplays();
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
        serial.execute(() -> {
            Path normalized = media == null ? null : media.toAbsolutePath().normalize();
            if (normalized == null || !Files.isRegularFile(normalized) || !Files.isReadable(normalized)) {
                store.publish(store.current().withUserMessage("Не удалось открыть выбранный видеофайл."));
                return;
            }
            store.publish(store.current().withSelectedMedia(normalized));
        });
    }

    public void startOutput(int mpvScreenCandidate) {
        if (mpvScreenCandidate < 0) {
            throw new IllegalArgumentException("mpv screen candidate must be non-negative");
        }
        serial.execute(() -> startOutputOnSerial(mpvScreenCandidate));
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
        commandActivePlayer("Не удалось остановить воспроизведение.", MpvPlayerAdapter::stop,
                ignored -> PlaybackUiState.idle());
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

    @Override
    public void close() {
        serial.shutdownNow();
    }

    private void startOutputOnSerial(int mpvScreenCandidate) {
        ApplicationState state = store.current();
        if (!state.readyForOutput()) {
            store.publish(state.withUserMessage("Сначала выберите доступный внешний экран и видеофайл."));
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
            player.load(media, Duration.ZERO).toCompletableFuture()
                    .get(LOAD_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            transition(OutputSessionEvent.FILE_LOADED, "Видео воспроизводится на выбранном внешнем экране.");
        } catch (Exception exception) {
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
        transition(OutputSessionEvent.STOP_OUTPUT_REQUESTED, "Восстанавливаю исходную конфигурацию экранов…");
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
        if (store.current().outputState() == OutputSessionState.PREPARING_DISPLAY) {
            transition(OutputSessionEvent.PREPARATION_FAILED,
                    "Не удалось подготовить вывод: " + conciseMessage(originalFailure));
        } else if (store.current().outputState() == OutputSessionState.OUTPUT_IDLE
                || store.current().outputState() == OutputSessionState.OUTPUT_ACTIVE) {
            transition(OutputSessionEvent.OUTPUT_FAILED,
                    "Не удалось запустить вывод: " + conciseMessage(originalFailure));
        }
        if (snapshot == null || record == null) {
            store.publish(store.current().withOutputState(OutputSessionState.OUTPUT_ERROR,
                    "Не удалось подготовить вывод: " + conciseMessage(originalFailure)));
            return;
        }
        try {
            displayMutator.restore(snapshot);
            recoveryJournal.markRestored(record.sessionId());
            store.publish(store.current().withDisplays(displayDiscovery.discover()).withUserMessage(
                    "Запуск вывода отменён: " + conciseMessage(originalFailure)));
        } catch (Exception restoreFailure) {
            originalFailure.addSuppressed(restoreFailure);
            store.publish(store.current().withOutputState(OutputSessionState.OUTPUT_ERROR,
                    "Не удалось подготовить вывод и восстановить экраны. Восстановите их при следующем запуске."));
        }
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
        store.publish(current.withPlayback(current.playback().withNotification(notification)));
        if (notification instanceof PlayerNotification.Diagnostic diagnostic) {
            store.publish(store.current().withUserMessage(diagnostic.message()));
        } else if (notification instanceof PlayerNotification.Failure failure) {
            failActiveOutputOnSerial(OutputSessionEvent.OUTPUT_FAILED,
                    "Видеоплеер остановлен из-за ошибки: " + failure.message());
        }
    }

    private void transition(OutputSessionEvent event, String message) {
        ApplicationState current = store.current();
        OutputSessionState next = OutputSessionStateMachine.transition(current.outputState(), event);
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

    private static ProcessContainment processContainment() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? new WindowsProcessContainment()
                : ProcessContainment.disabled();
    }

    private static Path findMpvExecutable() {
        for (Path current = Path.of("").toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            Path candidate = current.resolve(Path.of("vendor", "mpv", "runtime", "mpv.exe"));
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return Path.of("vendor", "mpv", "runtime", "mpv.exe").toAbsolutePath().normalize();
    }

    private static Path applicationDataDirectory() {
        String localAppData = System.getenv("LOCALAPPDATA");
        return localAppData == null || localAppData.isBlank()
                ? Path.of(System.getProperty("user.home"), "AppData", "Local", "ScreenPilot")
                : Path.of(localAppData).resolve("ScreenPilot");
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
