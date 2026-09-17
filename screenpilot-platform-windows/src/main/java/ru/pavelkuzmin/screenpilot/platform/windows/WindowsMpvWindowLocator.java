package ru.pavelkuzmin.screenpilot.platform.windows;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayBounds;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Locates the mpv window owned by one process and places it in physical Win32 desktop coordinates.
 *
 * <p>The display topology API also reports physical virtual-desktop coordinates. The locator temporarily
 * uses {@code PER_MONITOR_AWARE_V2} on its worker thread, so it neither mixes those values with JavaFX
 * logical coordinates nor accepts a window merely because its centre happens to be on the target.</p>
 */
public final class WindowsMpvWindowLocator {

    private static final Duration RETRY_INTERVAL = Duration.ofMillis(100);
    private static final Pointer DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = Pointer.createConstant(-4);

    /**
     * Moves the unique output window to exactly cover the selected physical target and reads the resulting
     * bounds back. The process id prevents a title collision from being accepted as ScreenPilot output.
     */
    public boolean placeAndVerifyWindowOn(
            String windowTitle,
            long expectedProcessId,
            DisplayBounds target,
            Duration timeout
    ) throws IOException {
        requireWindows();
        if (windowTitle == null || windowTitle.isBlank()) {
            throw new IllegalArgumentException("windowTitle must not be blank");
        }
        if (expectedProcessId <= 0) {
            throw new IllegalArgumentException("expectedProcessId must be positive");
        }
        target = Objects.requireNonNull(target, "target");
        timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        DisplayBounds requiredTarget = target;
        Duration requiredTimeout = timeout;
        return inPerMonitorV2(() -> placeAndVerifyWindowOnPhysical(
                windowTitle, expectedProcessId, requiredTarget, requiredTimeout));
    }

    private static boolean placeAndVerifyWindowOnPhysical(
            String windowTitle,
            long expectedProcessId,
            DisplayBounds target,
            Duration timeout
    ) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            Optional<OwnedWindow> candidate = visibleOwnedWindow(windowTitle, expectedProcessId);
            if (candidate.isPresent()) {
                HWND window = candidate.orElseThrow().handle();
                if (!User32.INSTANCE.SetWindowPos(
                        window,
                        null,
                        target.x(),
                        target.y(),
                        target.width(),
                        target.height(),
                        WinUser.SWP_NOACTIVATE | WinUser.SWP_NOOWNERZORDER | WinUser.SWP_NOZORDER | WinUser.SWP_SHOWWINDOW
                )) {
                    throw new IOException("SetWindowPos failed while placing the mpv window on the selected target");
                }
                Optional<DisplayBounds> bounds = visibleWindowBounds(window);
                if (bounds.isPresent() && exactlyMatches(bounds.orElseThrow(), target)) {
                    return true;
                }
            }
            try {
                Thread.sleep(RETRY_INTERVAL.toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for the mpv window", exception);
            }
        } while (System.nanoTime() < deadline);
        return false;
    }

    static boolean exactlyMatches(DisplayBounds window, DisplayBounds target) {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(target, "target");
        return window.equals(target);
    }

    private static Optional<OwnedWindow> visibleOwnedWindow(String title, long expectedProcessId) throws IOException {
        HWND window = User32.INSTANCE.FindWindow(null, title);
        if (window == null || window.getPointer() == null || !User32.INSTANCE.IsWindowVisible(window)) {
            return Optional.empty();
        }
        IntByReference processId = new IntByReference();
        int windowThread = User32.INSTANCE.GetWindowThreadProcessId(window, processId);
        if (windowThread == 0) {
            throw new IOException("GetWindowThreadProcessId failed for mpv window");
        }
        return Integer.toUnsignedLong(processId.getValue()) == expectedProcessId
                ? Optional.of(new OwnedWindow(window))
                : Optional.empty();
    }

    private static Optional<DisplayBounds> visibleWindowBounds(HWND window) throws IOException {
        if (window == null || window.getPointer() == null || !User32.INSTANCE.IsWindowVisible(window)) {
            return Optional.empty();
        }
        RECT rectangle = new RECT();
        if (!User32.INSTANCE.GetWindowRect(window, rectangle)) {
            throw new IOException("GetWindowRect failed for mpv window");
        }
        return Optional.of(new DisplayBounds(
                rectangle.left,
                rectangle.top,
                rectangle.right - rectangle.left,
                rectangle.bottom - rectangle.top
        ));
    }

    private static <T> T inPerMonitorV2(NativeCall<T> call) throws IOException {
        Pointer previousContext = DpiAwarenessUser32.INSTANCE.SetThreadDpiAwarenessContext(
                DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
        if (previousContext == null) {
            throw new IOException("SetThreadDpiAwarenessContext(PER_MONITOR_AWARE_V2) failed");
        }
        try {
            return call.run();
        } finally {
            if (DpiAwarenessUser32.INSTANCE.SetThreadDpiAwarenessContext(previousContext) == null) {
                throw new IOException("Could not restore the prior thread DPI awareness context");
            }
        }
    }

    @FunctionalInterface
    private interface NativeCall<T> {
        T run() throws IOException;
    }

    private record OwnedWindow(HWND handle) {
    }

    private interface DpiAwarenessUser32 extends StdCallLibrary {
        DpiAwarenessUser32 INSTANCE = Native.load("user32", DpiAwarenessUser32.class, W32APIOptions.DEFAULT_OPTIONS);

        Pointer SetThreadDpiAwarenessContext(Pointer dpiContext);
    }

    private static void requireWindows() {
        if (!Platform.isWindows()) {
            throw new UnsupportedOperationException("mpv window placement verification is available only on Windows");
        }
    }
}
