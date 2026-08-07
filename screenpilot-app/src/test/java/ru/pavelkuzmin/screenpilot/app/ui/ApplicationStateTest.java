package ru.pavelkuzmin.screenpilot.app.ui;

import org.junit.jupiter.api.Test;
import ru.pavelkuzmin.screenpilot.domain.display.ConnectionType;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayBounds;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayId;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayInfo;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayTargetAddress;
import ru.pavelkuzmin.screenpilot.domain.display.GdiMappingConfidence;
import ru.pavelkuzmin.screenpilot.domain.session.OutputSessionState;

import java.nio.file.Path;
import java.util.List;

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

    private static DisplayInfo display(String id, boolean internal, boolean active, ConnectionType connection) {
        return new DisplayInfo(
                new DisplayId(id), id, "Test", "Monitor", connection, internal, active, internal,
                true, active ? "\\\\.\\DISPLAY1" : "", GdiMappingConfidence.AUTHORITATIVE,
                new DisplayBounds(0, 0, 1_920, 1_080), null, null, List.of(), false, "",
                new DisplayTargetAddress(1, 0, internal ? 1 : 2)
        );
    }
}
