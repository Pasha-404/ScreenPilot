package ru.pavelkuzmin.screenpilot.player.mpv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import ru.pavelkuzmin.screenpilot.domain.media.AudioOutputDevice;
import ru.pavelkuzmin.screenpilot.domain.media.MediaInfo;
import ru.pavelkuzmin.screenpilot.domain.media.MediaTrack;
import ru.pavelkuzmin.screenpilot.domain.port.ProcessContainment;
import ru.pavelkuzmin.screenpilot.domain.session.PlayerEvent;
import ru.pavelkuzmin.screenpilot.domain.session.PlayerNotification;
import ru.pavelkuzmin.screenpilot.domain.session.PlayerState;
import ru.pavelkuzmin.screenpilot.domain.session.PlayerStateMachine;
import ru.pavelkuzmin.screenpilot.domain.settings.ScalingMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Production adapter for one mpv process. All mutable state lives on application-serial; the IPC
 * reader only enqueues work there, so neither JavaFX nor the reader thread performs player logic.
 */
public final class MpvPlayerAdapter implements AutoCloseable {

    private static final Duration IPC_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration LOAD_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration QUIT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration DESTROY_TIMEOUT = Duration.ofSeconds(2);
    private static final List<String> OBSERVED_PROPERTIES = List.of(
            "pause", "time-pos", "duration", "eof-reached", "idle-active", "media-title", "path",
            "file-format", "video-format", "video-params", "container-fps", "track-list",
            "audio-device-list", "audio-device", "hwdec-current", "estimated-vf-fps"
    );

    private final Path executable;
    private final MpvProcessStarter processStarter;
    private final ProcessContainment containment;
    private final MpvIpcConnector ipcConnector;
    private final MpvLaunchProfileFactory profileFactory;
    private final ScheduledExecutorService serial;
    private final SubmissionPublisher<PlayerNotification> notifications = new SubmissionPublisher<>();

    private volatile PlayerState state = PlayerState.STOPPED;
    private Process process;
    private ProcessContainment.Handle containmentHandle = ProcessContainment.Handle.none();
    private MpvIpcSession ipc;
    private final List<MpvIpcSession.Subscription> ipcSubscriptions = new ArrayList<>();
    private Path loadedFile;
    private Duration pendingStartPosition = Duration.ZERO;
    private Duration lastPosition = Duration.ZERO;
    private Optional<Duration> lastDuration = Optional.empty();
    private List<AudioOutputDevice> audioOutputs = List.of();
    private CompletableFuture<MediaInfo> pendingLoad;
    /** Increments for every process attempt so an old load timeout cannot fail its replacement. */
    private long pendingLoadAttempt;
    private boolean stopRequested;
    private boolean softwareDecode;
    private boolean fallbackAttempted;
    private boolean pauseAfterLoad;
    private volatile String windowTitle;

    public MpvPlayerAdapter(Path executable, ProcessContainment containment) {
        this(executable, new MpvProcessLauncher(), containment, MpvIpcClient::connect, MpvLaunchProfile::forPlayer);
    }

    /**
     * Creates a production player whose fullscreen window is placed on a manually verified mpv
     * screen number. Passing {@code null} preserves the default operating-system placement.
     */
    public MpvPlayerAdapter(Path executable, ProcessContainment containment, Integer targetScreen) {
        this(executable, new MpvProcessLauncher(), containment, MpvIpcClient::connect,
                (playerExecutable, sessionId, softwareDecode) ->
                        MpvLaunchProfile.forPlayer(playerExecutable, sessionId, softwareDecode, targetScreen));
    }

