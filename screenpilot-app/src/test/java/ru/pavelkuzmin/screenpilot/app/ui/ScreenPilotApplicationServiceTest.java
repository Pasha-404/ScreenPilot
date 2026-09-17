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
import ru.pavelkuzmin.screenpilot.domain.session.OutputSessionState;
import ru.pavelkuzmin.screenpilot.platform.windows.WindowsDisplayMutator;
import ru.pavelkuzmin.screenpilot.platform.windows.WindowsMpvWindowLocator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
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

    @Test
    void startingFromTheBeginningClearsThePersistedResumeEntry() throws Exception {
        Path video = temporaryDirectory.resolve("movie.mkv");
        Files.write(video, new byte[]{1, 2, 3});
        CopyOnWriteArrayList<MediaFingerprint> completed = new CopyOnWriteArrayList<>();
        UiStateStore store = new UiStateStore();
        ExecutorService serial = Executors.newSingleThreadExecutor();
        MediaProbe probe = inertProbe();
        ResumeRepository resume = resumableRepository(completed);
        try (ScreenPilotApplicationService service = new ScreenPilotApplicationService(
                java.util.List::of,
                store,
                serial,
                new WindowsDisplayMutator(),
                new EmptyRecoveryJournal(),
                new WindowsMpvWindowLocator(),
                inertSettings(),
                resume,
                probe
        )) {
            service.addMediaFiles(java.util.List.of(video));
            await(() -> store.current().resumeOffer() != null);

            service.resolveResume(false);
            await(() -> store.current().resumeOffer() == null);

            assertThat(completed).containsExactly(store.current().selectedPlaylistItem().orElseThrow().fingerprint());
            assertThat(store.current().requestedStartPosition()).isZero();
        }
    }

    @Test
    void rejectsPlaylistChangesWhileTheOutputStateIsActive() throws Exception {
        Path first = temporaryDirectory.resolve("first.mkv");
        Path second = temporaryDirectory.resolve("second.mkv");
        Files.write(first, new byte[]{1, 2, 3});
        Files.write(second, new byte[]{4, 5, 6});
        UiStateStore store = new UiStateStore();
        ExecutorService serial = Executors.newSingleThreadExecutor();
        try (ScreenPilotApplicationService service = new ScreenPilotApplicationService(
                java.util.List::of,
                store,
                serial,
                new WindowsDisplayMutator(),
                new EmptyRecoveryJournal(),
                new WindowsMpvWindowLocator(),
                inertSettings(),
                resumableRepository(new CopyOnWriteArrayList<>()),
                inertProbe()
        )) {
            service.addMediaFiles(java.util.List.of(first));
            await(() -> store.current().playlist().items().size() == 1);
            store.publish(store.current().withOutputState(OutputSessionState.OUTPUT_ACTIVE, "Видео воспроизводится."));

            service.addMediaFiles(java.util.List.of(second));
            await(() -> store.current().userMessage().contains("нельзя менять плейлист"));

            assertThat(store.current().playlist().items()).hasSize(1);
            assertThat(store.current().selectedPlaylistItem().orElseThrow().source())
                    .isEqualTo(first.toAbsolutePath().normalize());
        }
    }

    @Test
    void ignoresAResumeChoiceFromAnOlderPromptAfterTheSelectionChanges() throws Exception {
        Path first = temporaryDirectory.resolve("first.mkv");
        Path second = temporaryDirectory.resolve("second.mkv");
        Files.write(first, new byte[]{1, 2, 3});
        Files.write(second, new byte[]{4, 5, 6});
        CopyOnWriteArrayList<MediaFingerprint> completed = new CopyOnWriteArrayList<>();
        UiStateStore store = new UiStateStore();
        ExecutorService serial = Executors.newSingleThreadExecutor();
        try (ScreenPilotApplicationService service = new ScreenPilotApplicationService(
                java.util.List::of,
                store,
                serial,
                new WindowsDisplayMutator(),
                new EmptyRecoveryJournal(),
                new WindowsMpvWindowLocator(),
                inertSettings(),
                resumableRepository(completed),
                inertProbe()
        )) {
            service.addMediaFiles(java.util.List.of(first));
            await(() -> store.current().resumeOffer() != null);
            ResumeEntry firstOffer = store.current().resumeOffer();

            service.addMediaFiles(java.util.List.of(second));
            await(() -> store.current().selectedPlaylistItem().orElseThrow().source()
                    .equals(second.toAbsolutePath().normalize()));
            ResumeEntry secondOffer = store.current().resumeOffer();

            service.resolveResume(firstOffer, false);
            service.selectPlaylistItem(0);
            await(() -> store.current().playlist().selectedIndex() == 0);

            assertThat(completed).isEmpty();
            assertThat(secondOffer.fingerprint()).isNotEqualTo(firstOffer.fingerprint());
        }
    }

    @Test
    void surfacesAnUnfinishedRecoveryAtStartupAndBlocksNewOutputUntilTheUserDecides() throws Exception {
        RecoveryRecord record = RecoveryRecord.begin(UUID.randomUUID(), Instant.parse("2026-09-17T10:00:00Z"),
                "saved-display-topology");
        UiStateStore store = new UiStateStore();
        ExecutorService serial = Executors.newSingleThreadExecutor();
        RecoveryJournal journal = new RecoveryJournal() {
            @Override
            public Optional<RecoveryRecord> findUnfinished() {
                return Optional.of(record);
            }

            @Override
            public void begin(RecoveryRecord ignored) {
            }

            @Override
            public void markRestored(UUID sessionId) {
            }

            @Override
            public void markLeftAsIs(UUID sessionId) {
            }
        };
        try (ScreenPilotApplicationService service = new ScreenPilotApplicationService(
                java.util.List::of, store, serial, new WindowsDisplayMutator(), journal,
                new WindowsMpvWindowLocator(), inertSettings(), resumableRepository(new CopyOnWriteArrayList<>()), inertProbe())) {
            service.start();
            await(() -> store.current().pendingRecovery() != null);

            assertThat(store.current().pendingRecovery()).isEqualTo(record);
            assertThat(store.current().readyForOutput()).isFalse();
            assertThat(store.current().outputState()).isEqualTo(OutputSessionState.OUTPUT_ERROR);
        }
    }

    private static MediaProbe inertProbe() {
        return new MediaProbe() {
            @Override
            public java.util.concurrent.CompletionStage<MediaInfo> probe(Path file) {
                return new CompletableFuture<>();
            }

            @Override
            public void cancelAll() {
            }

            @Override
            public void close() {
            }
        };
    }

    private static SettingsRepository inertSettings() {
        return new SettingsRepository() {
            @Override
            public AppSettings load() {
                return AppSettings.defaults();
            }

            @Override
            public void save(AppSettings value) {
            }
        };
    }

    private static ResumeRepository resumableRepository(CopyOnWriteArrayList<MediaFingerprint> completed) {
        return new ResumeRepository() {
            @Override
            public Optional<ResumeEntry> find(MediaFingerprint fingerprint) {
                return completed.contains(fingerprint)
                        ? Optional.empty()
                        : Optional.of(new ResumeEntry(fingerprint, Duration.ofMinutes(3), Duration.ofMinutes(20), Instant.now()));
            }

            @Override
            public void save(ResumeEntry entry) {
            }

            @Override
            public void markCompleted(MediaFingerprint fingerprint) {
                completed.add(fingerprint);
            }
        };
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
