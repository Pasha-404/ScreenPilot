package ru.pavelkuzmin.screenpilot.platform.windows;

import org.junit.jupiter.api.Test;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayInfo;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayTopologyChanged;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WindowsDisplayPollerTest {

    @Test
    void publishesOnlyInitialAndChangedTopologiesFromFakeFixtures() {
        Deque<String> fingerprints = new ArrayDeque<>(List.of("connected", "connected", "unplugged"));
        List<List<DisplayInfo>> snapshots = List.of(
                WindowsDisplayTopologyFixtures.internalAndHdmi(),
                WindowsDisplayTopologyFixtures.internalOnly()
        );
        AtomicInteger snapshotReads = new AtomicInteger();
        RecordingListener listener = new RecordingListener();
        WindowsDisplayPoller poller = new WindowsDisplayPoller(
                () -> fingerprints.isEmpty() ? "unplugged" : fingerprints.removeFirst(),
                () -> snapshots.get(snapshotReads.getAndIncrement()),
                listener,
                Duration.ofSeconds(1)
        );

        try {
            poller.pollOnce();
            poller.pollOnce();
            poller.pollOnce();
        } finally {
            poller.close();
        }

        assertThat(listener.events).extracting(DisplayTopologyChanged::fingerprint)
                .containsExactly("connected", "unplugged");
        assertThat(listener.events.get(0).displays()).hasSize(2);
        assertThat(listener.events.get(1).displays()).hasSize(1);
        assertThat(snapshotReads).hasValue(2);
        assertThat(listener.failures).isEmpty();
    }

    @Test
    void reportsDiscoveryFailureWithoutPublishingAStaleTopology() {
        RecordingListener listener = new RecordingListener();
        WindowsDisplayPoller poller = new WindowsDisplayPoller(
                () -> {
                    throw new IOException("fixture read failure");
                },
                WindowsDisplayTopologyFixtures::internalAndHdmi,
                listener,
                Duration.ofSeconds(1)
        );

        try {
            poller.pollOnce();
        } finally {
            poller.close();
        }

        assertThat(listener.events).isEmpty();
        assertThat(listener.failures).hasSize(1);
        assertThat(listener.failures.getFirst().getMessage()).isEqualTo("fixture read failure");
    }

    private static final class RecordingListener implements WindowsDisplayPoller.Listener {
        private final List<DisplayTopologyChanged> events = new ArrayList<>();
        private final List<IOException> failures = new ArrayList<>();

        @Override
        public void onTopologyChanged(DisplayTopologyChanged event) {
            events.add(event);
        }

        @Override
        public void onPollingFailure(IOException exception) {
            failures.add(exception);
        }
    }
}
