package ru.pavelkuzmin.screenpilot.player.mpv;

import ru.pavelkuzmin.screenpilot.domain.media.MediaInfo;
import ru.pavelkuzmin.screenpilot.domain.port.MediaProbe;
import ru.pavelkuzmin.screenpilot.domain.port.ProcessContainment;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One-at-a-time mpv metadata reader. It uses a separate headless process, deliberately has no
 * video/audio output, and is cancelled as soon as foreground playback is requested.
 */
public final class MpvMediaProbe implements MediaProbe {

    private static final Duration START_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

    private final Path executable;
    private final ProcessContainment containment;
    private final ExecutorService queue;
    private final AtomicReference<MpvPlayerAdapter> active = new AtomicReference<>();
    private final AtomicLong generation = new AtomicLong();

    public MpvMediaProbe(Path executable) {
        this(executable, ProcessContainment.disabled());
    }

    /** The production composition root supplies the same child-process containment policy as playback. */
    public MpvMediaProbe(Path executable, ProcessContainment containment) {
        this.executable = Objects.requireNonNull(executable, "executable").toAbsolutePath().normalize();
        this.containment = Objects.requireNonNull(containment, "containment");
        this.queue = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mpv-metadata-probe");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public CompletionStage<MediaInfo> probe(Path file) {
        Path source = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        long requestGeneration = generation.get();
        CompletableFuture<MediaInfo> result = new CompletableFuture<>();
        queue.execute(() -> probeOnQueue(source, requestGeneration, result));
        return result;
    }

    @Override
    public void cancelAll() {
        generation.incrementAndGet();
        MpvPlayerAdapter player = active.getAndSet(null);
        if (player != null) {
            try {
                // Do not wait here: the foreground output request owns its own serial executor and
                // must not be delayed by the probe's IPC timeout. The probe thread closes it later.
                player.shutdown();
            } catch (Exception ignored) {
                // Probe failures never affect the output player or the playlist row.
            }
        }
    }

    @Override
    public void close() {
        cancelAll();
        queue.shutdownNow();
    }

    private void probeOnQueue(Path source, long requestGeneration, CompletableFuture<MediaInfo> result) {
        if (requestGeneration != generation.get()) {
            result.cancel(false);
            return;
        }
        MpvPlayerAdapter player = new MpvPlayerAdapter(
                executable,
                new MpvProcessLauncher(),
                containment,
                MpvIpcClient::connect,
                (playerExecutable, sessionId, softwareDecode) -> MpvLaunchProfile.forMetadataProbe(playerExecutable, sessionId)
        );
        active.set(player);
        try {
            player.start().toCompletableFuture().get(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (requestGeneration != generation.get()) {
                result.cancel(false);
                return;
            }
            MediaInfo media = player.load(source, Duration.ZERO).toCompletableFuture()
                    .get(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (requestGeneration == generation.get()) {
                result.complete(media);
            } else {
                result.cancel(false);
            }
        } catch (Exception exception) {
            if (requestGeneration == generation.get()) {
                result.completeExceptionally(exception);
            } else {
                result.cancel(false);
            }
        } finally {
            active.compareAndSet(player, null);
            player.close();
        }
    }
}
