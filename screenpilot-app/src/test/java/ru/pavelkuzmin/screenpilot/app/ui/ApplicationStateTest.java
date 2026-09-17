package ru.pavelkuzmin.screenpilot.app.ui;

import org.junit.jupiter.api.Test;
import ru.pavelkuzmin.screenpilot.domain.display.ConnectionType;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayBounds;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayId;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayInfo;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayMode;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayTargetAddress;
import ru.pavelkuzmin.screenpilot.domain.display.GdiMappingConfidence;
import ru.pavelkuzmin.screenpilot.domain.display.RefreshRate;
import ru.pavelkuzmin.screenpilot.domain.media.MediaFingerprint;
import ru.pavelkuzmin.screenpilot.domain.media.Playlist;
import ru.pavelkuzmin.screenpilot.domain.media.PlaylistItem;
import ru.pavelkuzmin.screenpilot.domain.media.ResumeEntry;
import ru.pavelkuzmin.screenpilot.domain.recovery.RecoveryRecord;
import ru.pavelkuzmin.screenpilot.domain.session.OutputSessionState;

import java.nio.file.Path;
import java.util.List;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationStateTest {

    @Test
    void selectsOnlyAvailableExternalTargetAutomatically() {
        DisplayInfo internal = display("internal", true, true, ConnectionType.INTERNAL);
        DisplayInfo hdmi = display("hdmi", false, true, ConnectionType.HDMI);

        ApplicationState state = ApplicationState.initial().withDisplays(List.of(internal, hdmi));

        assertThat(state.selectedTarget()).contains(hdmi);
        assertThat(state.outputState()).isEqualTo(OutputSessionState.TARGET_READY);
        assertThat(state.externalTargets()).containsExactly(hdmi);
    }

    @Test
    void requiresExplicitSelectionWhenSeveralExternalTargetsExist() {
        DisplayInfo hdmi = display("hdmi", false, true, ConnectionType.HDMI);
        DisplayInfo displayPort = display("dp", false, true, ConnectionType.DISPLAY_PORT);

        ApplicationState state = ApplicationState.initial().withDisplays(List.of(hdmi, displayPort));

        assertThat(state.selectedTarget()).isEmpty();
        assertThat(state.outputState()).isEqualTo(OutputSessionState.NO_TARGET);
        assertThat(state.userMessage()).contains("Выберите");
    }

    @Test
    void isReadyOnlyWithSelectedTargetAndLocalFile() {
        DisplayInfo hdmi = display("hdmi", false, true, ConnectionType.HDMI);

        ApplicationState state = ApplicationState.initial()
                .withDisplays(List.of(hdmi))
                .withSelectedMedia(Path.of("C:/Video/movie.mkv"));

        assertThat(state.readyForOutput()).isTrue();
    }

    @Test
    void retainsAConfirmedManualModeAfterRefreshingTheDisplaySnapshot() {
        DisplayMode nativeMode = mode(1920, 1080, 60);
        DisplayMode selectedMode = mode(640, 480, 119);
        ApplicationState state = ApplicationState.initial()
                .withDisplays(List.of(display("hdmi", false, true, ConnectionType.HDMI,
                        nativeMode, List.of(nativeMode, selectedMode))))
                .withSelectedMode(selectedMode);

        DisplayMode currentModeReportedWithAnotherRationalRate = new DisplayMode(
                1920, 1080, RefreshRate.of(60_000, 1_001), false, 32, false);
        ApplicationState refreshed = state.withDisplays(List.of(display("hdmi", false, true, ConnectionType.HDMI,
                currentModeReportedWithAnotherRationalRate, List.of(currentModeReportedWithAnotherRationalRate, selectedMode))));

        assertThat(refreshed.selectedMode()).isEqualTo(selectedMode);
    }

    @Test
    void blocksOutputUntilTheUserChoosesHowToHandleSavedPosition() {
        DisplayInfo hdmi = display("hdmi", false, true, ConnectionType.HDMI);
        Path file = Path.of("C:/Video/movie.mkv").toAbsolutePath().normalize();
        Playlist playlist = Playlist.empty().addOrSelect(PlaylistItem.pending(new MediaFingerprint(
                file.toString(), 42, Instant.parse("2026-08-09T12:00:00Z")))).playlist();
        ResumeEntry offer = new ResumeEntry(playlist.selectedItem().orElseThrow().fingerprint(),
                Duration.ofMinutes(4), Duration.ofMinutes(20), Instant.now());

        ApplicationState awaitingChoice = ApplicationState.initial().withDisplays(List.of(hdmi))
                .withPlaylist(playlist, offer, "Выбран файл.");

        assertThat(awaitingChoice.readyForOutput()).isFalse();
        assertThat(awaitingChoice.resumeOffer()).isEqualTo(offer);
        assertThat(awaitingChoice.withResumeDecision(true).requestedStartPosition()).isEqualTo(Duration.ofMinutes(4));
        assertThat(awaitingChoice.withResumeDecision(false).requestedStartPosition()).isZero();
        assertThat(awaitingChoice.withResumeDecision(true).readyForOutput()).isTrue();
    }

    @Test
    void blocksOutputUntilTheUserExplicitlyResolvesAnUnfinishedDisplayRecovery() {
        DisplayInfo hdmi = display("hdmi", false, true, ConnectionType.HDMI);
        RecoveryRecord recovery = RecoveryRecord.begin(UUID.randomUUID(), Instant.parse("2026-09-17T10:00:00Z"),
                "saved-display-topology");

        ApplicationState state = ApplicationState.initial()
                .withDisplays(List.of(hdmi))
                .withSelectedMedia(Path.of("C:/Video/movie.mkv"))
                .withPendingRecovery(recovery)
                .withDisplays(List.of(hdmi));

        assertThat(state.pendingRecovery()).isEqualTo(recovery);
        assertThat(state.readyForOutput()).isFalse();
        assertThat(state.outputState()).isEqualTo(OutputSessionState.OUTPUT_ERROR);
        assertThat(state.withoutPendingRecovery("Пользователь оставил текущую конфигурацию.").readyForOutput()).isTrue();
    }

    private static DisplayInfo display(String id, boolean internal, boolean active, ConnectionType connection) {
        DisplayMode mode = mode(1920, 1080, 60);
        return display(id, internal, active, connection, mode, List.of(mode));
    }

    private static DisplayInfo display(
            String id,
            boolean internal,
            boolean active,
            ConnectionType connection,
            DisplayMode currentMode,
            List<DisplayMode> confirmedModes
    ) {
        return new DisplayInfo(
                new DisplayId(id), id, "Test", "Monitor", connection, internal, active, internal,
                true, active ? "\\\\.\\DISPLAY1" : "", GdiMappingConfidence.AUTHORITATIVE,
                new DisplayBounds(0, 0, 1_920, 1_080), currentMode, null, confirmedModes, false, "",
                new DisplayTargetAddress(1, 0, internal ? 1 : 2)
        );
    }

    private static DisplayMode mode(int width, int height, int hertz) {
        return new DisplayMode(width, height, RefreshRate.of(hertz, 1), false, 32, false);
    }
}
