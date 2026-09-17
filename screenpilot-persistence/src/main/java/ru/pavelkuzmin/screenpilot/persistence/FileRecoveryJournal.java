package ru.pavelkuzmin.screenpilot.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import ru.pavelkuzmin.screenpilot.domain.port.RecoveryJournal;
import ru.pavelkuzmin.screenpilot.domain.recovery.RecoveryRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/** One active recovery record plus an archive useful for post-incident diagnosis. */
public final class FileRecoveryJournal implements RecoveryJournal {

    private static final Duration ARCHIVE_RETENTION = Duration.ofDays(7);

    private final Path activeRecordFile;
    private final Path archiveDirectory;
    private final ObjectMapper mapper;

    public FileRecoveryJournal(Path applicationDataDirectory) {
        this(
                applicationDataDirectory.resolve(Path.of("recovery", "display-session.json")),
                applicationDataDirectory.resolve(Path.of("recovery", "archive")),
                JsonFiles.mapper()
        );
    }

    FileRecoveryJournal(Path activeRecordFile, Path archiveDirectory, ObjectMapper mapper) {
        this.activeRecordFile = Objects.requireNonNull(activeRecordFile, "activeRecordFile");
        this.archiveDirectory = Objects.requireNonNull(archiveDirectory, "archiveDirectory");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public synchronized Optional<RecoveryRecord> findUnfinished() {
        if (!Files.exists(activeRecordFile)) {
            return Optional.empty();
        }
        try {
            JsonNode envelope = mapper.readTree(activeRecordFile.toFile());
            int schemaVersion = envelope.path("schemaVersion").asInt(0);
            if (schemaVersion > RecoveryRecord.CURRENT_SCHEMA_VERSION) {
                throw new UnsupportedSchemaException(
                        activeRecordFile.getFileName().toString(),
                        schemaVersion,
                        RecoveryRecord.CURRENT_SCHEMA_VERSION
                );
            }
            RecoveryRecord record = mapper.treeToValue(envelope, RecoveryRecord.class);
            if (!record.hasValidChecksum()) {
                JsonFiles.quarantineCorruptFile(activeRecordFile);
                return Optional.empty();
            }
            return Optional.of(record);
        } catch (UnsupportedSchemaException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            JsonFiles.quarantineCorruptFile(activeRecordFile);
            return Optional.empty();
        }
    }

    @Override
    public synchronized void begin(RecoveryRecord record) {
        Objects.requireNonNull(record, "record");
        if (!record.hasValidChecksum()) {
            throw new IllegalArgumentException("Recovery record checksum is invalid");
        }
        Optional<RecoveryRecord> unfinished = findUnfinished();
        if (unfinished.isPresent() && !unfinished.orElseThrow().sessionId().equals(record.sessionId())) {
            throw new PersistenceException("An unfinished display recovery journal already exists: "
                    + unfinished.orElseThrow().sessionId());
        }
        JsonFiles.writeAtomically(mapper, activeRecordFile, record);
    }

    @Override
    public synchronized void markRestored(UUID sessionId) {
        closeAndArchive(sessionId);
    }

    @Override
    public synchronized void markLeftAsIs(UUID sessionId) {
        closeAndArchive(sessionId);
    }

    private void closeAndArchive(UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        Optional<RecoveryRecord> active = findUnfinished();
        if (active.isEmpty() || !active.orElseThrow().sessionId().equals(sessionId)) {
            return;
        }
        RecoveryRecord record = active.orElseThrow();
        JsonFiles.writeAtomically(mapper, archiveDirectory.resolve(record.sessionId() + ".json"), record);
        try {
            Files.deleteIfExists(activeRecordFile);
        } catch (IOException exception) {
            throw new PersistenceException("Could not close recovery journal " + activeRecordFile, exception);
        }
        try {
            deleteExpiredArchive();
        } catch (IOException ignored) {
            // Archive retention is diagnostic housekeeping. It must not turn an already-successful
            // display restore into a false failure or resurrect the active journal.
        }
    }

    private void deleteExpiredArchive() throws IOException {
        if (!Files.isDirectory(archiveDirectory)) {
            return;
        }
        Instant cutoff = Instant.now().minus(ARCHIVE_RETENTION);
        try (Stream<Path> entries = Files.list(archiveDirectory)) {
            for (Path entry : entries.filter(Files::isRegularFile).toList()) {
                FileTime modified = Files.getLastModifiedTime(entry);
                if (modified.toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }
}
