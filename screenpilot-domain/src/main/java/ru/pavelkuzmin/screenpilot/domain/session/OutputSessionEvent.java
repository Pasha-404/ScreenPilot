package ru.pavelkuzmin.screenpilot.domain.session;

public enum OutputSessionEvent {
    TARGET_SELECTED,
    TARGET_LOST,
    START_REQUESTED,
    DISPLAY_PREPARED,
    FILE_LOADED,
    FILE_STOPPED,
    STOP_OUTPUT_REQUESTED,
    RESTORE_SUCCEEDED,
    RESTORE_FAILED,
    PREPARATION_FAILED,
    OUTPUT_FAILED
}
