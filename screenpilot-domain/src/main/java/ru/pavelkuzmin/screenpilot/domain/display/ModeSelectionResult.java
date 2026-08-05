package ru.pavelkuzmin.screenpilot.domain.display;

import java.util.Objects;

public record ModeSelectionResult(
        DisplayMode mode,
        ModeSelectionConfidence confidence,
        ModeSelectionReason reason,
        boolean requiresModeChange
) {
    public ModeSelectionResult {
        mode = Objects.requireNonNull(mode, "mode");
        confidence = Objects.requireNonNull(confidence, "confidence");
        reason = Objects.requireNonNull(reason, "reason");
    }
}
