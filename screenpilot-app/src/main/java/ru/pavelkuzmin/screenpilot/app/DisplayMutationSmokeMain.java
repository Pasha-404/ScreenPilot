package ru.pavelkuzmin.screenpilot.app;

import ru.pavelkuzmin.screenpilot.domain.display.DisplayInfo;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayTopologyChanged;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayMode;
import ru.pavelkuzmin.screenpilot.domain.port.RecoveryJournal;
import ru.pavelkuzmin.screenpilot.domain.recovery.RecoveryRecord;
import ru.pavelkuzmin.screenpilot.persistence.FileRecoveryJournal;
import ru.pavelkuzmin.screenpilot.platform.windows.WindowsDisplayDiscovery;
import ru.pavelkuzmin.screenpilot.platform.windows.WindowsDisplayMutator;
import ru.pavelkuzmin.screenpilot.platform.windows.WindowsDisplayPoller;
import ru.pavelkuzmin.screenpilot.platform.windows.WindowsDisplaySnapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Explicit, console-only hardware checks for Stage 5.
 * They are deliberately separate from JUnit so a normal build can never change Windows display settings.
 */
final class DisplayMutationSmokeMain {

    private static final int EXPECTED_HALT_EXIT_CODE = 86;
    private static final long DEFAULT_HOLD_MILLIS = 2_000;
    private static final long MAX_HOLD_MILLIS = 30_000;

    private DisplayMutationSmokeMain() {
    }

    static int runModeSmoke(String[] args) {
        Command command;
        try {
            command = Command.parse(args);
            command.requireConfirmation();
        } catch (IllegalArgumentException exception) {
            return failUsage(exception.getMessage());
        }

        RecoveryJournal journal = new FileRecoveryJournal(applicationDataDirectory());
        if (reportPendingRecovery(journal)) {
            return 2;
        }

        try {
            WindowsDisplayDiscovery discovery = new WindowsDisplayDiscovery();
            DisplayInfo target = selectTarget(discovery.discoverDisplays(), command.targetId(), false);
            WindowsDisplayMutator mutator = new WindowsDisplayMutator();
            System.out.println("[INFO] Selected external target " + target.friendlyName()
                    + " | id=" + target.id().value()
                    + " | gdi=" + target.gdiDeviceName()
                    + " | mapping=" + target.gdiMappingConfidence());

            WindowsDisplaySnapshot snapshot = mutator.captureSnapshot(target, true);
            RecoveryRecord record = RecoveryRecord.begin(UUID.randomUUID(), Instant.now(), snapshot.toRecoveryPayload());
            journal.begin(record);
            System.out.println("[INFO] Recovery snapshot saved before changing Windows. Session=" + record.sessionId());

            boolean restored = false;
            try {
                DisplayInfo activeTarget = mutator.ensureExtendedTopology(target, true);
                DisplayMode requested = selectRequestedMode(activeTarget, command.mode());
                System.out.println("[INFO] Active target after topology preparation: " + activeTarget.gdiDeviceName()
                        + " | requested=" + formatMode(requested));
                DisplayMode actual = mutator.applyTemporaryMode(snapshot, activeTarget, requested);
                System.out.println("[PASS] Temporary target mode was applied and read back as " + formatMode(actual) + ".");
                if (command.simulateCrash()) {
                    System.out.println("[INFO] Simulating abrupt termination. The recovery journal is intentionally left active; "
                            + "run display-recover --confirm next.");
                    Runtime.getRuntime().halt(EXPECTED_HALT_EXIT_CODE);
                }
                waitWhileUserObserves(target, command.holdMillis());
            } finally {
                try {
                    mutator.restore(snapshot);
                    journal.markRestored(record.sessionId());
                    restored = true;
                    System.out.println("[PASS] Original display configuration was restored and recovery journal closed.");
                } catch (Exception restoreFailure) {
                    System.out.println("[FAIL] Display recovery did not complete: " + restoreFailure.getMessage());
                    System.out.println("[INFO] The recovery journal was kept. Use Win+P or Windows Display settings if needed, "
                            + "then run display-recover --confirm.");
                }
            }
            return restored ? 0 : 1;
        } catch (Exception exception) {
            System.out.println("[FAIL] Display mode smoke check: " + exception.getMessage());
            return 1;
        }
    }

