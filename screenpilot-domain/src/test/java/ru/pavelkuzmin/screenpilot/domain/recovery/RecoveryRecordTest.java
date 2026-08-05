package ru.pavelkuzmin.screenpilot.domain.recovery;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryRecordTest {

    @Test
    void detectsAChangedTopologyPayload() {
        RecoveryRecord record = RecoveryRecord.begin(UUID.randomUUID(), Instant.now(), "topology-v1");

        assertThat(record.hasValidChecksum()).isTrue();
        assertThat(new RecoveryRecord(
                record.schemaVersion(), record.sessionId(), record.startedAt(), "changed", record.checksum()
        ).hasValidChecksum()).isFalse();
    }
}
