package ru.pavelkuzmin.screenpilot.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.pavelkuzmin.screenpilot.domain.recovery.RecoveryRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void preservesAnUnknownFutureRecoverySchemaWithoutTreatingItAsCorruption() throws Exception {
        Path activeFile = temporaryDirectory.resolve(Path.of("recovery", "display-session.json"));
        Files.createDirectories(activeFile.getParent());
        String futureJson = """
                {"schemaVersion":2,"futureField":"must-survive","sessionId":"00000000-0000-0000-0000-000000000000"}
                """;
        Files.writeString(activeFile, futureJson);

        assertThatThrownBy(() -> new FileRecoveryJournal(temporaryDirectory).findUnfinished())
                .isInstanceOf(UnsupportedSchemaException.class);

        assertThat(Files.readString(activeFile)).isEqualTo(futureJson);
        try (Stream<Path> files = Files.list(activeFile.getParent())) {
            assertThat(files.noneMatch(path -> path.getFileName().toString().contains(".corrupt-"))).isTrue();
        }
    }

    @Test
    void archivesAndClosesWhenUserKeepsTheCurrentConfiguration() {
        FileRecoveryJournal journal = new FileRecoveryJournal(temporaryDirectory);
        RecoveryRecord record = RecoveryRecord.begin(UUID.randomUUID(), Instant.now(), "captured-display-topology");

        journal.begin(record);
        journal.markLeftAsIs(record.sessionId());

        assertThat(journal.findUnfinished()).isEmpty();
        assertThat(temporaryDirectory.resolve(Path.of("recovery", "archive", record.sessionId() + ".json"))).exists();
    }

    @Test
    void neverOverwritesAnotherUnfinishedRecoveryRecord() {
        FileRecoveryJournal journal = new FileRecoveryJournal(temporaryDirectory);
        journal.begin(RecoveryRecord.begin(UUID.randomUUID(), Instant.now(), "first"));

        assertThatThrownBy(() -> journal.begin(RecoveryRecord.begin(UUID.randomUUID(), Instant.now(), "second")))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("unfinished display recovery journal");
    }

    @Test
    void removesArchiveEntriesOlderThanSevenDaysWhenClosingARecord() throws Exception {
        Path archive = temporaryDirectory.resolve(Path.of("recovery", "archive"));
        Files.createDirectories(archive);
        Path expired = archive.resolve("expired.json");
        Files.writeString(expired, "old diagnostic record");
        Files.setLastModifiedTime(expired, FileTime.from(Instant.now().minus(Duration.ofDays(8))));
        FileRecoveryJournal journal = new FileRecoveryJournal(temporaryDirectory);
        RecoveryRecord record = RecoveryRecord.begin(UUID.randomUUID(), Instant.now(), "fresh");

        journal.begin(record);
        journal.markRestored(record.sessionId());

        assertThat(expired).doesNotExist();
        assertThat(archive.resolve(record.sessionId() + ".json")).exists();
    }
}
