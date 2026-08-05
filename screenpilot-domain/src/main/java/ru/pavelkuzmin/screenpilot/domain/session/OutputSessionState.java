package ru.pavelkuzmin.screenpilot.domain.session;

public enum OutputSessionState {
    NO_TARGET,
    TARGET_READY,
    PREPARING_DISPLAY,
    OUTPUT_IDLE,
    OUTPUT_ACTIVE,
    RESTORING_DISPLAY,
    OUTPUT_ERROR
}
