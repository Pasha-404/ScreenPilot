package ru.pavelkuzmin.screenpilot.app;

import ru.pavelkuzmin.screenpilot.domain.display.DisplayInfo;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayTopologyChanged;
import ru.pavelkuzmin.screenpilot.domain.port.ProcessContainment;
import ru.pavelkuzmin.screenpilot.platform.windows.WindowsDisplayDiscovery;
import ru.pavelkuzmin.screenpilot.platform.windows.WindowsDisplayPoller;
import ru.pavelkuzmin.screenpilot.platform.windows.WindowsProcessContainment;
import ru.pavelkuzmin.screenpilot.player.mpv.MpvPlayerAdapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manual Stage 5 hardware smoke test for a playing mpv session when its selected external output
 * is physically disconnected. It does not change Windows display settings or audio defaults.
 */
final class OutputHotUnplugSmokeMain {

    private static final long DEFAULT_HOLD_MILLIS = 30_000;
    private static final long MAX_HOLD_MILLIS = 600_000;
    private static final Duration START_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration LOAD_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);

    private OutputHotUnplugSmokeMain() {
    }

    static int run(String[] args) {
        Command command;
        try {
            command = Command.parse(args);
        } catch (IllegalArgumentException exception) {
            return failUsage(exception.getMessage());
        }
        if (!Files.isRegularFile(command.media())) {
            return failUsage("Media file was not found: " + command.media());
        }
        if (!Files.isRegularFile(command.mpvExecutable())) {
            return failUsage("mpv.exe was not found: " + command.mpvExecutable());
        }

        try {
            WindowsDisplayDiscovery discovery = new WindowsDisplayDiscovery();
            DisplayInfo target = selectActiveExternalTarget(discovery.discoverDisplays(), command.targetId());
            return runWatchingOutput(command, discovery, target);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.out.println("[FAIL] Output hot-unplug smoke test was interrupted.");
            return 1;
        } catch (Exception exception) {
            System.out.println("[FAIL] Output hot-unplug smoke test: " + exception.getMessage());
            return 1;
        }
    }

    private static int runWatchingOutput(Command command, WindowsDisplayDiscovery discovery, DisplayInfo target)
            throws Exception {
        CountDownLatch initialTopology = new CountDownLatch(1);
        CountDownLatch targetLost = new CountDownLatch(1);
        AtomicBoolean baselineSeen = new AtomicBoolean();
        AtomicReference<IOException> pollingFailure = new AtomicReference<>();
        ProcessContainment containment = isWindows() ? new WindowsProcessContainment() : ProcessContainment.disabled();

        try (MpvPlayerAdapter adapter = new MpvPlayerAdapter(command.mpvExecutable(), containment, command.mpvScreen());
             WindowsDisplayPoller poller = new WindowsDisplayPoller(discovery, new WindowsDisplayPoller.Listener() {
                 @Override
                 public void onTopologyChanged(DisplayTopologyChanged event) {
                     boolean targetPresent = event.displays().stream()
                             .anyMatch(display -> display.targetAddress().equals(target.targetAddress()) && display.active());
                     if (baselineSeen.compareAndSet(false, true)) {
                         if (!targetPresent) {
                             pollingFailure.compareAndSet(null,
                                     new IOException("Selected target disappeared before video output began"));
                         }
                         initialTopology.countDown();
                     } else if (!targetPresent) {
                         targetLost.countDown();
                     }
                 }

                 @Override
                 public void onPollingFailure(IOException exception) {
                     pollingFailure.compareAndSet(null, exception);
                     initialTopology.countDown();
                     targetLost.countDown();
                 }
             })) {
            adapter.start().toCompletableFuture().get(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            adapter.load(command.media(), Duration.ZERO).toCompletableFuture()
                    .get(LOAD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            adapter.pause().toCompletableFuture().get(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            adapter.play().toCompletableFuture().get(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            poller.start();
            if (!initialTopology.await(3, TimeUnit.SECONDS)) {
                System.out.println("[FAIL] Output hot-unplug smoke test did not receive its initial topology within 3 seconds.");
                return 1;
            }
            if (pollingFailure.get() != null) {
                System.out.println("[FAIL] Output hot-unplug smoke test: " + pollingFailure.get().getMessage());
                return 1;
            }

            System.out.println("[INFO] Video is playing on mpv screen " + command.mpvScreen() + ". Disconnect only "
                    + target.gdiDeviceName() + " within " + command.holdMillis() + " ms to test safe shutdown.");
            boolean unplugObserved = targetLost.await(command.holdMillis(), TimeUnit.MILLISECONDS);
            if (pollingFailure.get() != null) {
                System.out.println("[FAIL] Output hot-unplug smoke test: " + pollingFailure.get().getMessage());
                return 1;
            }
            if (!unplugObserved) {
                adapter.shutdown().toCompletableFuture().get(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                System.out.println("[NOT TESTED] The selected target stayed connected; mpv was closed normally.");
                return 0;
            }

            try {
                adapter.pause().toCompletableFuture().get(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception exception) {
                System.out.println("[INFO] mpv could not be paused after the unplug: " + exception.getMessage());
            }
            adapter.shutdown().toCompletableFuture().get(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            System.out.println("[PASS] Selected output was removed; mpv was stopped and its Job Object was released.");
            return 0;
        }
    }

    private static DisplayInfo selectActiveExternalTarget(List<DisplayInfo> displays, String requestedId) {
        List<DisplayInfo> candidates = displays.stream()
                .filter(display -> !display.internal())
                .filter(DisplayInfo::active)
                .filter(display -> !display.primary())
                .filter(display -> !display.gdiDeviceName().isBlank())
                .sorted(Comparator.comparing(display -> display.id().value()))
                .toList();
        if (requestedId != null) {
            return candidates.stream()
                    .filter(display -> display.id().value().equals(requestedId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "The requested active external target was not found: " + requestedId));
        }
        if (candidates.size() != 1) {
            throw new IllegalArgumentException("Exactly one active external display is required; found "
                    + candidates.size() + ". Specify --target=<id> after display-probe.");
        }
        return candidates.getFirst();
    }

    private static int failUsage(String message) {
        System.out.println("[FAIL] " + message);
        System.out.println("[INFO] Usage: output-hot-unplug-smoke --media=<file> --screen=<verified-mpv-screen> "
                + "[--target=<display-id>] [--mpv=<mpv.exe>] [--hold-ms=30000]");
        return 2;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private record Command(Path media, Path mpvExecutable, int mpvScreen, String targetId, long holdMillis) {

        static Command parse(String[] args) {
            Path media = null;
            Path mpvExecutable = null;
            Integer mpvScreen = null;
            String targetId = null;
            long holdMillis = DEFAULT_HOLD_MILLIS;
            for (String argument : args) {
                if (argument.startsWith("--media=")) {
                    media = optionPath(argument, "--media=");
                } else if (argument.startsWith("--mpv=")) {
                    mpvExecutable = optionPath(argument, "--mpv=");
                } else if (argument.startsWith("--screen=")) {
                    try {
                        mpvScreen = Integer.parseInt(value(argument, "--screen="));
                    } catch (NumberFormatException exception) {
                        throw new IllegalArgumentException("--screen must be a non-negative integer");
                    }
                } else if (argument.startsWith("--target=")) {
                    targetId = value(argument, "--target=");
                } else if (argument.startsWith("--hold-ms=")) {
                    try {
                        holdMillis = Long.parseLong(value(argument, "--hold-ms="));
                    } catch (NumberFormatException exception) {
                        throw new IllegalArgumentException("--hold-ms must be an integer");
                    }
                } else {
                    throw new IllegalArgumentException("Unknown option: " + argument);
                }
            }
            if (media == null) {
                throw new IllegalArgumentException("--media is required");
            }
            if (mpvScreen == null || mpvScreen < 0) {
                throw new IllegalArgumentException("--screen must be a non-negative integer");
            }
            if (holdMillis < 0 || holdMillis > MAX_HOLD_MILLIS) {
                throw new IllegalArgumentException("--hold-ms must be between 0 and " + MAX_HOLD_MILLIS);
            }
            return new Command(media, mpvExecutable == null ? findMpvExecutable() : mpvExecutable,
                    mpvScreen, targetId, holdMillis);
        }

        private static Path optionPath(String argument, String prefix) {
            return Path.of(value(argument, prefix)).toAbsolutePath().normalize();
        }

        private static String value(String argument, String prefix) {
            String value = argument.substring(prefix.length());
            if (value.isBlank()) {
                throw new IllegalArgumentException(prefix + " must have a value");
            }
            return value;
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
    }
}
