package ru.pavelkuzmin.screenpilot.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.pavelkuzmin.screenpilot.domain.media.MediaFingerprint;
import ru.pavelkuzmin.screenpilot.domain.media.ResumeEntry;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JsonResumeRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void replacesEntriesForTheSameFingerprintAndRemovesCompletedEntry() {
        JsonResumeRepository repository = new JsonResumeRepository(temporaryDirectory);
        MediaFingerprint fingerprint = fingerprint(1);
        ResumeEntry first = entry(fingerprint, 40, Instant.parse("2026-08-05T10:00:00Z"));
        ResumeEntry replacement = entry(fingerprint, 50, Instant.parse("2026-08-05T10:01:00Z"));

        repository.save(first);
        repository.save(replacement);

        assertThat(repository.find(fingerprint)).contains(replacement);
        repository.markCompleted(fingerprint);
        assertThat(repository.find(fingerprint)).isEmpty();
    }

    @Test
    void retainsAtMostOneThousandMostRecentlyUpdatedEntries() {
        JsonResumeRepository repository = new JsonResumeRepository(temporaryDirectory);
        for (int index = 0; index < 1_001; index++) {
            repository.save(entry(fingerprint(index), 50, Instant.ofEpochSecond(index)));
        }

        assertThat(repository.find(fingerprint(0))).isEmpty();
        assertThat(repository.find(fingerprint(1_000))).isPresent();
    }

    private static MediaFingerprint fingerprint(int index) {
        return new MediaFingerprint("C:/Video/" + index + ".mkv", index, Instant.ofEpochSecond(index));
    }

    private static ResumeEntry entry(MediaFingerprint fingerprint, int position, Instant updatedAt) {
        return new ResumeEntry(fingerprint, Duration.ofSeconds(position), Duration.ofMinutes(10), updatedAt);
    }
}
