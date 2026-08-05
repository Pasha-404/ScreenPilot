package ru.pavelkuzmin.screenpilot.domain.port;

import ru.pavelkuzmin.screenpilot.domain.recovery.RecoveryRecord;

import java.util.Optional;
import java.util.UUID;

public interface RecoveryJournal {
    Optional<RecoveryRecord> findUnfinished();

    void begin(RecoveryRecord record);

    void markRestored(UUID sessionId);
}
