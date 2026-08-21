package ru.pavelkuzmin.screenpilot.app;

import java.util.Arrays;

/** Entry point for the technical Stage 1 tools. JavaFX is intentionally not started before the mpv gate passes. */
public final class BootstrapMain {

    private BootstrapMain() {
    }

    public static void main(String[] args) {
        ApplicationPaths.configureLogging();
        if (args.length == 0) {
            ru.pavelkuzmin.screenpilot.app.ui.ScreenPilotApplication.launchApplication(args);
            return;
        }
        if (args.length > 0 && "mpv-spike".equals(args[0])) {
            MpvSpikeMain.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && "display-probe".equals(args[0])) {
            if (DisplayMutationSmokeMain.reportPendingRecovery()) {
                System.exit(2);
            }
            System.exit(DisplayTopologyProbeMain.run());
        }
        if (args.length > 0 && "display-poll-smoke".equals(args[0])) {
            if (DisplayMutationSmokeMain.reportPendingRecovery()) {
                System.exit(2);
            }
            System.exit(DisplayTopologyProbeMain.runPollingSmoke());
        }
        if (args.length > 0 && "player-demo".equals(args[0])) {
            if (DisplayMutationSmokeMain.reportPendingRecovery()) {
                System.exit(2);
            }
            System.exit(MpvPlayerDemoMain.run(Arrays.copyOfRange(args, 1, args.length)));
        }
        if (args.length > 0 && "output-hot-unplug-smoke".equals(args[0])) {
            if (DisplayMutationSmokeMain.reportPendingRecovery()) {
                System.exit(2);
            }
            System.exit(OutputHotUnplugSmokeMain.run(Arrays.copyOfRange(args, 1, args.length)));
        }
        if (args.length > 0 && "display-mode-smoke".equals(args[0])) {
            System.exit(DisplayMutationSmokeMain.runModeSmoke(Arrays.copyOfRange(args, 1, args.length)));
        }
        if (args.length > 0 && "display-extend-smoke".equals(args[0])) {
            System.exit(DisplayMutationSmokeMain.runExtendedTopologySmoke(Arrays.copyOfRange(args, 1, args.length)));
        }
        if (args.length > 0 && "display-mode-list".equals(args[0])) {
            System.exit(DisplayMutationSmokeMain.runModeList(Arrays.copyOfRange(args, 1, args.length)));
        }
        if (args.length > 0 && "display-hot-unplug-watch".equals(args[0])) {
            System.exit(DisplayMutationSmokeMain.runHotUnplugWatch(Arrays.copyOfRange(args, 1, args.length)));
        }
        if (args.length > 0 && "display-recover".equals(args[0])) {
            System.exit(DisplayMutationSmokeMain.runRecovery(Arrays.copyOfRange(args, 1, args.length)));
        }
        if (args.length > 0 && "display-keep-current".equals(args[0])) {
            System.exit(DisplayMutationSmokeMain.runKeepCurrentConfiguration(Arrays.copyOfRange(args, 1, args.length)));
        }
        if (DisplayMutationSmokeMain.reportPendingRecovery()) {
            System.exit(2);
        }
        System.out.println("ScreenPilot technical prototype. Run with: mpv-spike [path-to-mpv.exe] | display-probe | "
                + "display-poll-smoke | player-demo --media=<file> | "
                + "output-hot-unplug-smoke --media=<file> --screen=1 | display-mode-smoke --confirm | "
                + "display-mode-list | display-hot-unplug-watch --hold-ms=30000 | display-extend-smoke --confirm | "
                + "display-recover --confirm");
    }
}
