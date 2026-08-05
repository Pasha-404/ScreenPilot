package ru.pavelkuzmin.screenpilot.app;

import ru.pavelkuzmin.screenpilot.domain.display.DisplayInfo;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayTopologyChanged;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayMode;
import ru.pavelkuzmin.screenpilot.platform.windows.WindowsDisplayDiscovery;
import ru.pavelkuzmin.screenpilot.platform.windows.WindowsDisplayPoller;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Console-only Stage 3 diagnostic. It deliberately performs no Windows display mutation. */
final class DisplayTopologyProbeMain {

    private DisplayTopologyProbeMain() {
    }

    static int run() {
        try {
            List<DisplayInfo> displays = new WindowsDisplayDiscovery().discoverDisplays();
            System.out.println("[PASS] Read-only Windows display discovery returned " + displays.size() + " path(s).");
            for (DisplayInfo display : displays) {
                System.out.println("[INFO] " + display.friendlyName()
                        + " | id=" + display.id().value()
                        + " | connection=" + display.connectionType()
                        + " | internal=" + display.internal()
                        + " | active=" + display.active()
                        + " | primary=" + display.primary()
                        + " | gdi=" + display.gdiDeviceName()
                        + " | gdiMapping=" + display.gdiMappingConfidence()
                        + " | current=" + formatMode(display.currentMode())
                        + " | preferred=" + formatMode(display.preferredMode())
                        + " | modes=" + display.confirmedModes().size()
                        + " | advancedColor=" + display.advancedColorSupported());
            }
            return 0;
        } catch (Exception exception) {
            System.out.println("[FAIL] Read-only Windows display discovery: " + exception.getMessage());
            return 1;
        }
    }

    static int runPollingSmoke() {
        CountDownLatch firstEvent = new CountDownLatch(1);
        AtomicReference<IOException> failure = new AtomicReference<>();
        try (WindowsDisplayPoller poller = new WindowsDisplayPoller(new WindowsDisplayDiscovery(),
                new WindowsDisplayPoller.Listener() {
                    @Override
                    public void onTopologyChanged(DisplayTopologyChanged event) {
                        System.out.println("[INFO] display-poller observed " + event.displays().size() + " path(s), fingerprint="
                                + event.fingerprint());
                        firstEvent.countDown();
                    }

                    @Override
                    public void onPollingFailure(IOException exception) {
                        failure.compareAndSet(null, exception);
                        firstEvent.countDown();
                    }
                })) {
            poller.start();
            if (!firstEvent.await(3, TimeUnit.SECONDS)) {
                System.out.println("[FAIL] display-poller did not produce an initial topology within 3 seconds.");
                return 1;
            }
            if (failure.get() != null) {
                System.out.println("[FAIL] display-poller: " + failure.get().getMessage());
                return 1;
            }
            System.out.println("[PASS] display-poller published the current topology without changing Windows settings.");
            return 0;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.out.println("[FAIL] display-poller smoke test was interrupted.");
            return 1;
        }
    }

    private static String formatMode(DisplayMode mode) {
        if (mode == null) {
            return "unknown";
        }
        return mode.width() + "x" + mode.height() + "@" + String.format("%.3f", mode.refreshRate().hertz());
    }
}
