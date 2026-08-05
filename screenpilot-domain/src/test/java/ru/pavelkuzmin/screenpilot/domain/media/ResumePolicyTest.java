package ru.pavelkuzmin.screenpilot.domain.media;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ResumePolicyTest {

    private static final MediaFingerprint FINGERPRINT = new MediaFingerprint(
            "C:/Video/film.mkv", 100, Instant.parse("2026-08-05T00:00:00Z")
    );

    @Test
    void doesNotOfferResumeBeforeThirtySeconds() {
        ResumeEntry entry = new ResumeEntry(FINGERPRINT, Duration.ofSeconds(29), Duration.ofMinutes(10), Instant.now());

        assertThat(ResumePolicy.shouldOfferResume(entry)).isFalse();
    }

    @Test
    void treatsNinetyFivePercentAndLastTwoMinutesAsCompleted() {
        assertThat(ResumePolicy.isCompleted(Duration.ofSeconds(570), Duration.ofSeconds(600))).isTrue();
        assertThat(ResumePolicy.isCompleted(Duration.ofSeconds(481), Duration.ofSeconds(600))).isTrue();
        assertThat(ResumePolicy.isCompleted(Duration.ofSeconds(400), Duration.ofSeconds(600))).isFalse();
    }

    @Test
    void clampsSeekToDuration() {
        assertThat(ResumePolicy.clamp(Duration.ofSeconds(999), Duration.ofSeconds(123))).isEqualTo(Duration.ofSeconds(123));
    }
}
