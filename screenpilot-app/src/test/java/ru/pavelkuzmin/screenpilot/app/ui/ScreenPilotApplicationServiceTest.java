package ru.pavelkuzmin.screenpilot.app.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.pavelkuzmin.screenpilot.domain.media.MediaFingerprint;
import ru.pavelkuzmin.screenpilot.domain.media.MediaInfo;
import ru.pavelkuzmin.screenpilot.domain.media.ResumeEntry;
import ru.pavelkuzmin.screenpilot.domain.port.MediaProbe;
import ru.pavelkuzmin.screenpilot.domain.port.RecoveryJournal;
import ru.pavelkuzmin.screenpilot.domain.port.ResumeRepository;
import ru.pavelkuzmin.screenpilot.domain.port.SettingsRepository;
import ru.pavelkuzmin.screenpilot.domain.recovery.RecoveryRecord;
import ru.pavelkuzmin.screenpilot.domain.settings.AppSettings;
import ru.pavelkuzmin.screenpilot.platform.windows.WindowsDisplayMutator;
import ru.pavelkuzmin.screenpilot.platform.windows.WindowsMpvWindowLocator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ScreenPilotApplicationServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void turnsModeVerificationDiagnosticsIntoTheDocumentedUserMessage() {
        String message = ScreenPilotApplicationService.startFailureMessage(new IOException(
                "Windows did not report the requested display state; modeMatch=false"));

        assertThat(message).startsWith("DSP-005:");
        assertThat(message).doesNotContain("modeMatch");
    }

    @Test
    void remembersTheFolderAndBlocksPlaybackUntilResumeIsResolved() throws Exception {
        Path video = temporaryDirectory.resolve("movie.mkv");
        Files.write(video, new byte[]{1, 2, 3});
        AtomicReference<AppSettings> savedSettings = new AtomicReference<>();
        AtomicReference<Path> probed = new AtomicReference<>();
        UiStateStore store = new UiStateStore();
        ExecutorService serial = Executors.newSingleThreadExecutor();
        MediaProbe probe = new MediaProbe() {
            @Override
            public java.util.concurrent.CompletionStage<MediaInfo> probe(Path file) {
                probed.set(file);
                return new CompletableFuture<>();
            }

            @Override
            public void cancelAll() {
            }

            @Override
            public void close() {
            }
        };
        SettingsRepository settings = new SettingsRepository() {
            @Override
            public AppSettings load() {
                return AppSettings.defaults();
            }

            @Override
            public void save(AppSettings value) {
                savedSettings.set(value);
            }
        };
        ResumeRepository resume = new ResumeRepository() {
            @Override
            public Optional<ResumeEntry> find(MediaFingerprint fingerprint) {
                return Optional.of(new ResumeEntry(fingerprint, Duration.ofMinutes(3), Duration.ofMinutes(20), Instant.now()));
            }

            @Override
            public void save(ResumeEntry entry) {
            }

            @Override
            public void markCompleted(MediaFingerprint fingerprint) {
            }
        };
        try (ScreenPilotApplicationService service = new ScreenPilotApplicationService(
                java.util.List::of,
                store,
                serial,
                new WindowsDisplayMutator(),
                new EmptyRecoveryJournal(),
                new WindowsMpvWindowLocator(),
                settings,
                resume,
                probe
        )) {
            service.addMediaFiles(java.util.List.of(video));
            await(() -> store.current().playlist().items().size() == 1);

            assertThat(store.current().lastMediaFolder()).isEqualTo(temporaryDirectory);
            assertThat(savedSettings.get().lastFolder()).isEqualTo(temporaryDirectory.toString());
            assertThat(store.current().resumeOffer()).isNotNull();
            assertThat(store.current().readyForOutput()).isFalse();
            assertThat(probed.get()).isEqualTo(video.toAbsolutePath().normalize());
        }
    }

    private static void await(Check check) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (!check.matches() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(check.matches()).isTrue();
    }

    @FunctionalInterface
    private interface Check {
        boolean matches();
    }

    private static final class EmptyRecoveryJournal implements RecoveryJournal {
        @Override
        public Optional<RecoveryRecord> findUnfinished() {
            return Optional.empty();
        }

        @Override
        public void begin(RecoveryRecord record) {
        }

        @Override
        public void markRestored(UUID sessionId) {
        }

        @Override
        public void markLeftAsIs(UUID sessionId) {
        }
    }
}
