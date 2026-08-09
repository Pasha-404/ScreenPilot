package ru.pavelkuzmin.screenpilot.domain.media;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** One local media file in the in-memory playlist. No playlist is persisted between launches. */
public record PlaylistItem(
        MediaFingerprint fingerprint,
        Path source,
        Optional<MediaInfo> mediaInfo,
        MediaProbeStatus probeStatus
) {
    public PlaylistItem {
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        source = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        mediaInfo = mediaInfo == null ? Optional.empty() : mediaInfo;
        probeStatus = probeStatus == null ? MediaProbeStatus.PENDING : probeStatus;
        if (!source.toString().equals(fingerprint.normalizedAbsolutePath())) {
            throw new IllegalArgumentException("Playlist source must match its fingerprint path");
        }
        if (probeStatus == MediaProbeStatus.READY && mediaInfo.isEmpty()) {
            throw new IllegalArgumentException("A ready probe must provide media information");
        }
    }

    public static PlaylistItem pending(MediaFingerprint fingerprint) {
        return new PlaylistItem(
                fingerprint,
                Path.of(fingerprint.normalizedAbsolutePath()),
                Optional.empty(),
                MediaProbeStatus.PENDING
        );
    }

    public String displayName() {
        return mediaInfo.map(MediaInfo::displayName)
                .orElseGet(() -> source.getFileName() == null ? source.toString() : source.getFileName().toString());
    }

    public PlaylistItem withMediaInfo(MediaInfo nextMediaInfo) {
        nextMediaInfo = Objects.requireNonNull(nextMediaInfo, "nextMediaInfo");
        if (!source.equals(nextMediaInfo.source())) {
            throw new IllegalArgumentException("Metadata source must belong to the playlist item");
        }
        return new PlaylistItem(fingerprint, source, Optional.of(nextMediaInfo), MediaProbeStatus.READY);
    }

    public PlaylistItem withProbeFailure() {
        return new PlaylistItem(fingerprint, source, Optional.empty(), MediaProbeStatus.FAILED);
    }
}
