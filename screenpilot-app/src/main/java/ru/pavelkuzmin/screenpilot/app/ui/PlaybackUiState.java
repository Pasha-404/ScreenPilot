package ru.pavelkuzmin.screenpilot.app.ui;

import ru.pavelkuzmin.screenpilot.domain.media.AudioOutputDevice;
import ru.pavelkuzmin.screenpilot.domain.media.MediaInfo;
import ru.pavelkuzmin.screenpilot.domain.media.MediaTrack;
import ru.pavelkuzmin.screenpilot.domain.session.PlayerNotification;
import ru.pavelkuzmin.screenpilot.domain.session.PlayerState;
import ru.pavelkuzmin.screenpilot.domain.settings.ScalingMode;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable projection of notifications from one active mpv adapter. */
public record PlaybackUiState(
        PlayerState playerState,
        MediaInfo mediaInfo,
        Duration position,
        Optional<Duration> duration,
        int volumePercent,
        ScalingMode scalingMode,
        List<AudioOutputDevice> audioOutputs,
        String selectedAudioOutputId,
        List<MediaTrack> tracks
) {

    public PlaybackUiState {
        playerState = playerState == null ? PlayerState.STOPPED : playerState;
        position = position == null || position.isNegative() ? Duration.ZERO : position;
        duration = duration == null ? Optional.empty() : duration.filter(value -> !value.isNegative());
        if (volumePercent < 0 || volumePercent > 100) {
            throw new IllegalArgumentException("volumePercent must be between 0 and 100");
        }
        scalingMode = scalingMode == null ? ScalingMode.FIT : scalingMode;
        audioOutputs = List.copyOf(Objects.requireNonNullElse(audioOutputs, List.of()));
        selectedAudioOutputId = selectedAudioOutputId == null || selectedAudioOutputId.isBlank()
                ? null : selectedAudioOutputId;
        tracks = List.copyOf(Objects.requireNonNullElse(tracks, List.of()));
    }

    public static PlaybackUiState idle() {
        return new PlaybackUiState(PlayerState.STOPPED, null, Duration.ZERO, Optional.empty(), 100,
                ScalingMode.FIT, List.of(), null, List.of());
    }

    public boolean controlsAvailable() {
        return playerState == PlayerState.PLAYING || playerState == PlayerState.PAUSED;
    }

    PlaybackUiState withNotification(PlayerNotification notification) {
        return switch (notification) {
            case PlayerNotification.StateChanged changed -> new PlaybackUiState(
                    changed.state(), mediaInfo, position, duration, volumePercent, scalingMode,
                    audioOutputs, selectedAudioOutputId, tracks);
            case PlayerNotification.MediaLoaded loaded -> new PlaybackUiState(
                    playerState, loaded.media(), position, duration, volumePercent, scalingMode,
                    audioOutputs, selectedAudioOutputId, loaded.media().tracks());
            case PlayerNotification.PlaybackProgress progress -> new PlaybackUiState(
                    playerState, mediaInfo, progress.position(), progress.duration(), volumePercent, scalingMode,
                    audioOutputs, selectedAudioOutputId, tracks);
            case PlayerNotification.TracksChanged changed -> new PlaybackUiState(
                    playerState, mediaInfo, position, duration, volumePercent, scalingMode,
                    audioOutputs, selectedAudioOutputId, changed.tracks());
            case PlayerNotification.AudioOutputsChanged changed -> new PlaybackUiState(
                    playerState, mediaInfo, position, duration, volumePercent, scalingMode,
                    changed.devices(), changed.selectedDeviceId().orElse(null), tracks);
            case PlayerNotification.HardwareDecoderChanged ignored -> this;
            case PlayerNotification.Diagnostic ignored -> this;
            case PlayerNotification.Failure ignored -> this;
        };
    }

    PlaybackUiState withVolumePercent(int volume) {
        return new PlaybackUiState(playerState, mediaInfo, position, duration, volume, scalingMode,
                audioOutputs, selectedAudioOutputId, tracks);
    }

    PlaybackUiState withScalingMode(ScalingMode scaling) {
        return new PlaybackUiState(playerState, mediaInfo, position, duration, volumePercent, scaling,
                audioOutputs, selectedAudioOutputId, tracks);
    }
}
