package ru.pavelkuzmin.screenpilot.domain.session;

import ru.pavelkuzmin.screenpilot.domain.media.AudioOutputDevice;
import ru.pavelkuzmin.screenpilot.domain.media.MediaInfo;
import ru.pavelkuzmin.screenpilot.domain.media.MediaTrack;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Typed, UI-independent events produced by a player adapter. */
public sealed interface PlayerNotification permits PlayerNotification.StateChanged,
        PlayerNotification.MediaLoaded, PlayerNotification.PlaybackProgress,
        PlayerNotification.TracksChanged, PlayerNotification.AudioOutputsChanged,
        PlayerNotification.HardwareDecoderChanged, PlayerNotification.Diagnostic,
        PlayerNotification.Failure {

    record StateChanged(PlayerState state) implements PlayerNotification {
        public StateChanged {
            state = Objects.requireNonNull(state, "state");
        }
    }

    record MediaLoaded(MediaInfo media) implements PlayerNotification {
        public MediaLoaded {
            media = Objects.requireNonNull(media, "media");
        }
    }

    record PlaybackProgress(Duration position, Optional<Duration> duration) implements PlayerNotification {
        public PlaybackProgress {
            position = nonNegative(position, "position");
            duration = duration == null ? Optional.empty() : duration.map(value -> nonNegative(value, "duration"));
        }
    }

    record TracksChanged(List<MediaTrack> tracks) implements PlayerNotification {
        public TracksChanged {
            tracks = List.copyOf(Objects.requireNonNullElse(tracks, List.of()));
        }
    }

    record AudioOutputsChanged(List<AudioOutputDevice> devices, Optional<String> selectedDeviceId) implements PlayerNotification {
        public AudioOutputsChanged {
            devices = List.copyOf(Objects.requireNonNullElse(devices, List.of()));
            selectedDeviceId = selectedDeviceId == null ? Optional.empty() : selectedDeviceId.filter(value -> !value.isBlank());
        }
    }

    record HardwareDecoderChanged(String decoder) implements PlayerNotification {
        public HardwareDecoderChanged {
            decoder = decoder == null || decoder.isBlank() ? "unknown" : decoder.trim();
        }
    }

    record Diagnostic(String code, String message, String technicalDetail) implements PlayerNotification {
        public Diagnostic {
            code = requireText(code, "code");
            message = requireText(message, "message");
            technicalDetail = technicalDetail == null ? "" : technicalDetail;
        }
    }

    record Failure(String code, String message, String technicalDetail) implements PlayerNotification {
        public Failure {
            code = requireText(code, "code");
            message = requireText(message, "message");
            technicalDetail = technicalDetail == null ? "" : technicalDetail;
        }
    }

    private static Duration nonNegative(Duration value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
