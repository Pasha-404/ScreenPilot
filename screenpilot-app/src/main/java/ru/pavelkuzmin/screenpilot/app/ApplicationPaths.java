package ru.pavelkuzmin.screenpilot.app;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Stable user-writable locations and runtime resource lookup for both Gradle and jpackage starts. */
public final class ApplicationPaths {

    private static final String LOG_DIRECTORY_PROPERTY = "screenpilot.log.directory";

    private ApplicationPaths() {
    }

    /** User settings and resume data, safe to retain across application updates. */
    public static Path settingsDirectory() {
        return currentDirectories().settingsDirectory();
    }

    /** Logs, recovery journals and the single-instance lock, local to this Windows profile. */
    public static Path localDataDirectory() {
        return currentDirectories().localDirectory();
    }

    /**
     * Compatibility alias for technical callers that need local, non-roaming data.
     * New production code should name the intended location explicitly.
     */
    public static Path applicationDataDirectory() {
        return localDataDirectory();
    }

    public static Path logDirectory() {
        return localDataDirectory().resolve("logs");
    }

    /** Creates standard directories and makes a non-destructive copy from the pre-AppFleet layout. */
    public static void prepareUserDataDirectories() throws IOException {
        currentDirectories().prepareAndMigrateLegacyData();
    }

    /** Configures Logback before application classes create their first logger. */
    public static void configureLogging() {
        Path directory = logDirectory();
        try {
            prepareUserDataDirectories();
            Files.createDirectories(directory);
        } catch (IOException ignored) {
            // Logging must not make the player unusable; Logback falls back to its configured default path.
        }
        System.setProperty(LOG_DIRECTORY_PROPERTY, directory.toAbsolutePath().normalize().toString());
    }

    /** Resolves the bundled mpv beside the packaged application JAR, then the development runtime. */
    public static Path findMpvExecutable(Class<?> applicationClass) {
        String configured = System.getProperty("screenpilot.mpv.path");
        if (configured != null && !configured.isBlank()) {
            Path explicit = Path.of(configured).toAbsolutePath().normalize();
            if (Files.isRegularFile(explicit)) {
                return explicit;
            }
        }
        try {
            Path codeSource = Path.of(applicationClass.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path packageDirectory = Files.isRegularFile(codeSource) ? codeSource.getParent() : codeSource;
            Path packagedRuntime = packageDirectory.resolve("mpv").resolve("mpv.exe");
            if (Files.isRegularFile(packagedRuntime)) {
                return packagedRuntime;
            }
        } catch (URISyntaxException | SecurityException ignored) {
            // Development lookup below still gives a clear failure if the runtime is absent.
        }
        for (Path current = Path.of("").toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            Path candidate = current.resolve(Path.of("vendor", "mpv", "runtime", "mpv.exe"));
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return Path.of("vendor", "mpv", "runtime", "mpv.exe").toAbsolutePath().normalize();
    }

    private static UserDataDirectories currentDirectories() {
        return UserDataDirectories.fromEnvironment(System.getenv(), Path.of(System.getProperty("user.home")));
    }
}
