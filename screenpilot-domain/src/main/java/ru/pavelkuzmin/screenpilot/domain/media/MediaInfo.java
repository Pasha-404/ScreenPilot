package ru.pavelkuzmin.screenpilot.domain.media;

import ru.pavelkuzmin.screenpilot.domain.display.RefreshRate;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Immutable metadata snapshot received from mpv after a file has loaded. */
public record MediaInfo(
        Path source,
        String displayName,
        Optional<String> container,
        Optional<String> videoCodec,
        Optional<String> videoProfile,
        OptionalInt width,
        OptionalInt height,
        Optional<RefreshRate> framesPerSecond,
        boolean interlaced,
        OptionalInt bitDepth,
        Optional<String> colorSpace,
        Optional<String> transferCharacteristics,
        boolean highDynamicRange,
        List<MediaTrack> tracks,
        OptionalInt selectedAudioTrackId,
        OptionalInt selectedSubtitleTrackId
) {
    public MediaInfo {
        source = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        displayName = displayName == null || displayName.isBlank()
                ? source.getFileName().toString()
                : displayName.trim();
        container = safeOptional(container);
        videoCodec = safeOptional(videoCodec);
        videoProfile = safeOptional(videoProfile);
        width = nonNegative(width, "width");
        height = nonNegative(height, "height");
        framesPerSecond = framesPerSecond == null ? Optional.empty() : framesPerSecond;
        bitDepth = nonNegative(bitDepth, "bitDepth");
        colorSpace = safeOptional(colorSpace);
        transferCharacteristics = safeOptional(transferCharacteristics);
        tracks = List.copyOf(Objects.requireNonNullElse(tracks, List.of()));
        selectedAudioTrackId = selectedAudioTrackId == null ? OptionalInt.empty() : selectedAudioTrackId;
        selectedSubtitleTrackId = selectedSubtitleTrackId == null ? OptionalInt.empty() : selectedSubtitleTrackId;
    }

    private static Optional<String> safeOptional(Optional<String> value) {
        if (value == null || value.isEmpty() || value.get().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.get().trim());
    }

    private static OptionalInt nonNegative(OptionalInt value, String name) {
        OptionalInt result = value == null ? OptionalInt.empty() : value;
        if (result.isPresent() && result.getAsInt() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return result;
    }
}