    MpvPlayerAdapter(
            Path executable,
            MpvProcessStarter processStarter,
            ProcessContainment containment,
            MpvIpcConnector ipcConnector,
            MpvLaunchProfileFactory profileFactory
    ) {
        this.executable = Objects.requireNonNull(executable, "executable").toAbsolutePath().normalize();
        this.processStarter = Objects.requireNonNull(processStarter, "processStarter");
        this.containment = Objects.requireNonNull(containment, "containment");
        this.ipcConnector = Objects.requireNonNull(ipcConnector, "ipcConnector");
        this.profileFactory = Objects.requireNonNull(profileFactory, "profileFactory");
        this.serial = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "application-serial");
            thread.setDaemon(true);
            return thread;
        });
    }

    public PlayerState state() {
        return state;
    }

    /** Unique title of the current mpv output window, available after a successful start. */
    public Optional<String> windowTitle() {
        return Optional.ofNullable(windowTitle);
    }

    /** OS process that owns the uniquely titled output window, available after a successful start. */
    public OptionalLong processId() {
        Process currentProcess = process;
        return currentProcess == null ? OptionalLong.empty() : OptionalLong.of(currentProcess.pid());
    }

    public Flow.Publisher<PlayerNotification> notifications() {
        return notifications;
    }

    public CompletionStage<Void> start() {
        return onSerial(() -> {
            if (state == PlayerState.IDLE) {
                return null;
            }
            if (state != PlayerState.STOPPED) {
                throw new IllegalStateException("mpv cannot start while player is " + state);
            }
            startProcess(false);
            return null;
        });
    }

    public CompletionStage<MediaInfo> load(Path file, Duration startPosition) {
        CompletableFuture<MediaInfo> result = new CompletableFuture<>();
        serial.execute(() -> {
            try {
                requireState(PlayerState.IDLE);
                Path normalizedFile = validateMediaFile(file);
                pendingStartPosition = requireNonNegative(startPosition, "startPosition");
                loadedFile = normalizedFile;
                lastPosition = Duration.ZERO;
                lastDuration = Optional.empty();
                pendingLoad = result;
                fallbackAttempted = false;
                transition(PlayerEvent.LOAD_REQUESTED);
                command("loadfile", List.of("loadfile", normalizedFile.toString(), "replace"), COMMAND_TIMEOUT);
                schedulePendingLoadTimeout(result);
            } catch (Exception exception) {
                result.completeExceptionally(exception);
                fail("PLY-003", "Не удалось открыть видеофайл.", exception);
            }
        });
        return result;
    }

    public CompletionStage<Void> play() {
        return setPaused(false);
    }

    public CompletionStage<Void> pause() {
        return setPaused(true);
    }

    public CompletionStage<Void> stop() {
        return onSerial(() -> {
            if (state == PlayerState.IDLE) {
                return null;
            }
            if (state != PlayerState.PLAYING && state != PlayerState.PAUSED && state != PlayerState.LOADING) {
                throw new IllegalStateException("mpv cannot stop media while player is " + state);
            }
            command("stop", List.of("stop"), COMMAND_TIMEOUT);
            if (state == PlayerState.PLAYING || state == PlayerState.PAUSED) {
                transition(PlayerEvent.STOP_MEDIA_REQUESTED);
            } else {
                publishState(PlayerState.IDLE);
            }
            loadedFile = null;
            return null;
        });
    }

    public CompletionStage<Void> seek(Duration requestedPosition) {
        return onSerial(() -> {
            requirePlaybackState();
            Duration position = requireNonNegative(requestedPosition, "requestedPosition");
            if (lastDuration.isPresent() && position.compareTo(lastDuration.get()) > 0) {
                position = lastDuration.get();
            }
            command("seek", List.of("seek", position.toMillis() / 1_000.0, "absolute+exact"), COMMAND_TIMEOUT);
            lastPosition = position;
            return null;
        });
    }

    public CompletionStage<Void> seekRelative(Duration offset) {
        return onSerial(() -> {
            requirePlaybackState();
            command("seek", List.of("seek", offset.toMillis() / 1_000.0, "relative+exact"), COMMAND_TIMEOUT);
            return null;
        });
    }

    public CompletionStage<Void> setVolume(int volumePercent) {
        return onSerial(() -> {
            requirePlaybackState();
            if (volumePercent < 0 || volumePercent > 100) {
                throw new IllegalArgumentException("volume must be between 0 and 100");
            }
            command("set volume", List.of("set_property", "volume", volumePercent), COMMAND_TIMEOUT);
            return null;
        });
    }

    public CompletionStage<Void> setMuted(boolean muted) {
        return onSerial(() -> {
            requirePlaybackState();
            command("set mute", List.of("set_property", "mute", muted), COMMAND_TIMEOUT);
            return null;
        });
    }

    public CompletionStage<Void> selectAudioTrack(int trackId) {
        return selectTrack("aid", trackId);
    }

    public CompletionStage<Void> selectSubtitle(OptionalInt trackId) {
        return onSerial(() -> {
            requirePlaybackState();
            Object value = trackId != null && trackId.isPresent() ? trackId.getAsInt() : "no";
            command("set sid", List.of("set_property", "sid", value), COMMAND_TIMEOUT);
            return null;
        });
    }

    public CompletionStage<Void> addExternalSubtitle(Path subtitleFile) {
        return onSerial(() -> {
            requirePlaybackState();
            Path normalized = validateSubtitleFile(subtitleFile);
            command("sub-add", List.of("sub-add", normalized.toString(), "select"), COMMAND_TIMEOUT);
            return null;
        });
    }

    public CompletionStage<Void> selectAudioOutput(String deviceId) {
        return onSerial(() -> {
            requirePlaybackState();
            if (deviceId == null || deviceId.isBlank()) {
                throw new IllegalArgumentException("audio device id must not be blank");
            }
            command("set audio-device", List.of("set_property", "audio-device", deviceId), COMMAND_TIMEOUT);
            return null;
        });
    }

    public CompletionStage<Void> setScaling(ScalingMode scalingMode) {
        return onSerial(() -> {
            requirePlaybackState();
            ScalingMode mode = Objects.requireNonNull(scalingMode, "scalingMode");
            switch (mode) {
                case FIT -> {
                    command("set panscan", List.of("set_property", "panscan", 0.0), COMMAND_TIMEOUT);
                    command("set video-unscaled", List.of("set_property", "video-unscaled", false), COMMAND_TIMEOUT);
                    command("set video-zoom", List.of("set_property", "video-zoom", 0.0), COMMAND_TIMEOUT);
                }
                case FILL -> {
                    command("set video-unscaled", List.of("set_property", "video-unscaled", false), COMMAND_TIMEOUT);
                    command("set video-zoom", List.of("set_property", "video-zoom", 0.0), COMMAND_TIMEOUT);
                    command("set panscan", List.of("set_property", "panscan", 1.0), COMMAND_TIMEOUT);
                }
                case ONE_TO_ONE -> {
                    command("set panscan", List.of("set_property", "panscan", 0.0), COMMAND_TIMEOUT);
                    command("set video-zoom", List.of("set_property", "video-zoom", 0.0), COMMAND_TIMEOUT);
                    command("set video-unscaled", List.of("set_property", "video-unscaled", true), COMMAND_TIMEOUT);
                }
            }
            return null;
        });
    }

    /** Stops mpv and releases its Job Object; safe to call repeatedly. */
    public CompletionStage<Void> shutdown() {
        return onSerial(() -> {
            closeProcessResources();
            return null;
        });
    }

    @Override
    public void close() {
        try {
            shutdown().toCompletableFuture().get(QUIT_TIMEOUT.plus(DESTROY_TIMEOUT).plusSeconds(2).toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            notifications.submit(new PlayerNotification.Diagnostic(
                    "PLY-002", "Не удалось корректно завершить видеоплеер.", exception.getMessage()));
        } finally {
            serial.shutdownNow();
            notifications.close();
        }
    }

    private CompletionStage<Void> setPaused(boolean paused) {
        return onSerial(() -> {
            if (paused && state == PlayerState.PAUSED || !paused && state == PlayerState.PLAYING) {
                return null;
            }
            requirePlaybackState();
            command("set pause", List.of("set_property", "pause", paused), COMMAND_TIMEOUT);
            transition(paused ? PlayerEvent.PAUSE_REQUESTED : PlayerEvent.PLAY_REQUESTED);
            return null;
        });
    }

    private CompletionStage<Void> selectTrack(String property, int trackId) {
        return onSerial(() -> {
            requirePlaybackState();
            if (trackId < 1) {
                throw new IllegalArgumentException("track id must be positive");
            }
            command("set " + property, List.of("set_property", property, trackId), COMMAND_TIMEOUT);
            return null;
        });
    }

    private void startProcess(boolean useSoftwareDecode) throws IOException {
        transition(PlayerEvent.START_REQUESTED);
        stopRequested = false;
        softwareDecode = useSoftwareDecode;
        MpvLaunchProfile profile = profileFactory.create(executable, UUID.randomUUID(), useSoftwareDecode);
        windowTitle = profile.windowTitle();
        process = processStarter.start(profile);
        try {
            containmentHandle = containment.attach(process);
        } catch (IOException exception) {
            // External output must never continue with a child process that ScreenPilot cannot reliably
            // terminate. A caller that deliberately uses ProcessContainment.disabled() still opts out
            // explicitly (for metadata/integration probes), but a failed Windows Job assignment is fatal.
            closeProcessResources();
            throw new IOException("PLY-001: Windows не позволила защитить дочерний видеоплеер от зависания.", exception);
        }
        try {
            ipc = ipcConnector.connect(profile.ipcPipe(), IPC_CONNECT_TIMEOUT);
        } catch (IOException exception) {
            closeProcessResources();
            throw exception;
        }
        MpvIpcSession connectedIpc = ipc;
        Process launchedProcess = process;
        ipcSubscriptions.add(connectedIpc.addEventListener(event -> serial.execute(() -> {
            if (ipc == connectedIpc) {
                handleIpcEvent(event);
            }
        })));
        ipcSubscriptions.add(connectedIpc.addDisconnectListener(error -> serial.execute(() -> {
            if (ipc == connectedIpc) {
                handleIpcDisconnect(error);
            }
        })));
        launchedProcess.onExit().thenRun(() -> serial.execute(() -> {
            if (process == launchedProcess) {
                handleProcessExit();
            }
        }));
        for (int index = 0; index < OBSERVED_PROPERTIES.size(); index++) {
            command("observe " + OBSERVED_PROPERTIES.get(index),
                    List.of("observe_property", index + 1, OBSERVED_PROPERTIES.get(index)), COMMAND_TIMEOUT);
        }
        optionalCommand(List.of("request_log_messages", "warn"));
        transition(PlayerEvent.STARTED);
    }

    private void handleIpcEvent(JsonNode event) {
        String eventName = event.path("event").asText("");
        switch (eventName) {
            case "file-loaded" -> onFileLoaded();
            case "property-change" -> onPropertyChanged(event);
            case "end-file" -> onEndFile(event);
            case "log-message" -> onLogMessage(event);
            default -> {
                // Events outside the declared MVP surface are intentionally non-fatal.
            }
        }
    }

    private void onFileLoaded() {
        if (state != PlayerState.LOADING || loadedFile == null) {
            return;
        }
        try {
            if (!pendingStartPosition.isZero()) {
                command("initial seek", List.of("seek", pendingStartPosition.toMillis() / 1_000.0, "absolute+exact"), COMMAND_TIMEOUT);
                lastPosition = pendingStartPosition;
            }
            MediaInfo media = readMediaInfo(loadedFile);
            transition(PlayerEvent.FILE_LOADED);
            if (pauseAfterLoad) {
                command("restore paused state", List.of("set_property", "pause", true), COMMAND_TIMEOUT);
                transition(PlayerEvent.PAUSE_REQUESTED);
                pauseAfterLoad = false;
            } else {
                // --keep-open=yes can leave mpv paused after EOF. Do not rely on its implicit
                // default here: each normal load must explicitly enter playback.
                command("start loaded file", List.of("set_property", "pause", false), COMMAND_TIMEOUT);
            }
            notifications.submit(new PlayerNotification.MediaLoaded(media));
            completePendingLoad(media);
        } catch (Exception exception) {
            fail("PLY-003", "Не удалось открыть видеофайл.", exception);
        }
    }

    private void onPropertyChanged(JsonNode event) {
        String name = event.path("name").asText("");
        JsonNode data = event.path("data");
        switch (name) {
            case "time-pos" -> {
                if (data.isNumber()) {
                    lastPosition = Duration.ofMillis(Math.max(0, Math.round(data.asDouble() * 1_000)));
                    notifications.submit(new PlayerNotification.PlaybackProgress(lastPosition, lastDuration));
                }
            }
            case "duration" -> {
                lastDuration = data.isNumber() && data.asDouble() >= 0
                        ? Optional.of(Duration.ofMillis(Math.round(data.asDouble() * 1_000)))
                        : Optional.empty();
                notifications.submit(new PlayerNotification.PlaybackProgress(lastPosition, lastDuration));
            }
            case "pause" -> syncPauseState(data.asBoolean(false));
            case "track-list" -> notifications.submit(new PlayerNotification.TracksChanged(MpvMediaMapper.tracks(data)));
            case "audio-device-list" -> {
                audioOutputs = MpvMediaMapper.audioOutputs(data);
                notifications.submit(new PlayerNotification.AudioOutputsChanged(audioOutputs, Optional.empty()));
            }
            case "audio-device" -> notifications.submit(new PlayerNotification.AudioOutputsChanged(
                    audioOutputs, text(data)));
            case "hwdec-current" -> notifications.submit(new PlayerNotification.HardwareDecoderChanged(data.asText("unknown")));
            case "eof-reached" -> {
                if (data.asBoolean(false) && state == PlayerState.PLAYING) {
                    notifications.submit(new PlayerNotification.EndOfFile());
                    transition(PlayerEvent.STOP_MEDIA_REQUESTED);
                }
            }
            default -> {
                // Other observed properties are read while loading metadata and retained for future UI use.
            }
        }
    }

    private void onEndFile(JsonNode event) {
        String reason = event.path("reason").asText("");
        if ("error".equals(reason)) {
            fail("PLY-006", "Файл повреждён или неполный.", new IOException(event.toString()));
        }
    }

    private void onLogMessage(JsonNode event) {
        String level = event.path("level").asText("");
        String text = event.path("text").asText("").toLowerCase(java.util.Locale.ROOT);
        if (("error".equals(level) || "warn".equals(level))
                && (text.contains("hwdec") || text.contains("hardware decoding"))
                && (text.contains("fail") || text.contains("error"))) {
            attemptSoftwareFallback(event.toString());
        }
    }

    private void attemptSoftwareFallback(String technicalDetail) {
        if (loadedFile == null || softwareDecode) {
            if (softwareDecode) {
                fail("PLY-005", "Видео не удалось декодировать.", new IOException(technicalDetail));
            }
            return;
        }
        if (fallbackAttempted) {
            return;
        }
        fallbackAttempted = true;
        Path fileToReload = loadedFile;
        Duration positionToRestore = lastPosition;
        boolean restorePaused = state == PlayerState.PAUSED;
        notifications.submit(new PlayerNotification.Diagnostic(
                "PLY-004", "Аппаратное декодирование недоступно — используется процессор.", technicalDetail));
        try {
            closeProcessResources(true);
            startProcess(true);
            loadedFile = fileToReload;
            pendingStartPosition = positionToRestore;
            pauseAfterLoad = restorePaused;
            transition(PlayerEvent.LOAD_REQUESTED);
            command("loadfile after hardware decode fallback", List.of("loadfile", fileToReload.toString(), "replace"), COMMAND_TIMEOUT);
            if (pendingLoad != null) {
                schedulePendingLoadTimeout(pendingLoad);
            }
        } catch (Exception exception) {
            fail("PLY-005", "Видео не удалось декодировать.", exception);
        }
    }

    private void handleIpcDisconnect(IOException exception) {
        if (!stopRequested && state != PlayerState.STOPPED && state != PlayerState.STOPPING) {
            fail("PLY-002", "Видеоплеер не отвечает.", exception);
        }
    }

    private void handleProcessExit() {
        if (!stopRequested && state != PlayerState.STOPPED && state != PlayerState.STOPPING) {
            fail("PLY-002", "Видеоплеер завершился неожиданно.", new IOException("mpv process exited"));
        }
    }

    private MediaInfo readMediaInfo(Path source) throws IOException {
        Map<String, JsonNode> properties = new LinkedHashMap<>();
        for (String property : List.of("media-title", "duration", "file-format", "video-format", "video-params", "container-fps", "track-list")) {
            properties.put(property, property(property));
        }
        return MpvMediaMapper.mediaInfo(source, properties);
    }

    private JsonNode property(String name) throws IOException {
        JsonNode response = ipc.command(List.of("get_property", name), COMMAND_TIMEOUT);
        return "success".equals(response.path("error").asText()) ? response.path("data") : MissingNode.getInstance();
    }

    private void optionalCommand(List<?> command) {
        try {
            command("optional mpv command", command, COMMAND_TIMEOUT);
        } catch (IOException ignored) {
            // Feature availability varies between mpv builds; the command is not essential for basic playback.
        }
    }

    private JsonNode command(String operation, List<?> mpvCommand, Duration timeout) throws IOException {
        if (ipc == null) {
            throw new IOException("mpv IPC is not connected");
        }
        JsonNode response = ipc.command(mpvCommand, timeout);
        if (!"success".equals(response.path("error").asText())) {
            throw new IOException("mpv command " + operation + " failed: " + response);
        }
        return response;
    }

    private void schedulePendingLoadTimeout(CompletableFuture<MediaInfo> expected) {
        long attempt = ++pendingLoadAttempt;
        serial.schedule(() -> failPendingLoadIfUnchanged(expected, attempt), LOAD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void failPendingLoadIfUnchanged(CompletableFuture<MediaInfo> expected, long expectedAttempt) {
        if (pendingLoad == expected && pendingLoadAttempt == expectedAttempt && !expected.isDone()) {
            fail("PLY-003", "Не удалось открыть видеофайл.", new TimeoutException("mpv file-loaded event timed out"));
        }
    }

    private void completePendingLoad(MediaInfo media) {
        if (pendingLoad != null) {
            pendingLoad.complete(media);
            pendingLoad = null;
            pendingLoadAttempt++;
        }
    }

    private void fail(String code, String message, Exception exception) {
        if (pendingLoad != null) {
            pendingLoad.completeExceptionally(exception);
            pendingLoad = null;
            pendingLoadAttempt++;
        }
        publishState(PlayerState.FAILED);
        notifications.submit(new PlayerNotification.Failure(code, message, exception.getMessage()));
    }

    private void closeProcessResources() {
        closeProcessResources(false);
    }

    private void closeProcessResources(boolean retainPendingLoad) {
        stopRequested = true;
        if (state != PlayerState.STOPPED && state != PlayerState.STOPPING) {
            publishState(PlayerState.STOPPING);
        }
        for (MpvIpcSession.Subscription subscription : List.copyOf(ipcSubscriptions)) {
            subscription.close();
        }
        ipcSubscriptions.clear();
        if (ipc != null) {
            try {
                command("quit", List.of("quit"), QUIT_TIMEOUT);
            } catch (IOException ignored) {
                // Continue with Java Process termination below.
            }
            try {
                ipc.close();
            } catch (Exception ignored) {
                // The process may have already closed its pipe.
            }
            ipc = null;
        }
        if (process != null) {
            waitFor(process, QUIT_TIMEOUT);
            if (process.isAlive()) {
                process.destroy();
                waitFor(process, DESTROY_TIMEOUT);
            }
            if (process.isAlive()) {
                process.destroyForcibly();
                waitFor(process, DESTROY_TIMEOUT);
            }
            process = null;
        }
        containmentHandle.close();
        containmentHandle = ProcessContainment.Handle.none();
        loadedFile = null;
        pendingStartPosition = Duration.ZERO;
        lastPosition = Duration.ZERO;
        lastDuration = Optional.empty();
        if (!retainPendingLoad) {
            pendingLoad = null;
            pendingLoadAttempt++;
        }
        pauseAfterLoad = false;
        windowTitle = null;
        publishState(PlayerState.STOPPED);
    }

    private static void waitFor(Process process, Duration timeout) {
        try {
            process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void syncPauseState(boolean paused) {
        if (paused && state == PlayerState.PLAYING) {
            transition(PlayerEvent.PAUSE_REQUESTED);
        } else if (!paused && state == PlayerState.PAUSED) {
            transition(PlayerEvent.PLAY_REQUESTED);
        }
    }

    private void transition(PlayerEvent event) {
        publishState(PlayerStateMachine.transition(state, event));
    }

    private void publishState(PlayerState newState) {
        state = newState;
        notifications.submit(new PlayerNotification.StateChanged(newState));
    }

    private void requireState(PlayerState expected) {
        if (state != expected) {
            throw new IllegalStateException("Expected player state " + expected + " but was " + state);
        }
    }

    private void requirePlaybackState() {
        if (state != PlayerState.PLAYING && state != PlayerState.PAUSED) {
            throw new IllegalStateException("Player is not ready for playback control: " + state);
        }
    }

    private static Path validateMediaFile(Path file) throws IOException {
        Path normalized = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized) || !Files.isReadable(normalized)) {
            throw new IOException("Media file is not a readable regular file: " + normalized);
        }
        return normalized;
    }

    private static Path validateSubtitleFile(Path file) throws IOException {
        Path normalized = validateMediaFile(file);
        String name = normalized.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!name.endsWith(".srt") && !name.endsWith(".ass") && !name.endsWith(".ssa")) {
            throw new IOException("Subtitle file must use .srt, .ass or .ssa: " + normalized);
        }
        return normalized;
    }

    private static Duration requireNonNegative(Duration value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    private static Optional<String> text(JsonNode value) {
        return value != null && value.isTextual() && !value.asText().isBlank()
                ? Optional.of(value.asText())
                : Optional.empty();
    }

    private <T> CompletionStage<T> onSerial(CheckedSupplier<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        serial.execute(() -> {
            try {
                result.complete(work.get());
            } catch (Exception exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
