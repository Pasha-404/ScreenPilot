package ru.pavelkuzmin.screenpilot.domain.session;

import java.util.Objects;

/** Pure player state transition table; adapter failures never crash the application state. */
public final class PlayerStateMachine {

    private PlayerStateMachine() {
    }

    public static PlayerState transition(PlayerState state, PlayerEvent event) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(event, "event");
        if (event == PlayerEvent.FAILED) {
            return PlayerState.FAILED;
        }
        return switch (state) {
            case STOPPED -> event == PlayerEvent.START_REQUESTED ? PlayerState.STARTING : invalid(state, event);
            case STARTING -> event == PlayerEvent.STARTED ? PlayerState.IDLE : invalid(state, event);
            case IDLE -> switch (event) {
                case LOAD_REQUESTED -> PlayerState.LOADING;
                case STOP_PROCESS_REQUESTED -> PlayerState.STOPPING;
                default -> invalid(state, event);
            };
            case LOADING -> switch (event) {
                case FILE_LOADED -> PlayerState.PLAYING;
                case STOP_PROCESS_REQUESTED -> PlayerState.STOPPING;
                default -> invalid(state, event);
            };
            case PLAYING -> switch (event) {
                case PAUSE_REQUESTED -> PlayerState.PAUSED;
                case STOP_MEDIA_REQUESTED -> PlayerState.IDLE;
                case STOP_PROCESS_REQUESTED -> PlayerState.STOPPING;
                default -> invalid(state, event);
            };
            case PAUSED -> switch (event) {
                case PLAY_REQUESTED -> PlayerState.PLAYING;
                case STOP_MEDIA_REQUESTED -> PlayerState.IDLE;
                case STOP_PROCESS_REQUESTED -> PlayerState.STOPPING;
                default -> invalid(state, event);
            };
            case FAILED -> event == PlayerEvent.STOP_PROCESS_REQUESTED ? PlayerState.STOPPING : invalid(state, event);
            case STOPPING -> event == PlayerEvent.STOPPED ? PlayerState.STOPPED : invalid(state, event);
        };
    }

    private static PlayerState invalid(PlayerState state, PlayerEvent event) {
        throw new IllegalStateException("Cannot apply " + event + " while player is " + state);
    }
}
