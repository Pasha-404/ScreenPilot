package ru.pavelkuzmin.screenpilot.platform.windows;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayBounds;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Locates one uniquely titled mpv top-level window and verifies its centre against a physical target. */
public final class WindowsMpvWindowLocator {

    private static final Duration RETRY_INTERVAL = Duration.ofMillis(100);

    public boolean waitForWindowCenteredOn(String windowTitle, DisplayBounds target, Duration timeout) throws IOException {
        requireWindows();
        if (windowTitle == null || windowTitle.isBlank()) {
            throw new IllegalArgumentException("windowTitle must not be blank");
        }
        target = Objects.requireNonNull(target, "target");
        timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            Optional<DisplayBounds> bounds = visibleWindowBounds(windowTitle);
            if (bounds.isPresent() && centerIsInside(bounds.orElseThrow(), target)) {
                return true;
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

    static boolean centerIsInside(DisplayBounds window, DisplayBounds target) {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(target, "target");
        long centerX2 = 2L * window.x() + window.width();
        long centerY2 = 2L * window.y() + window.height();
        return centerX2 >= 2L * target.x()
                && centerX2 < 2L * (target.x() + target.width())
                && centerY2 >= 2L * target.y()
                && centerY2 < 2L * (target.y() + target.height());
    }

    private static Optional<DisplayBounds> visibleWindowBounds(String title) throws IOException {
        HWND window = User32.INSTANCE.FindWindow(null, title);
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

    private static void requireWindows() {
        if (!Platform.isWindows()) {
            throw new UnsupportedOperationException("mpv window placement verification is available only on Windows");
        }
    }
}
