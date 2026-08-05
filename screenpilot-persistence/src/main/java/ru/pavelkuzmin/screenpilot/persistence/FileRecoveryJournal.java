package ru.pavelkuzmin.screenpilot.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import ru.pavelkuzmin.screenpilot.domain.port.RecoveryJournal;
import ru.pavelkuzmin.screenpilot.domain.recovery.RecoveryRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One active recovery record plus an archive useful for post-incident diagnosis. */
public final class FileRecoveryJournal implements RecoveryJournal {

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
            RecoveryRecord record = mapper.readValue(activeRecordFile.toFile(), RecoveryRecord.class);
            if (record.schemaVersion() > RecoveryRecord.CURRENT_SCHEMA_VERSION) {
                throw new UnsupportedSchemaException(
                        activeRecordFile.getFileName().toString(),
                        record.schemaVersion(),
                        RecoveryRecord.CURRENT_SCHEMA_VERSION
                );
            }
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
        JsonFiles.writeAtomically(mapper, activeRecordFile, record);
    }

    @Override
    public synchronized void markRestored(UUID sessionId) {
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
    }
}
