package ru.pavelkuzmin.screenpilot.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayMode;
import ru.pavelkuzmin.screenpilot.domain.port.SettingsRepository;
import ru.pavelkuzmin.screenpilot.domain.settings.AppSettings;
import ru.pavelkuzmin.screenpilot.domain.settings.ScalingMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Settings file with an explicit safe refusal to overwrite a future schema. */
public final class JsonSettingsRepository implements SettingsRepository {

    private final Path settingsFile;
    private final ObjectMapper mapper;
    private boolean futureSchemaSeen;

    public JsonSettingsRepository(Path applicationDataDirectory) {
        this(applicationDataDirectory.resolve("settings.json"), JsonFiles.mapper());
    }

    JsonSettingsRepository(Path settingsFile, ObjectMapper mapper) {
        this.settingsFile = Objects.requireNonNull(settingsFile, "settingsFile");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public synchronized AppSettings load() {
        if (!Files.exists(settingsFile)) {
            return AppSettings.defaults();
        }
        try {
            JsonNode node = mapper.readTree(settingsFile.toFile());
            int schemaVersion = node.path("schemaVersion").asInt(0);
            if (schemaVersion > AppSettings.CURRENT_SCHEMA_VERSION) {
                futureSchemaSeen = true;
                return AppSettings.defaults();
            }
            return schemaVersion == AppSettings.CURRENT_SCHEMA_VERSION
                    ? mapper.treeToValue(node, AppSettings.class)
                    : migrateVersionZero(node);
        } catch (IOException | IllegalArgumentException exception) {
            JsonFiles.quarantineCorruptFile(settingsFile);
            return AppSettings.defaults();
        }
    }

    @Override
    public synchronized void save(AppSettings settings) {
        Objects.requireNonNull(settings, "settings");
        if (futureSchemaSeen) {
            throw new UnsupportedSchemaException(
                    settingsFile.getFileName().toString(),
                    AppSettings.CURRENT_SCHEMA_VERSION + 1,
                    AppSettings.CURRENT_SCHEMA_VERSION
            );
        }
        if (settings.schemaVersion() != AppSettings.CURRENT_SCHEMA_VERSION) {
            throw new UnsupportedSchemaException(
                    settingsFile.getFileName().toString(),
                    settings.schemaVersion(),
                    AppSettings.CURRENT_SCHEMA_VERSION
            );
        }
        JsonFiles.writeAtomically(mapper, settingsFile, settings);
    }

    private AppSettings migrateVersionZero(JsonNode node) throws IOException {
        DisplayMode manualMode = node.hasNonNull("manualDisplayMode")
                ? mapper.treeToValue(node.get("manualDisplayMode"), DisplayMode.class)
                : null;
        return new AppSettings(
                AppSettings.CURRENT_SCHEMA_VERSION,
                nullableText(node, "lastFolder"),
                nullableText(node, "lastTargetId"),
                node.path("automaticModeSelection").asBoolean(true),
                manualMode,
                enumValue(node, "scalingMode", ScalingMode.FIT),
                nullableText(node, "audioDeviceId"),
                node.path("volumePercent").asInt(100),
                node.path("muted").asBoolean(false),
                node.path("prioritizeSmoothness").asBoolean(false),
                node.path("windowWidth").asInt(0),
                node.path("windowHeight").asInt(0),
                node.path("windowX").asInt(0),
                node.path("windowY").asInt(0),
                nullableText(node, "playerProfile")
        );
    }

    private static String nullableText(JsonNode node, String fieldName) {
        return node.hasNonNull(fieldName) ? node.path(fieldName).asText() : null;
    }

    private static ScalingMode enumValue(JsonNode node, String fieldName, ScalingMode fallback) {
        if (!node.hasNonNull(fieldName)) {
            return fallback;
        }
        try {
            return ScalingMode.valueOf(node.path(fieldName).asText());
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