    static int runExtendedTopologySmoke(String[] args) {
        Command command;
        try {
            command = Command.parse(args);
            command.requireConfirmation();
        } catch (IllegalArgumentException exception) {
            return failUsage(exception.getMessage());
        }
        RecoveryJournal journal = new FileRecoveryJournal(applicationDataDirectory());
        if (reportPendingRecovery(journal)) {
            return 2;
        }
        try {
            WindowsDisplayDiscovery discovery = new WindowsDisplayDiscovery();
            DisplayInfo target = selectTarget(discovery.discoverDisplays(), command.targetId(), false);
            WindowsDisplayMutator mutator = new WindowsDisplayMutator();
            WindowsDisplaySnapshot snapshot = mutator.captureSnapshot(target, true);
            RecoveryRecord record = RecoveryRecord.begin(UUID.randomUUID(), Instant.now(), snapshot.toRecoveryPayload());
            journal.begin(record);
            System.out.println("[INFO] Recovery snapshot saved before checking temporary Extend topology. Session="
                    + record.sessionId());
            boolean restored = false;
            try {
                DisplayInfo activeTarget = mutator.ensureExtendedTopology(target, true);
                System.out.println("[PASS] Windows reports an active, separate internal and external desktop: "
                        + activeTarget.gdiDeviceName() + ".");
                waitWhileUserObserves(activeTarget, command.holdMillis());
            } finally {
                try {
                    mutator.restore(snapshot);
                    journal.markRestored(record.sessionId());
                    restored = true;
                    System.out.println("[PASS] Original display configuration was restored and recovery journal closed.");
                } catch (Exception restoreFailure) {
                    System.out.println("[FAIL] Display recovery did not complete: " + restoreFailure.getMessage());
                    System.out.println("[INFO] The recovery journal was kept. Use Win+P or Windows Display settings if needed, "
                            + "then run display-recover --confirm.");
                }
            }
            return restored ? 0 : 1;
        } catch (Exception exception) {
            System.out.println("[FAIL] Extended topology smoke check: " + exception.getMessage());
            return 1;
        }
    }

    static int runModeList(String[] args) {
        Command command;
        try {
            command = Command.parse(args);
            if (command.mode() != null || command.simulateCrash() || command.confirmed()) {
                throw new IllegalArgumentException("display-mode-list accepts only the optional --target=<id>");
            }
        } catch (IllegalArgumentException exception) {
            return failUsage(exception.getMessage());
        }
        if (reportPendingRecovery()) {
            return 2;
        }
        try {
            DisplayInfo target = selectTarget(new WindowsDisplayDiscovery().discoverDisplays(), command.targetId(), true);
            System.out.println("[PASS] Confirmed driver modes for " + target.friendlyName() + " | id=" + target.id().value());
            target.confirmedModes().forEach(mode -> System.out.println("[INFO] " + formatMode(mode)
                    + (mode.interlaced() ? " interlaced" : "")));
            return 0;
        } catch (Exception exception) {
            System.out.println("[FAIL] Display mode list: " + exception.getMessage());
            return 1;
        }
    }

