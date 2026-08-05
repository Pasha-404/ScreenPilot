package ru.pavelkuzmin.screenpilot.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.pavelkuzmin.screenpilot.domain.recovery.RecoveryRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FileRecoveryJournalTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void archivesAndClosesAConfirmedRecoveryRecord() {
        FileRecoveryJournal journal = new FileRecoveryJournal(temporaryDirectory);
        RecoveryRecord record = RecoveryRecord.begin(UUID.randomUUID(), Instant.now(), "captured-display-topology");

        journal.begin(record);
        assertThat(journal.findUnfinished()).contains(record);

        journal.markRestored(record.sessionId());

        assertThat(journal.findUnfinished()).isEmpty();
        assertThat(temporaryDirectory.resolve(Path.of("recovery", "archive", record.sessionId() + ".json"))).exists();
    }

    @Test
    void quarantinesRecordWithInvalidChecksum() throws Exception {
        Path activeFile = temporaryDirectory.resolve(Path.of("recovery", "display-session.json"));
        Files.createDirectories(activeFile.getParent());
        RecoveryRecord valid = RecoveryRecord.begin(UUID.randomUUID(), Instant.now(), "payload");
        JsonFiles.writeAtomically(JsonFiles.mapper(), activeFile, new RecoveryRecord(
                valid.schemaVersion(), valid.sessionId(), valid.startedAt(), "changed", valid.checksum()
        ));

        assertThat(new FileRecoveryJournal(temporaryDirectory).findUnfinished()).isEmpty();
        try (Stream<Path> files = Files.list(activeFile.getParent())) {
            assertThat(files.anyMatch(path -> path.getFileName().toString().startsWith("display-session.json.corrupt-"))).isTrue();
        }
    }
}
