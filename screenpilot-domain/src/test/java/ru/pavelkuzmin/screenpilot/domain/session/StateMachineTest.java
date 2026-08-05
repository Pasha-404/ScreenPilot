package ru.pavelkuzmin.screenpilot.domain.session;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateMachineTest {

    @Test
    void followsOutputLifecycleWithoutSkippingDisplayPreparation() {
        OutputSessionState state = OutputSessionState.NO_TARGET;
        state = OutputSessionStateMachine.transition(state, OutputSessionEvent.TARGET_SELECTED);
        state = OutputSessionStateMachine.transition(state, OutputSessionEvent.START_REQUESTED);
        state = OutputSessionStateMachine.transition(state, OutputSessionEvent.DISPLAY_PREPARED);
        state = OutputSessionStateMachine.transition(state, OutputSessionEvent.FILE_LOADED);
        state = OutputSessionStateMachine.transition(state, OutputSessionEvent.STOP_OUTPUT_REQUESTED);
        state = OutputSessionStateMachine.transition(state, OutputSessionEvent.RESTORE_SUCCEEDED);

        assertThat(state).isEqualTo(OutputSessionState.TARGET_READY);
    }

    @Test
    void rejectsPlaybackBeforeOutputIsPrepared() {
        assertThatThrownBy(() -> OutputSessionStateMachine.transition(
                OutputSessionState.TARGET_READY,
                OutputSessionEvent.FILE_LOADED
        )).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void retainsApplicationAfterPlayerFailureAndAllowsShutdown() {
        PlayerState state = PlayerStateMachine.transition(PlayerState.STOPPED, PlayerEvent.START_REQUESTED);
        state = PlayerStateMachine.transition(state, PlayerEvent.STARTED);
        state = PlayerStateMachine.transition(state, PlayerEvent.LOAD_REQUESTED);
        state = PlayerStateMachine.transition(state, PlayerEvent.FAILED);
        state = PlayerStateMachine.transition(state, PlayerEvent.STOP_PROCESS_REQUESTED);
        state = PlayerStateMachine.transition(state, PlayerEvent.STOPPED);

        assertThat(state).isEqualTo(PlayerState.STOPPED);
    }
}
