package ru.pavelkuzmin.screenpilot.domain.media;

import java.util.Objects;
import java.util.OptionalInt;

/** A track reported by the active mpv media session. */
public record MediaTrack(
        int id,
        MediaTrackKind kind,
        String language,
        String title,
        String codec,
        OptionalInt channelCount,
        boolean defaultTrack,
        boolean forced,
        boolean selected,
        boolean external
) {
    public MediaTrack {
        if (id < 1) {
            throw new IllegalArgumentException("track id must be positive");
        }
        kind = Objects.requireNonNullElse(kind, MediaTrackKind.UNKNOWN);
        language = safeText(language);
        title = safeText(title);
        codec = safeText(codec);
        channelCount = channelCount == null ? OptionalInt.empty() : channelCount;
        if (channelCount.isPresent() && channelCount.getAsInt() < 1) {
            throw new IllegalArgumentException("channel count must be positive when present");
        }
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }
}
