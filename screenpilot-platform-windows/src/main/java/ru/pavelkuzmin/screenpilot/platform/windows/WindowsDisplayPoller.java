package ru.pavelkuzmin.screenpilot.platform.windows;

import ru.pavelkuzmin.screenpilot.domain.display.DisplayInfo;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayTopologyChanged;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls the lightweight topology fingerprint every second. Full display and mode discovery occurs
 * only on the first poll and when that fingerprint changes.
 */
public final class WindowsDisplayPoller implements AutoCloseable {

    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(1);

    private final TopologyFingerprintReader fingerprintReader;
    private final DisplaySnapshotReader snapshotReader;
    private final Listener listener;
    private final ScheduledExecutorService executor;
    private final Duration interval;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private String lastFingerprint;

    public WindowsDisplayPoller(WindowsDisplayDiscovery discovery, Listener listener) {
        this(discovery::currentTopologyFingerprint, discovery::discoverDisplays, listener, DEFAULT_INTERVAL);
    }

    WindowsDisplayPoller(
            TopologyFingerprintReader fingerprintReader,
            DisplaySnapshotReader snapshotReader,
            Listener listener,
            Duration interval
    ) {
        this.fingerprintReader = Objects.requireNonNull(fingerprintReader, "fingerprintReader");
        this.snapshotReader = Objects.requireNonNull(snapshotReader, "snapshotReader");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "display-poller");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Starts background polling. Calling this method more than once has no effect. */
    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("display poller is closed");
        }
        if (started.compareAndSet(false, true)) {
            executor.scheduleWithFixedDelay(this::pollOnce, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    void pollOnce() {
        if (closed.get()) {
            return;
        }
        try {
            String fingerprint = fingerprintReader.read();
            synchronized (this) {
                if (fingerprint.equals(lastFingerprint)) {
                    return;
                }
                List<DisplayInfo> displays = snapshotReader.read();
                lastFingerprint = fingerprint;
                listener.onTopologyChanged(new DisplayTopologyChanged(fingerprint, displays));
            }
        } catch (IOException exception) {
            listener.onPollingFailure(exception);
        } catch (RuntimeException exception) {
            listener.onPollingFailure(new IOException("Unexpected display polling failure", exception));
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
        }
    }

    public interface Listener {
        void onTopologyChanged(DisplayTopologyChanged event);

        void onPollingFailure(IOException exception);
    }

    @FunctionalInterface
    interface TopologyFingerprintReader {
        String read() throws IOException;
    }

    @FunctionalInterface
    interface DisplaySnapshotReader {
        List<DisplayInfo> read() throws IOException;
    }
}
