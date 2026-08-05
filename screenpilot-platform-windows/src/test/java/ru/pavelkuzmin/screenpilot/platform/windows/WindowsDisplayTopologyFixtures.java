package ru.pavelkuzmin.screenpilot.platform.windows;

import ru.pavelkuzmin.screenpilot.domain.display.ConnectionType;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayBounds;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayId;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayInfo;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayMode;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayTargetAddress;
import ru.pavelkuzmin.screenpilot.domain.display.GdiMappingConfidence;
import ru.pavelkuzmin.screenpilot.domain.display.RefreshRate;

import java.util.List;

/** Recorded-shape fake topologies for polling tests; they never call Windows APIs. */
final class WindowsDisplayTopologyFixtures {

    private WindowsDisplayTopologyFixtures() {
    }

    static List<DisplayInfo> internalAndHdmi() {
        return List.of(
                display("internal-panel", "Internal panel", ConnectionType.INTERNAL, true, true, true, 256, 0),
                display("hdmi-monitor", "HDMI monitor", ConnectionType.HDMI, false, true, false, 257, 1920)
        );
    }

    static List<DisplayInfo> internalOnly() {
        return List.of(display("internal-panel", "Internal panel", ConnectionType.INTERNAL, true, true, true, 256, 0));
    }

    static List<DisplayInfo> clonedInternalAndHdmi() {
        DisplayInfo internal = display("internal-panel", "Internal panel", ConnectionType.INTERNAL, true, true, true, 256, 0);
        DisplayMode mode = internal.currentMode();
        DisplayInfo clone = new DisplayInfo(
                new DisplayId("hdmi-monitor"),
                "HDMI monitor",
                "Fixture",
                "Fixture model",
                ConnectionType.HDMI,
                false,
                true,
                true,
                true,
                "\\\\.\\DISPLAY1",
                GdiMappingConfidence.AUTHORITATIVE,
                new DisplayBounds(0, 0, 1920, 1080),
                mode,
                mode,
                List.of(mode),
                false,
                "",
                new DisplayTargetAddress(42628, 0, 257)
        );
        return List.of(internal, clone);
    }

    static List<DisplayInfo> internalAndInactiveHdmi() {
        DisplayInfo internal = display("internal-panel", "Internal panel", ConnectionType.INTERNAL, true, true, true, 256, 0);
        DisplayInfo inactive = new DisplayInfo(
                new DisplayId("hdmi-monitor"),
                "HDMI monitor",
                "Fixture",
                "Fixture model",
                ConnectionType.HDMI,
                false,
                false,
                false,
                true,
                "",
                GdiMappingConfidence.UNAVAILABLE,
                null,
                null,
                null,
                List.of(),
                false,
                "",
                new DisplayTargetAddress(42628, 0, 257)
        );
        return List.of(internal, inactive);
    }

    private static DisplayInfo display(
            String id,
            String name,
            ConnectionType connection,
            boolean internal,
            boolean active,
            boolean primary,
            int targetId,
            int x
    ) {
        DisplayMode mode = new DisplayMode(1920, 1080, RefreshRate.of(60, 1), false, 32, true);
        return new DisplayInfo(
                new DisplayId(id),
                name,
                "Fixture",
                "Fixture model",
                connection,
                internal,
                active,
                primary,
                active,
                "\\\\.\\DISPLAY" + (targetId - 255),
                GdiMappingConfidence.AUTHORITATIVE,
                new DisplayBounds(x, 0, 1920, 1080),
                mode,
                mode,
                List.of(mode),
                false,
                "",
                new DisplayTargetAddress(42628, 0, targetId)
        );
    }
}
