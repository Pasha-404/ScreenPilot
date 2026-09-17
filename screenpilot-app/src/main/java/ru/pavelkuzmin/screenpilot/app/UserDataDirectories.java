package ru.pavelkuzmin.screenpilot.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Stable, user-writable locations required by the AppFleet Windows installer standard.
 *
 * <p>The previous ScreenPilot layout used one local directory. A release using the AppFleet
 * installer copies its settings and resume data forward exactly once, without deleting the old
 * files or overwriting a newer destination.</p>
 */
final class UserDataDirectories {

    private static final String PUBLISHER_DIRECTORY = "PashaApps";
    private static final String APPLICATION_NAME = "ScreenPilot";
    private static final String LEGACY_MIGRATION_MARKER = "legacy-migration-v1.complete";

    private final Path settingsDirectory;
    private final Path localDirectory;
    private final Path legacyDirectory;

    private UserDataDirectories(Path settingsDirectory, Path localDirectory, Path legacyDirectory) {
        this.settingsDirectory = settingsDirectory;
        this.localDirectory = localDirectory;
        this.legacyDirectory = legacyDirectory;
    }

    static UserDataDirectories fromEnvironment(Map<String, String> environment, Path userHome) {
        Path roamingRoot = root(environment.get("APPDATA"), userHome.resolve(Path.of("AppData", "Roaming")));
        Path localRoot = root(environment.get("LOCALAPPDATA"), userHome.resolve(Path.of("AppData", "Local")));
        return new UserDataDirectories(
                roamingRoot.resolve(PUBLISHER_DIRECTORY).resolve(APPLICATION_NAME),
                localRoot.resolve(PUBLISHER_DIRECTORY).resolve(APPLICATION_NAME),
                localRoot.resolve(APPLICATION_NAME)
        );
    }

    Path settingsDirectory() {
        return settingsDirectory;
    }

    Path localDirectory() {
        return localDirectory;
    }

    void prepareAndMigrateLegacyData() throws IOException {
        Files.createDirectories(settingsDirectory);
        Files.createDirectories(localDirectory);
        Path migrationMarker = settingsDirectory.resolve(LEGACY_MIGRATION_MARKER);
        if (Files.isRegularFile(migrationMarker)) {
            return;
        }
        copyFileIfMissing(legacyDirectory.resolve("settings.json"), settingsDirectory.resolve("settings.json"));
        copyFileIfMissing(legacyDirectory.resolve("resume.json"), settingsDirectory.resolve("resume.json"));
        copyTreeIfMissing(legacyDirectory.resolve("recovery"), localDirectory.resolve("recovery"));
        copyTreeIfMissing(legacyDirectory.resolve("logs"), localDirectory.resolve("logs"));
        publishMigrationMarker(migrationMarker);
    }

    private static Path root(String configuredRoot, Path fallback) {
        return configuredRoot == null || configuredRoot.isBlank() ? fallback : Path.of(configuredRoot);
    }

    private static void copyFileIfMissing(Path source, Path target) throws IOException {
        if (!Files.isRegularFile(source) || Files.exists(target)) {
            return;
        }
        Files.createDirectories(target.getParent());
        Path stagedCopy = Files.createTempFile(target.getParent(), ".screenpilot-migration-", ".tmp");
        try {
            Files.copy(source, stagedCopy, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(stagedCopy, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(stagedCopy, target);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Another launch completed this exact non-destructive publication first.
            }
        } finally {
            Files.deleteIfExists(stagedCopy);
        }
    }

    private static void copyTreeIfMissing(Path sourceRoot, Path targetRoot) throws IOException {
        if (!Files.isDirectory(sourceRoot)) {
            return;
        }
        try (Stream<Path> sourcePaths = Files.walk(sourceRoot)) {
            for (Path source : sourcePaths.sorted().toList()) {
                Path target = targetRoot.resolve(sourceRoot.relativize(source)).normalize();
                if (!target.startsWith(targetRoot.normalize())) {
                    throw new IOException("Legacy migration target escaped its destination root: " + target);
                }
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    copyFileIfMissing(source, target);
                }
            }
        }
    }

    private static void publishMigrationMarker(Path marker) throws IOException {
        Path stagedMarker = Files.createTempFile(marker.getParent(), ".screenpilot-migration-", ".tmp");
        try {
            Files.writeString(stagedMarker, "completed\n", StandardCharsets.UTF_8);
            try {
                Files.move(stagedMarker, marker, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(stagedMarker, marker);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // A concurrent start already published the durable completion marker.
            }
        } finally {
            Files.deleteIfExists(stagedMarker);
        }
    }
}
