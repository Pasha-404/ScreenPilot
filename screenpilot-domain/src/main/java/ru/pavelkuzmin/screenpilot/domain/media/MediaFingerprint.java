package ru.pavelkuzmin.screenpilot.domain.media;

import java.time.Instant;
import java.util.Objects;

/** Stable-enough local-file identity without hashing the complete video. */
public record MediaFingerprint(String normalizedAbsolutePath, long sizeBytes, Instant lastModified) {
    public MediaFingerprint {
        if (normalizedAbsolutePath == null || normalizedAbsolutePath.isBlank()) {
            throw new IllegalArgumentException("normalizedAbsolutePath must not be blank");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
        lastModified = Objects.requireNonNull(lastModified, "lastModified");
    }
}
