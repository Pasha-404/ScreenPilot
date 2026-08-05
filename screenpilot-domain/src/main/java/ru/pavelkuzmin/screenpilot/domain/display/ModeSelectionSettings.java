package ru.pavelkuzmin.screenpilot.domain.display;

/** User preference influencing only safe, confirmed display modes. */
public record ModeSelectionSettings(boolean prioritizeSmoothness) {
    public static final ModeSelectionSettings DEFAULT = new ModeSelectionSettings(false);
}
