package ru.pavelkuzmin.screenpilot.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.pavelkuzmin.screenpilot.domain.recovery.RecoveryRecord;
import ru.pavelkuzmin.screenpilot.persistence.FileRecoveryJournal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserDataDirectoriesTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesRoamingSettingsAndLocalOperationalDataUnderPashaApps() {
        UserDataDirectories directories = UserDataDirectories.fromEnvironment(
                Map.of(
                        "APPDATA", temporaryDirectory.resolve("Roaming").toString(),
                        "LOCALAPPDATA", temporaryDirectory.resolve("Local").toString()
                ),
                temporaryDirectory.resolve("Home")
        );

        assertThat(directories.settingsDirectory())
                .isEqualTo(temporaryDirectory.resolve("Roaming/PashaApps/ScreenPilot"));
        assertThat(directories.localDirectory())
                .isEqualTo(temporaryDirectory.resolve("Local/PashaApps/ScreenPilot"));
    }

    @Test
    void copiesLegacyDataOnlyWhenTheStandardDestinationIsMissing() throws Exception {
        Path localRoot = temporaryDirectory.resolve("Local");
        Path roamingRoot = temporaryDirectory.resolve("Roaming");
        Path legacyDirectory = localRoot.resolve("ScreenPilot");
        Files.createDirectories(legacyDirectory.resolve("recovery"));
        Files.createDirectories(legacyDirectory.resolve("logs"));
        Files.writeString(legacyDirectory.resolve("settings.json"), "legacy-settings");
        Files.writeString(legacyDirectory.resolve("resume.json"), "legacy-resume");
        Files.writeString(legacyDirectory.resolve("recovery/display-session.json"), "legacy-recovery");
        Files.writeString(legacyDirectory.resolve("logs/screenpilot.log"), "legacy-log");

        UserDataDirectories directories = UserDataDirectories.fromEnvironment(
                Map.of("APPDATA", roamingRoot.toString(), "LOCALAPPDATA", localRoot.toString()),
                temporaryDirectory.resolve("Home")
        );
        directories.prepareAndMigrateLegacyData();

        assertThat(Files.readString(directories.settingsDirectory().resolve("settings.json"))).isEqualTo("legacy-settings");
        assertThat(Files.readString(directories.settingsDirectory().resolve("resume.json"))).isEqualTo("legacy-resume");
        assertThat(Files.readString(directories.localDirectory().resolve("recovery/display-session.json")))
                .isEqualTo("legacy-recovery");
        assertThat(Files.readString(directories.localDirectory().resolve("logs/screenpilot.log"))).isEqualTo("legacy-log");

        Files.writeString(directories.settingsDirectory().resolve("settings.json"), "new-settings");
        Files.writeString(legacyDirectory.resolve("settings.json"), "changed-legacy-settings");
        directories.prepareAndMigrateLegacyData();

        assertThat(Files.readString(directories.settingsDirectory().resolve("settings.json"))).isEqualTo("new-settings");
        assertThat(Files.readString(legacyDirectory.resolve("settings.json"))).isEqualTo("changed-legacy-settings");
    }

    @Test
    void doesNotResurrectAnArchivedLegacyRecoveryJournalOnTheNextStart() throws Exception {
        Path localRoot = temporaryDirectory.resolve("Local");
        Path roamingRoot = temporaryDirectory.resolve("Roaming");
        Path legacyRecovery = localRoot.resolve("ScreenPilot/recovery/display-session.json");
        Files.createDirectories(legacyRecovery.getParent());
        RecoveryRecord record = RecoveryRecord.begin(UUID.randomUUID(), Instant.now(), "legacy-display-snapshot");
        Files.writeString(legacyRecovery, """
                {"schemaVersion":1,"sessionId":"%s","startedAt":"%s","topologyPayload":"legacy-display-snapshot","checksum":"%s"}
                """.formatted(record.sessionId(), record.startedAt(), record.checksum()));

        UserDataDirectories directories = UserDataDirectories.fromEnvironment(
                Map.of("APPDATA", roamingRoot.toString(), "LOCALAPPDATA", localRoot.toString()),
                temporaryDirectory.resolve("Home")
        );
        directories.prepareAndMigrateLegacyData();
        FileRecoveryJournal journal = new FileRecoveryJournal(directories.localDirectory());
        assertThat(journal.findUnfinished()).contains(record);

        journal.markRestored(record.sessionId());
        directories.prepareAndMigrateLegacyData();

        assertThat(journal.findUnfinished()).isEmpty();
        assertThat(directories.settingsDirectory().resolve("legacy-migration-v1.complete")).isRegularFile();
        assertThat(legacyRecovery).isRegularFile();
    }
}
