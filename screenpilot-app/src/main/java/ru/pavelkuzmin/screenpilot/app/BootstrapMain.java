package ru.pavelkuzmin.screenpilot.app;

import java.util.Arrays;

/** Entry point for the technical Stage 1 tools. JavaFX is intentionally not started before the mpv gate passes. */
public final class BootstrapMain {

    private BootstrapMain() {
    }

    public static void main(String[] args) {
        if (args.length > 0 && "mpv-spike".equals(args[0])) {
            MpvSpikeMain.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && "display-probe".equals(args[0])) {
            System.exit(DisplayTopologyProbeMain.run());
        }
        if (args.length > 0 && "display-poll-smoke".equals(args[0])) {
            System.exit(DisplayTopologyProbeMain.runPollingSmoke());
        }
        System.out.println("ScreenPilot technical prototype. Run with: mpv-spike [path-to-mpv.exe] | display-probe | display-poll-smoke");
    }
}
