package ru.pavelkuzmin.screenpilot.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

final class JsonFiles {

    private static final DateTimeFormatter CORRUPT_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private JsonFiles() {
    }

    static ObjectMapper mapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    static void writeAtomically(ObjectMapper mapper, Path target, Object value) {
        try {
            Path parent = target.toAbsolutePath().normalize().getParent();
            if (parent == null) {
                throw new PersistenceException("JSON target must have a parent directory: " + target);
            }
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
            try {
                mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new PersistenceException("Could not atomically write " + target, exception);
        }
    }

    static Path quarantineCorruptFile(Path target) {
        if (!Files.exists(target)) {
            return null;
        }
        Path corrupt = target.resolveSibling(target.getFileName() + ".corrupt-" + CORRUPT_TIMESTAMP.format(Instant.now()));
        try {
            Files.move(target, corrupt, StandardCopyOption.REPLACE_EXISTING);
            return corrupt;
        } catch (IOException exception) {
            throw new PersistenceException("Could not quarantine corrupt JSON file " + target, exception);
        }
    }
}
