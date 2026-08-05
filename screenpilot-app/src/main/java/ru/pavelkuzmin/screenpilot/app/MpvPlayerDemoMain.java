package ru.pavelkuzmin.screenpilot.app;

import ru.pavelkuzmin.screenpilot.domain.port.ProcessContainment;
import ru.pavelkuzmin.screenpilot.domain.session.PlayerNotification;
import ru.pavelkuzmin.screenpilot.platform.windows.WindowsProcessContainment;
import ru.pavelkuzmin.screenpilot.player.mpv.MpvPlayerAdapter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Manual Stage 4 smoke tool. It exercises the production adapter without a JavaFX interface. */
public final class MpvPlayerDemoMain {

    private static final Duration START_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration LOAD_TIMEOUT = Duration.ofSeconds(15);

    private MpvPlayerDemoMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Path media = optionPath(args, "--media=");
        if (media == null || !Files.isRegularFile(media)) {
            System.out.println("[FAIL] Pass an existing media file: player-demo --media=C:\\Videos\\movie.mkv");
            return 2;
        }
        Path executable = optionPath(args, "--mpv=");
        if (executable == null) {
            executable = findMpvExecutable();
        }
        if (!Files.isRegularFile(executable)) {
            System.out.println("[FAIL] mpv.exe was not found: " + executable);
            return 2;
        }

        long holdMillis = optionLong(args, "--hold-ms=", 15_000, 0, 600_000);
        ProcessContainment containment = isWindows() ? new WindowsProcessContainment() : ProcessContainment.disabled();
        try (MpvPlayerAdapter adapter = new MpvPlayerAdapter(executable, containment)) {
            adapter.notifications().subscribe(new ConsoleNotifications());
            adapter.start().toCompletableFuture().get(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            var info = adapter.load(media, Duration.ZERO).toCompletableFuture().get(LOAD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            System.out.println("[PASS] Loaded " + info.displayName() + "; tracks=" + info.tracks().size());
            adapter.pause().toCompletableFuture().get(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            adapter.seek(Duration.ofSeconds(1)).toCompletableFuture().get(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            adapter.play().toCompletableFuture().get(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (holdMillis > 0) {
                System.out.println("[INFO] Playback will remain open for " + holdMillis + " ms.");
                Thread.sleep(holdMillis);
            }
            adapter.stop().toCompletableFuture().get(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            System.out.println("[PASS] Player adapter stopped cleanly.");
            return 0;
        } catch (Exception exception) {
            System.out.println("[FAIL] player adapter demo: " + exception.getMessage());
            return 1;
        }
    }

    private static Path optionPath(String[] args, String prefix) {
        return Arrays.stream(args)
                .filter(argument -> argument.startsWith(prefix))
                .findFirst()
                .map(argument -> Path.of(argument.substring(prefix.length())).toAbsolutePath().normalize())
                .orElse(null);
    }

    private static long optionLong(String[] args, String prefix, long fallback, long minimum, long maximum) {
        return Arrays.stream(args)
                .filter(argument -> argument.startsWith(prefix))
                .findFirst()
                .map(argument -> Long.parseLong(argument.substring(prefix.length())))
                .filter(value -> value >= minimum && value <= maximum)
                .orElse(fallback);
    }

    private static Path findMpvExecutable() {
        for (Path current = Path.of("").toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            Path candidate = current.resolve(Path.of("vendor", "mpv", "runtime", "mpv.exe"));
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return Path.of("vendor", "mpv", "runtime", "mpv.exe").toAbsolutePath().normalize();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static final class ConsoleNotifications implements Flow.Subscriber<PlayerNotification> {

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(PlayerNotification notification) {
            if (notification instanceof PlayerNotification.Failure failure) {
                System.out.println("[FAIL] " + failure.code() + ": " + failure.message());
            } else if (notification instanceof PlayerNotification.Diagnostic diagnostic) {
                System.out.println("[INFO] " + diagnostic.code() + ": " + diagnostic.message());
            } else if (notification instanceof PlayerNotification.StateChanged changed) {
                System.out.println("[INFO] Player state: " + changed.state());
            }
        }

        @Override
        public void onError(Throwable throwable) {
            System.out.println("[FAIL] player notification stream: " + throwable.getMessage());
        }

        @Override
        public void onComplete() {
            // Adapter shutdown is expected to complete the notification stream.
        }
    }
}
