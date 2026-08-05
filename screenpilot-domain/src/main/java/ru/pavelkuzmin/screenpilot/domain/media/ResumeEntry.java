package ru.pavelkuzmin.screenpilot.domain.media;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record ResumeEntry(
        MediaFingerprint fingerprint,
        Duration position,
        Duration duration,
        Instant updatedAt
) {
    public ResumeEntry {
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        position = requireNonNegative(position, "position");
        duration = requireNonNegative(duration, "duration");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static Duration requireNonNegative(Duration value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }
}
