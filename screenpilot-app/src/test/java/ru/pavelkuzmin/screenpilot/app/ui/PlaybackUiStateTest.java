package ru.pavelkuzmin.screenpilot.app.ui;

import org.junit.jupiter.api.Test;
import ru.pavelkuzmin.screenpilot.domain.media.AudioOutputDevice;
import ru.pavelkuzmin.screenpilot.domain.session.PlayerNotification;
import ru.pavelkuzmin.screenpilot.domain.session.PlayerState;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybackUiStateTest {

    @Test
    void projectsMpvNotificationsWithoutLeakingPlayerObjectsIntoTheUi() {
        PlaybackUiState state = PlaybackUiState.idle()
                .withNotification(new PlayerNotification.StateChanged(PlayerState.PLAYING))
                .withNotification(new PlayerNotification.PlaybackProgress(
                        Duration.ofSeconds(12), Optional.of(Duration.ofSeconds(90))))
                .withNotification(new PlayerNotification.AudioOutputsChanged(
                        List.of(new AudioOutputDevice("auto", "Автоматический выбор mpv")), Optional.of("auto")));

        assertThat(state.controlsAvailable()).isTrue();
        assertThat(state.position()).isEqualTo(Duration.ofSeconds(12));
        assertThat(state.duration()).contains(Duration.ofSeconds(90));
        assertThat(state.selectedAudioOutputId()).isEqualTo("auto");
    }
}
