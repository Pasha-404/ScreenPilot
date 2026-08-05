package ru.pavelkuzmin.screenpilot.domain.port;

import ru.pavelkuzmin.screenpilot.domain.media.MediaFingerprint;
import ru.pavelkuzmin.screenpilot.domain.media.ResumeEntry;

import java.util.Optional;

public interface ResumeRepository {
    Optional<ResumeEntry> find(MediaFingerprint fingerprint);

    void save(ResumeEntry entry);

    void markCompleted(MediaFingerprint fingerprint);
}
