package ru.pavelkuzmin.screenpilot.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import ru.pavelkuzmin.screenpilot.domain.media.MediaFingerprint;
import ru.pavelkuzmin.screenpilot.domain.media.ResumeEntry;
import ru.pavelkuzmin.screenpilot.domain.port.ResumeRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class JsonResumeRepository implements ResumeRepository {

    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_ENTRIES = 1_000;

    private final Path resumeFile;
    private final ObjectMapper mapper;

    public JsonResumeRepository(Path applicationDataDirectory) {
        this(applicationDataDirectory.resolve("resume.json"), JsonFiles.mapper());
    }

    JsonResumeRepository(Path resumeFile, ObjectMapper mapper) {
        this.resumeFile = Objects.requireNonNull(resumeFile, "resumeFile");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public synchronized Optional<ResumeEntry> find(MediaFingerprint fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        return readStore().entries().stream().filter(entry -> entry.fingerprint().equals(fingerprint)).findFirst();
    }

    @Override
    public synchronized void save(ResumeEntry entry) {
        Objects.requireNonNull(entry, "entry");
        List<ResumeEntry> entries = new ArrayList<>(readStore().entries());
        entries.removeIf(candidate -> candidate.fingerprint().equals(entry.fingerprint()));
        entries.add(entry);
        entries.sort(Comparator.comparing(ResumeEntry::updatedAt).reversed());
        if (entries.size() > MAX_ENTRIES) {
            entries = new ArrayList<>(entries.subList(0, MAX_ENTRIES));
        }
        JsonFiles.writeAtomically(mapper, resumeFile, new ResumeStore(SCHEMA_VERSION, entries));
    }

    @Override
    public synchronized void markCompleted(MediaFingerprint fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        List<ResumeEntry> entries = new ArrayList<>(readStore().entries());
        if (entries.removeIf(entry -> entry.fingerprint().equals(fingerprint))) {
            JsonFiles.writeAtomically(mapper, resumeFile, new ResumeStore(SCHEMA_VERSION, entries));
        }
    }

    private ResumeStore readStore() {
        if (!Files.exists(resumeFile)) {
            return ResumeStore.empty();
        }
        try {
            ResumeStore store = mapper.readValue(resumeFile.toFile(), ResumeStore.class);
            if (store.schemaVersion() > SCHEMA_VERSION) {
                throw new UnsupportedSchemaException(resumeFile.getFileName().toString(), store.schemaVersion(), SCHEMA_VERSION);
            }
            return store;
        } catch (UnsupportedSchemaException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            JsonFiles.quarantineCorruptFile(resumeFile);
            return ResumeStore.empty();
        }
    }

    public record ResumeStore(int schemaVersion, List<ResumeEntry> entries) {
        public ResumeStore {
            if (schemaVersion < 1) {
                throw new IllegalArgumentException("schemaVersion must be positive");
            }
            entries = entries == null ? List.of() : List.copyOf(entries);
        }

        static ResumeStore empty() {
            return new ResumeStore(SCHEMA_VERSION, List.of());
        }
    }
}
