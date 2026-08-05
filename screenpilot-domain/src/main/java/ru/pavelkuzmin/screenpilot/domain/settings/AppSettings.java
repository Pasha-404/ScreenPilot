package ru.pavelkuzmin.screenpilot.domain.settings;

import ru.pavelkuzmin.screenpilot.domain.display.DisplayMode;

/** Serializable application preferences, independent from JavaFX and the Windows registry. */
public record AppSettings(
        int schemaVersion,
        String lastFolder,
        String lastTargetId,
        boolean automaticModeSelection,
        DisplayMode manualDisplayMode,
        ScalingMode scalingMode,
        String audioDeviceId,
        int volumePercent,
        boolean muted,
        boolean prioritizeSmoothness,
        int windowWidth,
        int windowHeight,
        int windowX,
        int windowY,
        String playerProfile
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public AppSettings {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        if (volumePercent < 0 || volumePercent > 100) {
            throw new IllegalArgumentException("volumePercent must be between 0 and 100");
        }
        scalingMode = scalingMode == null ? ScalingMode.FIT : scalingMode;
        if (windowWidth < 0 || windowHeight < 0) {
            throw new IllegalArgumentException("Window dimensions must not be negative");
        }
    }

    public static AppSettings defaults() {
        return new AppSettings(
                CURRENT_SCHEMA_VERSION,
                null,
                null,
                true,
                null,
                ScalingMode.FIT,
                null,
                100,
                false,
                false,
                0,
                0,
                0,
                0,
                null
        );
    }
}
