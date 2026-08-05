package ru.pavelkuzmin.screenpilot.domain.media;

import java.time.Duration;
import java.util.Objects;

public final class ResumePolicy {

    public static final Duration MINIMUM_RESUMABLE_POSITION = Duration.ofSeconds(30);
    public static final Duration COMPLETED_REMAINING_TIME = Duration.ofSeconds(120);
    private static final double COMPLETED_FRACTION = 0.95;

    private ResumePolicy() {
    }

    public static boolean isCompleted(Duration position, Duration duration) {
        position = requireNonNegative(position, "position");
        duration = requireNonNegative(duration, "duration");
        if (duration.isZero()) {
            return false;
        }
        return position.compareTo(duration.multipliedBy(95).dividedBy(100)) >= 0
                || duration.minus(position).compareTo(COMPLETED_REMAINING_TIME) <= 0;
    }

    public static boolean shouldOfferResume(ResumeEntry entry) {
        Objects.requireNonNull(entry, "entry");
        return entry.position().compareTo(MINIMUM_RESUMABLE_POSITION) >= 0
                && !isCompleted(entry.position(), entry.duration());
    }

    public static Duration clamp(Duration position, Duration duration) {
        position = requireNonNegative(position, "position");
        duration = requireNonNegative(duration, "duration");
        if (position.compareTo(duration) > 0) {
            return duration;
        }
        return position;
    }

    private static Duration requireNonNegative(Duration value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }
}
