package ru.pavelkuzmin.screenpilot.domain.session;

import java.util.Objects;

/** Validates the output lifecycle before platform code mutates a display. */
public final class OutputSessionStateMachine {

    private OutputSessionStateMachine() {
    }

    public static OutputSessionState transition(OutputSessionState state, OutputSessionEvent event) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(event, "event");
        return switch (state) {
            case NO_TARGET -> switch (event) {
                case TARGET_SELECTED -> OutputSessionState.TARGET_READY;
                default -> invalid(state, event);
            };
            case TARGET_READY -> switch (event) {
                case TARGET_LOST -> OutputSessionState.NO_TARGET;
                case START_REQUESTED -> OutputSessionState.PREPARING_DISPLAY;
                default -> invalid(state, event);
            };
            case PREPARING_DISPLAY -> switch (event) {
                case DISPLAY_PREPARED -> OutputSessionState.OUTPUT_IDLE;
                case PREPARATION_FAILED, TARGET_LOST -> OutputSessionState.OUTPUT_ERROR;
                default -> invalid(state, event);
            };
            case OUTPUT_IDLE -> switch (event) {
                case FILE_LOADED -> OutputSessionState.OUTPUT_ACTIVE;
                case STOP_OUTPUT_REQUESTED -> OutputSessionState.RESTORING_DISPLAY;
                case TARGET_LOST -> OutputSessionState.OUTPUT_ERROR;
                default -> invalid(state, event);
            };
            case OUTPUT_ACTIVE -> switch (event) {
                case STOP_OUTPUT_REQUESTED -> OutputSessionState.RESTORING_DISPLAY;
                case TARGET_LOST -> OutputSessionState.OUTPUT_ERROR;
                default -> invalid(state, event);
            };
            case RESTORING_DISPLAY -> switch (event) {
                case RESTORE_SUCCEEDED -> OutputSessionState.TARGET_READY;
                case RESTORE_FAILED -> OutputSessionState.OUTPUT_ERROR;
                default -> invalid(state, event);
            };
            case OUTPUT_ERROR -> switch (event) {
                case TARGET_SELECTED -> OutputSessionState.TARGET_READY;
                case TARGET_LOST -> OutputSessionState.NO_TARGET;
                default -> invalid(state, event);
            };
        };
    }

    private static OutputSessionState invalid(OutputSessionState state, OutputSessionEvent event) {
        throw new IllegalStateException("Cannot apply " + event + " while output session is " + state);
    }
}