    /** Read-only unplug check for the Stage 5 poller; it never applies a display configuration. */
    static int runHotUnplugWatch(String[] args) {
        Command command;
        try {
            command = Command.parse(args);
            if (command.mode() != null || command.simulateCrash() || command.confirmed()) {
                throw new IllegalArgumentException("display-hot-unplug-watch accepts only --target=<id> and --hold-ms=<1000..30000>");
            }
            if (command.holdMillis() < WindowsDisplayPoller.DEFAULT_INTERVAL.toMillis()) {
                throw new IllegalArgumentException("display-hot-unplug-watch requires --hold-ms of at least 1000");
            }
        } catch (IllegalArgumentException exception) {
            return failUsage(exception.getMessage());
        }
        if (reportPendingRecovery()) {
            return 2;
        }
        try {
            WindowsDisplayDiscovery discovery = new WindowsDisplayDiscovery();
            DisplayInfo target = selectTarget(discovery.discoverDisplays(), command.targetId(), true);
            CountDownLatch initialTopology = new CountDownLatch(1);
            CountDownLatch targetLost = new CountDownLatch(1);
            AtomicBoolean baselineSeen = new AtomicBoolean();
            AtomicReference<IOException> pollingFailure = new AtomicReference<>();
            try (WindowsDisplayPoller poller = new WindowsDisplayPoller(discovery, new WindowsDisplayPoller.Listener() {
                @Override
                public void onTopologyChanged(DisplayTopologyChanged event) {
                    boolean present = event.displays().stream().anyMatch(display -> display.targetAddress().equals(target.targetAddress())
                            && display.active());
                    if (baselineSeen.compareAndSet(false, true)) {
                        if (!present) {
                            pollingFailure.compareAndSet(null, new IOException("Selected target disappeared before the watch began"));
                        }
                        initialTopology.countDown();
                        return;
                    }
                    if (!present) {
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
                poller.start();
                if (!initialTopology.await(3, TimeUnit.SECONDS)) {
                    System.out.println("[FAIL] Hot-unplug watch did not receive its initial topology within 3 seconds.");
                    return 1;
                }
                if (pollingFailure.get() != null) {
                    System.out.println("[FAIL] Hot-unplug watch: " + pollingFailure.get().getMessage());
                    return 1;
                }
                System.out.println("[INFO] Hot-unplug watch is active for " + command.holdMillis()
                        + " ms. Disconnect only " + target.gdiDeviceName() + " now if you intend to test it.");
                boolean observed = targetLost.await(command.holdMillis(), TimeUnit.MILLISECONDS);
                if (pollingFailure.get() != null) {
                    System.out.println("[FAIL] Hot-unplug watch: " + pollingFailure.get().getMessage());
                    return 1;
                }
                if (observed) {
                    System.out.println("[PASS] display-poller observed selected target removal on its one-second polling interval.");
                } else {
                    System.out.println("[NOT TESTED] Selected target was not disconnected during this read-only watch.");
                }
                return 0;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.out.println("[FAIL] Hot-unplug watch was interrupted.");
            return 1;
        } catch (Exception exception) {
            System.out.println("[FAIL] Hot-unplug watch: " + exception.getMessage());
            return 1;
        }
    }

    static int runRecovery(String[] args) {
        try {
            Command.parse(args).requireConfirmation();
        } catch (IllegalArgumentException exception) {
            return failUsage(exception.getMessage());
        }
        RecoveryJournal journal = new FileRecoveryJournal(applicationDataDirectory());
        Optional<RecoveryRecord> record = journal.findUnfinished();
        if (record.isEmpty()) {
            System.out.println("[PASS] No unfinished display recovery journal exists.");
            return 0;
        }
        try {
            WindowsDisplayMutator mutator = new WindowsDisplayMutator();
            mutator.restore(mutator.snapshotFrom(record.orElseThrow()));
            journal.markRestored(record.orElseThrow().sessionId());
            System.out.println("[PASS] Pending display recovery succeeded and the journal was closed.");
            return 0;
        } catch (Exception exception) {
            System.out.println("[FAIL] Pending display recovery: " + exception.getMessage());
            System.out.println("[INFO] The recovery journal remains active. Use Win+P or Windows Display settings, then retry.");
            return 1;
        }
    }

    static int runKeepCurrentConfiguration(String[] args) {
        try {
            Command.parse(args).requireConfirmation();
        } catch (IllegalArgumentException exception) {
            return failUsage(exception.getMessage());
        }
        RecoveryJournal journal = new FileRecoveryJournal(applicationDataDirectory());
        Optional<RecoveryRecord> record = journal.findUnfinished();
        if (record.isEmpty()) {
            System.out.println("[PASS] No unfinished display recovery journal exists.");
            return 0;
        }
        journal.markLeftAsIs(record.orElseThrow().sessionId());
        System.out.println("[PASS] Current Windows display configuration was kept; the journal was archived for seven days.");
        return 0;
    }

    static boolean reportPendingRecovery() {
        return reportPendingRecovery(new FileRecoveryJournal(applicationDataDirectory()));
    }

    private static boolean reportPendingRecovery(RecoveryJournal journal) {
        Optional<RecoveryRecord> record = journal.findUnfinished();
        if (record.isEmpty()) {
            return false;
        }
        System.out.println("[BLOCKED] Предыдущая сессия завершилась до восстановления экранов. "
                + "Восстановить: display-recover --confirm; оставить как есть: display-keep-current --confirm; "
                + "подробности: %LOCALAPPDATA%\\ScreenPilot\\recovery\\display-session.json.");
        return true;
    }

    private static DisplayInfo selectTarget(
            List<DisplayInfo> displays,
            String requestedId,
            boolean requireIndependentActiveTarget
    ) {
        List<DisplayInfo> candidates = displays.stream()
                .filter(display -> !display.internal())
                .filter(display -> display.active() || display.targetAvailable())
                .filter(display -> !requireIndependentActiveTarget || display.active())
                .filter(display -> !requireIndependentActiveTarget || !display.primary())
                .filter(display -> !requireIndependentActiveTarget || display.currentMode() != null)
                .filter(display -> !requireIndependentActiveTarget || !display.gdiDeviceName().isBlank())
                .sorted(Comparator.comparing(display -> display.id().value()))
                .toList();
        if (requestedId != null) {
            return candidates.stream()
                    .filter(display -> display.id().value().equals(requestedId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("The requested available external target was not found: " + requestedId));
        }
        if (candidates.size() != 1) {
            throw new IllegalArgumentException("Exactly one available external display is required; found "
                    + candidates.size() + ". Specify --target=<id> after display-probe.");
        }
        return candidates.getFirst();
    }

    private static DisplayMode selectRequestedMode(DisplayInfo target, String requestedMode) {
        if (requestedMode == null) {
            if (target.currentMode().refreshRate().numerator() % target.currentMode().refreshRate().denominator() != 0) {
                throw new IllegalArgumentException("The current target frequency is fractional and cannot be re-applied "
                        + "through DEVMODE without rounding. Run display-mode-list and pass an explicit confirmed --mode.");
            }
            return target.currentMode();
        }
        RequestedMode parsed = RequestedMode.parse(requestedMode);
        return target.confirmedModes().stream()
                .filter(mode -> mode.width() == parsed.width() && mode.height() == parsed.height())
                .filter(mode -> !mode.interlaced())
                .filter(mode -> Math.abs(mode.refreshRate().hertz() - parsed.refreshHertz()) <= 0.02)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("The requested mode is not a confirmed target mode: " + requestedMode));
    }

    private static void waitWhileUserObserves(DisplayInfo target, long holdMillis) throws IOException {
        if (holdMillis == 0) {
            return;
        }
        System.out.println("[INFO] Keeping the test state for " + holdMillis + " ms. Observe " + target.gdiDeviceName() + ".");
        try {
            Thread.sleep(holdMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the hardware observation", exception);
        }
    }

    private static int failUsage(String message) {
        System.out.println("[FAIL] " + message);
        System.out.println("[INFO] Usage: display-mode-smoke --confirm [--target=<id>] [--mode=<width>x<height>@<Hz>] "
                + "[--hold-ms=2000] [--simulate-crash]");
        return 2;
    }

    private static Path applicationDataDirectory() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData).resolve("ScreenPilot");
        }
        return Path.of(System.getProperty("user.home"), "AppData", "Local", "ScreenPilot");
    }

    private static String formatMode(DisplayMode mode) {
        return mode.width() + "x" + mode.height() + "@" + String.format(Locale.ROOT, "%.3f", mode.refreshRate().hertz());
    }

    private record Command(boolean confirmed, String targetId, String mode, long holdMillis, boolean simulateCrash) {
        static Command parse(String[] args) {
            boolean confirmed = false;
            boolean simulateCrash = false;
            String targetId = null;
            String mode = null;
            long holdMillis = DEFAULT_HOLD_MILLIS;
            for (String argument : args) {
                if ("--confirm".equals(argument)) {
                    confirmed = true;
                } else if ("--simulate-crash".equals(argument)) {
                    simulateCrash = true;
                } else if (argument.startsWith("--target=")) {
                    targetId = value(argument, "--target=");
                } else if (argument.startsWith("--mode=")) {
                    mode = value(argument, "--mode=");
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
            if (holdMillis < 0 || holdMillis > MAX_HOLD_MILLIS) {
                throw new IllegalArgumentException("--hold-ms must be between 0 and " + MAX_HOLD_MILLIS);
            }
            if (simulateCrash && holdMillis != 0) {
                throw new IllegalArgumentException("--simulate-crash requires --hold-ms=0 so recovery can start immediately");
            }
            return new Command(confirmed, targetId, mode, holdMillis, simulateCrash);
        }

        void requireConfirmation() {
            if (!confirmed) {
                throw new IllegalArgumentException("This command changes or closes display recovery state; pass --confirm after reviewing display-probe");
            }
        }

        private static String value(String argument, String prefix) {
            String value = argument.substring(prefix.length());
            if (value.isBlank()) {
                throw new IllegalArgumentException(prefix + " must have a value");
            }
            return value;
        }
    }

    private record RequestedMode(int width, int height, double refreshHertz) {
        static RequestedMode parse(String value) {
            String normalized = value.toLowerCase(Locale.ROOT);
            int by = normalized.indexOf('x');
            int at = normalized.indexOf('@');
            if (by < 1 || at <= by + 1 || at == normalized.length() - 1) {
                throw new IllegalArgumentException("Mode must have the form <width>x<height>@<Hz>");
            }
            try {
                int width = Integer.parseInt(normalized.substring(0, by));
                int height = Integer.parseInt(normalized.substring(by + 1, at));
                double refresh = Double.parseDouble(normalized.substring(at + 1));
                if (width < 640 || height < 480 || !Double.isFinite(refresh) || refresh <= 0) {
                    throw new IllegalArgumentException("Mode has invalid dimensions or refresh rate");
                }
                return new RequestedMode(width, height, refresh);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Mode must contain numeric width, height and refresh rate");
            }
        }
    }
}
