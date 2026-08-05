package ru.pavelkuzmin.screenpilot.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.pavelkuzmin.screenpilot.domain.settings.AppSettings;
import ru.pavelkuzmin.screenpilot.domain.settings.ScalingMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonSettingsRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAndReloadsSettingsAtomically() throws Exception {
        JsonSettingsRepository repository = new JsonSettingsRepository(temporaryDirectory);
        AppSettings settings = new AppSettings(
                1, "C:/Video", "monitor-1", false, null, ScalingMode.FILL,
                "wasapi/display", 35, true, true, 1200, 800, 10, 20, "mpv-default"
        );

        repository.save(settings);

        assertThat(repository.load()).isEqualTo(settings);
        assertThat(temporaryDirectory.resolve("settings.json")).exists();
        try (Stream<Path> files = Files.list(temporaryDirectory)) {
            assertThat(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp"))).isTrue();
        }
    }

    @Test
    void quarantinesCorruptSettingsAndLoadsDefaults() throws Exception {
        Path settings = temporaryDirectory.resolve("settings.json");
        Files.writeString(settings, "{not-json");

        assertThat(new JsonSettingsRepository(temporaryDirectory).load()).isEqualTo(AppSettings.defaults());
        try (Stream<Path> files = Files.list(temporaryDirectory)) {
            assertThat(files.anyMatch(path -> path.getFileName().toString().startsWith("settings.json.corrupt-"))).isTrue();
        }
    }

    @Test
    void migratesVersionZeroWithSafeDefaultsForAbsentFields() throws Exception {
        Files.writeString(temporaryDirectory.resolve("settings.json"), """
                {"schemaVersion":0,"lastFolder":"C:/Movies","volumePercent":55}
                """);

        AppSettings settings = new JsonSettingsRepository(temporaryDirectory).load();

        assertThat(settings.schemaVersion()).isEqualTo(AppSettings.CURRENT_SCHEMA_VERSION);
        assertThat(settings.lastFolder()).isEqualTo("C:/Movies");
        assertThat(settings.volumePercent()).isEqualTo(55);
        assertThat(settings.scalingMode()).isEqualTo(ScalingMode.FIT);
    }

    @Test
    void refusesToOverwriteAFileFromANewerSchema() throws Exception {
        Files.writeString(temporaryDirectory.resolve("settings.json"), "{" + "\"schemaVersion\":99}");
        JsonSettingsRepository repository = new JsonSettingsRepository(temporaryDirectory);

        assertThat(repository.load()).isEqualTo(AppSettings.defaults());
        assertThatThrownBy(() -> repository.save(AppSettings.defaults()))
                .isInstanceOf(UnsupportedSchemaException.class);
    }
}
