package ru.pavelkuzmin.screenpilot.player.mpv;

import com.fasterxml.jackson.databind.JsonNode;
import ru.pavelkuzmin.screenpilot.domain.display.RefreshRate;
import ru.pavelkuzmin.screenpilot.domain.media.AudioOutputDevice;
import ru.pavelkuzmin.screenpilot.domain.media.MediaInfo;
import ru.pavelkuzmin.screenpilot.domain.media.MediaTrack;
import ru.pavelkuzmin.screenpilot.domain.media.MediaTrackKind;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/** Converts mpv JSON properties to domain values without leaking Jackson outside the adapter. */
final class MpvMediaMapper {

    private static final List<RefreshRate> KNOWN_FRAME_RATES = List.of(
            RefreshRate.of(24_000, 1_001), RefreshRate.of(24, 1), RefreshRate.of(25, 1),
            RefreshRate.of(30_000, 1_001), RefreshRate.of(30, 1), RefreshRate.of(50, 1),
            RefreshRate.of(60_000, 1_001), RefreshRate.of(60, 1)
    );

    private MpvMediaMapper() {
    }

    static MediaInfo mediaInfo(Path source, Map<String, JsonNode> properties) {
        JsonNode videoParams = properties.getOrDefault("video-params", MissingNode.INSTANCE);
        List<MediaTrack> tracks = tracks(properties.getOrDefault("track-list", MissingNode.INSTANCE));
        OptionalInt selectedAudio = selectedTrack(tracks, MediaTrackKind.AUDIO);
        OptionalInt selectedSubtitle = selectedTrack(tracks, MediaTrackKind.SUBTITLE);
        String transfer = text(videoParams, "gamma").orElse("");
        return new MediaInfo(
                source,
                text(properties.getOrDefault("media-title", MissingNode.INSTANCE)).orElse(source.getFileName().toString()),
                duration(properties.getOrDefault("duration", MissingNode.INSTANCE)),
                text(properties.getOrDefault("file-format", MissingNode.INSTANCE)),
                text(properties.getOrDefault("video-format", MissingNode.INSTANCE)),
                text(videoParams, "hw-pixelformat"),
                positiveInt(videoParams, "w"),
                positiveInt(videoParams, "h"),
                frameRate(properties.getOrDefault("container-fps", MissingNode.INSTANCE)),
                videoParams.path("interlaced").asBoolean(false),
                positiveInt(videoParams, "bits-per-component"),
                text(videoParams, "colormatrix"),
                transfer.isBlank() ? Optional.empty() : Optional.of(transfer),
                transfer.toLowerCase(java.util.Locale.ROOT).contains("pq")
                        || transfer.toLowerCase(java.util.Locale.ROOT).contains("hlg"),
                tracks,
                selectedAudio,
                selectedSubtitle
        );
    }

    static List<MediaTrack> tracks(JsonNode value) {
        List<MediaTrack> result = new ArrayList<>();
        if (!value.isArray()) {
            return List.of();
        }
        for (JsonNode track : value) {
            if (!track.path("id").canConvertToInt() || track.path("id").asInt() < 1) {
                continue;
            }
            result.add(new MediaTrack(
                    track.path("id").asInt(),
                    trackKind(track.path("type").asText()),
                    track.path("lang").asText(""),
                    track.path("title").asText(""),
                    track.path("codec").asText(""),
                    positiveInt(track, "demux-channel-count"),
                    track.path("default").asBoolean(false),
                    track.path("forced").asBoolean(false),
                    track.path("selected").asBoolean(false),
                    track.path("external").asBoolean(false)
            ));
        }
        return List.copyOf(result);
    }

    static List<AudioOutputDevice> audioOutputs(JsonNode value) {
        List<AudioOutputDevice> result = new ArrayList<>();
        if (!value.isArray()) {
            return List.of();
        }
        for (JsonNode device : value) {
            String id = device.path("name").asText("");
            if (!id.isBlank()) {
                result.add(new AudioOutputDevice(id, device.path("description").asText(id)));
            }
        }
        return List.copyOf(result);
    }

    static Optional<RefreshRate> frameRate(JsonNode value) {
        if (!value.isNumber() || value.asDouble() <= 0) {
            return Optional.empty();
        }
        double hertz = value.asDouble();
        for (RefreshRate known : KNOWN_FRAME_RATES) {
            if (Math.abs(known.hertz() - hertz) <= 0.02) {
                return Optional.of(known);
            }
        }
        return Optional.of(RefreshRate.of(Math.round(hertz * 1_000), 1_000));
    }

    private static Optional<Duration> duration(JsonNode value) {
        return value.isNumber() && value.asDouble() >= 0
                ? Optional.of(Duration.ofMillis(Math.round(value.asDouble() * 1_000)))
                : Optional.empty();
    }

    private static OptionalInt selectedTrack(List<MediaTrack> tracks, MediaTrackKind kind) {
        return tracks.stream()
                .filter(track -> track.kind() == kind && track.selected())
                .mapToInt(MediaTrack::id)
                .findFirst();
    }

    private static MediaTrackKind trackKind(String type) {
        return switch (type) {
            case "video" -> MediaTrackKind.VIDEO;
            case "audio" -> MediaTrackKind.AUDIO;
            case "sub" -> MediaTrackKind.SUBTITLE;
            default -> MediaTrackKind.UNKNOWN;
        };
    }

    private static Optional<String> text(JsonNode parent, String field) {
        return text(parent.path(field));
    }

    private static Optional<String> text(JsonNode value) {
        if (!value.isTextual() || value.asText().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.asText());
    }

    private static OptionalInt positiveInt(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        return value.canConvertToInt() && value.asInt() > 0 ? OptionalInt.of(value.asInt()) : OptionalInt.empty();
    }

    private static final class MissingNode {
        private static final JsonNode INSTANCE = com.fasterxml.jackson.databind.node.MissingNode.getInstance();

        private MissingNode() {
        }
    }
}
