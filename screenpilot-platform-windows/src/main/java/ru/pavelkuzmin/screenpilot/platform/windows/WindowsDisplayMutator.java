package ru.pavelkuzmin.screenpilot.platform.windows;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayInfo;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayMode;
import ru.pavelkuzmin.screenpilot.domain.display.GdiMappingConfidence;
import ru.pavelkuzmin.screenpilot.domain.recovery.RecoveryRecord;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Windows-only, explicitly temporary display mutation with snapshot-based rollback.
 * Callers must create and persist a {@link RecoveryRecord} before invoking a mutating method.
 */
public final class WindowsDisplayMutator {

    private static final int QDC_ONLY_ACTIVE_PATHS = 0x0000_0002;
    private static final int QDC_DATABASE_CURRENT = 0x0000_0004;
    private static final int QDC_VIRTUAL_MODE_AWARE = 0x0000_0010;
    private static final int ERROR_SUCCESS = 0;
    private static final int ERROR_INSUFFICIENT_BUFFER = 122;
    private static final int SDC_TOPOLOGY_EXTEND = 0x0000_0004;
    private static final int SDC_USE_SUPPLIED_DISPLAY_CONFIG = 0x0000_0020;
    private static final int SDC_VALIDATE = 0x0000_0040;
    private static final int SDC_APPLY = 0x0000_0080;
    private static final int SDC_USE_DATABASE_CURRENT = 0x0000_0400;
    private static final int SDC_VIRTUAL_MODE_AWARE = 0x0000_8000;
    private static final int CDS_TEST = 0x0000_0002;
    private static final int DISP_CHANGE_SUCCESSFUL = 0;
    private static final int DM_BITSPERPEL = 0x0004_0000;
    private static final int DM_PELSWIDTH = 0x0008_0000;
    private static final int DM_PELSHEIGHT = 0x0010_0000;
    private static final int DM_DISPLAYFLAGS = 0x0020_0000;
    private static final int DM_DISPLAYFREQUENCY = 0x0040_0000;
    private static final int DM_INTERLACED = 0x0000_0002;
    private static final int ENUM_CURRENT_SETTINGS = -1;
    private static final int MAX_QUERY_RETRIES = 3;
    private static final Duration TOPOLOGY_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration TOPOLOGY_POLL_INTERVAL = Duration.ofMillis(250);
    private static final double REFRESH_TOLERANCE_HERTZ = 0.02;

    private final User32DisplayMutationApi user32;
    private final WindowsDisplayDiscovery discovery;

    public WindowsDisplayMutator() {
        this(User32DisplayMutationApi.INSTANCE, new WindowsDisplayDiscovery());
    }

    WindowsDisplayMutator(User32DisplayMutationApi user32, WindowsDisplayDiscovery discovery) {
        this.user32 = Objects.requireNonNull(user32, "user32");
        this.discovery = Objects.requireNonNull(discovery, "discovery");
    }

    /** Captures all active DisplayConfig paths/modes before mutation, including an inactive chosen target. */
    public WindowsDisplaySnapshot captureSnapshot(DisplayInfo target, boolean targetWasExplicitlyConfirmed) throws IOException {
        requireSelectableExternalTarget(target, targetWasExplicitlyConfirmed);
        NativeTopology topology = queryActiveTopology();
        List<DisplayInfo> displays = discovery.discoverDisplays();
        List<WindowsDisplaySnapshot.SourceDevMode> sourceModes = new ArrayList<>();
        Set<String> capturedGdiSources = new LinkedHashSet<>();
        for (DisplayInfo display : displays) {
            if (display.active() && !display.gdiDeviceName().isBlank()
                    && capturedGdiSources.add(display.gdiDeviceName().toUpperCase(java.util.Locale.ROOT))) {
                sourceModes.add(new WindowsDisplaySnapshot.SourceDevMode(
                        display.gdiDeviceName(), readCurrentDevModeBytes(display.gdiDeviceName())));
            }
        }
        boolean targetWasIndependent = isUsableExtendedTarget(target, displays);
        return new WindowsDisplaySnapshot(
                discovery.currentTopologyFingerprint(),
                topology.topologyId(),
                targetWasIndependent ? target.gdiDeviceName() : "",
                target.targetAddress(),
                targetWasIndependent ? target.currentMode() : null,
                topology.pathCount(),
                topology.pathBytes(),
                topology.modeCount(),
                topology.modeBytes(),
                sourceModes
        );
    }

    /**
     * Converts the current desktop to temporary extended topology only after an explicit caller confirmation.
     * It intentionally never uses SDC_SAVE_TO_DATABASE.
     */
    public DisplayInfo ensureExtendedTopology(DisplayInfo target, boolean confirmedByUser) throws IOException {
        requireSelectableExternalTarget(target, confirmedByUser);
        if (isUsableExtendedTarget(target, discovery.discoverDisplays())) {
            return target;
        }
        if (!confirmedByUser) {
            throw new IOException("Switching Windows to extended desktop requires explicit confirmation");
        }
        int status = user32.SetDisplayConfig(0, null, 0, null, SDC_APPLY | SDC_TOPOLOGY_EXTEND);
        requireSuccess("SetDisplayConfig(SDC_TOPOLOGY_EXTEND)", status);
        return waitForTopology(target.targetAddress(), "", null, true);
    }

    /** Tests, applies and verifies a temporary target-only mode. On any post-apply error it restores the snapshot. */
    public DisplayMode applyTemporaryMode(
            WindowsDisplaySnapshot snapshot,
            DisplayInfo activeTarget,
            DisplayMode requested
    ) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(activeTarget, "activeTarget");
        Objects.requireNonNull(requested, "requested");
        requireIndependentActiveTarget(activeTarget, true);
        WindowsDisplayDiscovery.DevModeW candidate = createRequestedMode(activeTarget.gdiDeviceName(), requested);
        int testStatus = user32.ChangeDisplaySettingsExW(activeTarget.gdiDeviceName(), candidate, null, CDS_TEST, null);
        if (testStatus != DISP_CHANGE_SUCCESSFUL) {
            throw new IOException("ChangeDisplaySettingsExW(CDS_TEST) rejected the requested mode with code " + testStatus);
        }

        int applyStatus = user32.ChangeDisplaySettingsExW(activeTarget.gdiDeviceName(), candidate, null, 0, null);
        if (applyStatus != DISP_CHANGE_SUCCESSFUL) {
            throw new IOException("ChangeDisplaySettingsExW could not apply the requested mode, code " + applyStatus);
        }
        try {
            return waitForTopology(activeTarget.targetAddress(), activeTarget.gdiDeviceName(), requested, false).currentMode();
        } catch (IOException verificationFailure) {
            try {
                restore(snapshot);
            } catch (IOException rollbackFailure) {
                verificationFailure.addSuppressed(rollbackFailure);
            }
            throw verificationFailure;
        }
    }

    /** Restores the supplied topology first; if it is no longer valid, returns to Windows database current. */
    public void restore(WindowsDisplaySnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        requireWindows();
        int validation = setSuppliedTopology(snapshot, SDC_VALIDATE);
        boolean restoredFromSnapshot = validation == ERROR_SUCCESS
                && setSuppliedTopology(snapshot, SDC_APPLY) == ERROR_SUCCESS;
        if (!restoredFromSnapshot) {
            int fallback = user32.SetDisplayConfig(0, null, 0, null, SDC_APPLY | SDC_USE_DATABASE_CURRENT);
            requireSuccess("SetDisplayConfig(SDC_USE_DATABASE_CURRENT)", fallback);
        }
        if (snapshot.hasOriginalTargetMode()) {
            restoreTargetDevMode(snapshot);
            waitForTopology(snapshot.targetAddress(), snapshot.targetGdiDeviceName(), snapshot.targetMode(), false);
        } else {
            waitForOriginalTopologyFingerprint(snapshot);
        }
    }

    public WindowsDisplaySnapshot snapshotFrom(RecoveryRecord record) {
        Objects.requireNonNull(record, "record");
        if (!record.hasValidChecksum()) {
            throw new IllegalArgumentException("Recovery record checksum is invalid");
        }
        return WindowsDisplaySnapshot.fromRecoveryPayload(record.topologyPayload());
    }

    private int setSuppliedTopology(WindowsDisplaySnapshot snapshot, int action) {
        Memory paths = new Memory(snapshot.pathBytes().length);
        paths.write(0, snapshot.pathBytes(), 0, snapshot.pathBytes().length);
        Memory modes = snapshot.modeCount() == 0 ? null : new Memory(snapshot.modeBytes().length);
        if (modes != null) {
            modes.write(0, snapshot.modeBytes(), 0, snapshot.modeBytes().length);
        }
        return user32.SetDisplayConfig(snapshot.pathCount(), paths, snapshot.modeCount(), modes,
                action | SDC_USE_SUPPLIED_DISPLAY_CONFIG | SDC_VIRTUAL_MODE_AWARE);
    }

    private void restoreTargetDevMode(WindowsDisplaySnapshot snapshot) throws IOException {
        WindowsDisplaySnapshot.SourceDevMode original = snapshot.activeSourceModes().stream()
                .filter(source -> source.gdiDeviceName().equalsIgnoreCase(snapshot.targetGdiDeviceName()))
                .findFirst()
                .orElseThrow(() -> new IOException("Snapshot has no original DEVMODE for " + snapshot.targetGdiDeviceName()));
        WindowsDisplayDiscovery.DevModeW devMode = fromBytes(original.devModeBytes());
        int result = user32.ChangeDisplaySettingsExW(snapshot.targetGdiDeviceName(), devMode, null, 0, null);
        if (result != DISP_CHANGE_SUCCESSFUL) {
            throw new IOException("ChangeDisplaySettingsExW could not restore target mode, code " + result);
        }
    }

    private DisplayInfo waitForTopology(
            ru.pavelkuzmin.screenpilot.domain.display.DisplayTargetAddress targetAddress,
            String targetGdi,
            DisplayMode expectedMode,
            boolean requireExtended
    ) throws IOException {
        long deadline = System.nanoTime() + TOPOLOGY_TIMEOUT.toNanos();
        IOException lastFailure = null;
        String lastObservation = "no display snapshot was returned";
        do {
            try {
                List<DisplayInfo> displays = discovery.discoverDisplays();
                DisplayInfo actual = displays.stream()
                        .filter(display -> display.targetAddress().equals(targetAddress))
                        .findFirst()
                        .orElse(null);
                if (actual != null && actual.active() && actual.currentMode() != null
                        && (targetGdi == null || targetGdi.isBlank()
                        || actual.gdiDeviceName().equalsIgnoreCase(targetGdi))
                        && (!requireExtended || isUsableExtendedTarget(actual, displays))
                        && (expectedMode == null || sameMode(actual.currentMode(), expectedMode))) {
                    return actual;
                }
                lastObservation = describeObservation(actual, expectedMode, requireExtended, displays);
            } catch (IOException exception) {
                lastFailure = exception;
                lastObservation = "read-only discovery failed: " + exception.getMessage();
            }
            sleepForTopology();
        } while (System.nanoTime() < deadline);
        throw new IOException("Windows did not report the requested display state within " + TOPOLOGY_TIMEOUT
                + "; last observation: " + lastObservation,
                lastFailure);
    }

    private void waitForOriginalTopologyFingerprint(WindowsDisplaySnapshot snapshot) throws IOException {
        long deadline = System.nanoTime() + TOPOLOGY_TIMEOUT.toNanos();
        IOException lastFailure = null;
        String lastFingerprint = "unavailable";
        do {
            try {
                lastFingerprint = discovery.currentTopologyFingerprint();
                if (snapshot.topologyFingerprint().equals(lastFingerprint)) {
                    return;
                }
            } catch (IOException exception) {
                lastFailure = exception;
            }
            sleepForTopology();
        } while (System.nanoTime() < deadline);
        throw new IOException("Windows did not restore the original topology fingerprint within " + TOPOLOGY_TIMEOUT
                + "; last fingerprint=" + lastFingerprint, lastFailure);
    }

    private static String describeObservation(
            DisplayInfo actual,
            DisplayMode expectedMode,
            boolean requireExtended,
            List<DisplayInfo> displays
    ) {
        if (actual == null) {
            return "selected target address was absent";
        }
        String mode = actual.currentMode() == null ? "unknown" : formatMode(actual.currentMode());
        String expected = expectedMode == null ? "not checked" : formatMode(expectedMode);
        return "active=" + actual.active()
                + ", gdi=" + actual.gdiDeviceName()
                + ", mode=" + mode
                + ", expectedMode=" + expected
                + ", modeMatch=" + (expectedMode == null || actual.currentMode() != null && sameMode(actual.currentMode(), expectedMode))
                + ", extended=" + (!requireExtended || isUsableExtendedTarget(actual, displays));
    }

    private static String formatMode(DisplayMode mode) {
        return mode.width() + "x" + mode.height() + "@" + mode.refreshRate().hertz()
                + "/" + mode.bitsPerPixel() + (mode.interlaced() ? "i" : "p");
    }

    static boolean isUsableExtendedTarget(DisplayInfo target, List<DisplayInfo> displays) {
        if (!target.active() || target.internal() || target.gdiDeviceName().isBlank()) {
            return false;
        }
        List<DisplayInfo> activeInternal = displays.stream()
                .filter(DisplayInfo::internal)
                .filter(DisplayInfo::active)
                .toList();
        return !activeInternal.isEmpty() && activeInternal.stream()
                .noneMatch(internal -> internal.gdiDeviceName().equalsIgnoreCase(target.gdiDeviceName()));
    }

    private WindowsDisplayDiscovery.DevModeW createRequestedMode(String gdiDeviceName, DisplayMode requested) throws IOException {
        long numerator = requested.refreshRate().numerator();
        long denominator = requested.refreshRate().denominator();
        if (numerator % denominator != 0) {
            throw new IOException("ChangeDisplaySettingsExW accepts only integral DEVMODE refresh values; refusing to round "
                    + requested.refreshRate().hertz() + " Hz");
        }
        WindowsDisplayDiscovery.DevModeW candidate = readCurrentDevMode(gdiDeviceName);
        candidate.dmPelsWidth = requested.width();
        candidate.dmPelsHeight = requested.height();
        candidate.dmBitsPerPel = requested.bitsPerPixel();
        candidate.dmDisplayFrequency = Math.toIntExact(numerator / denominator);
        candidate.dmDisplayFlags = requested.interlaced()
                ? candidate.dmDisplayFlags | DM_INTERLACED
                : candidate.dmDisplayFlags & ~DM_INTERLACED;
        candidate.dmFields = DM_PELSWIDTH | DM_PELSHEIGHT | DM_BITSPERPEL | DM_DISPLAYFREQUENCY | DM_DISPLAYFLAGS;
        candidate.dmSize = (short) candidate.size();
        candidate.write();
        return candidate;
    }

    private WindowsDisplayDiscovery.DevModeW readCurrentDevMode(String gdiDeviceName) throws IOException {
        WindowsDisplayDiscovery.DevModeW mode = new WindowsDisplayDiscovery.DevModeW();
        mode.dmSize = (short) mode.size();
        mode.write();
        if (!user32.EnumDisplaySettingsExW(gdiDeviceName, ENUM_CURRENT_SETTINGS, mode, 0)) {
            throw new IOException("EnumDisplaySettingsExW could not read current mode for " + gdiDeviceName);
        }
        mode.read();
        return mode;
    }

    private byte[] readCurrentDevModeBytes(String gdiDeviceName) throws IOException {
        WindowsDisplayDiscovery.DevModeW mode = readCurrentDevMode(gdiDeviceName);
        return mode.getPointer().getByteArray(0, mode.size());
    }

    private static WindowsDisplayDiscovery.DevModeW fromBytes(byte[] bytes) throws IOException {
        WindowsDisplayDiscovery.DevModeW mode = new WindowsDisplayDiscovery.DevModeW();
        if (bytes.length != mode.size()) {
            throw new IOException("Snapshot DEVMODE has unexpected size " + bytes.length);
        }
        mode.getPointer().write(0, bytes, 0, bytes.length);
        mode.read();
        mode.dmSize = (short) mode.size();
        mode.write();
        return mode;
    }

    private NativeTopology queryActiveTopology() throws IOException {
        requireWindows();
        for (int attempt = 0; attempt < MAX_QUERY_RETRIES; attempt++) {
            IntByReference pathCount = new IntByReference();
            IntByReference modeCount = new IntByReference();
            requireSuccess("GetDisplayConfigBufferSizes", user32.GetDisplayConfigBufferSizes(
                    QDC_ONLY_ACTIVE_PATHS | QDC_VIRTUAL_MODE_AWARE, pathCount, modeCount));
            int pathSize = new WindowsDisplayDiscovery.DisplayConfigPathInfo().size();
            int modeSize = new WindowsDisplayDiscovery.DisplayConfigModeInfo().size();
            Memory paths = new Memory(Math.max(1L, (long) pathCount.getValue() * pathSize));
            Memory modes = new Memory(Math.max(1L, (long) modeCount.getValue() * modeSize));
            int status = user32.QueryDisplayConfig(
                    QDC_ONLY_ACTIVE_PATHS | QDC_VIRTUAL_MODE_AWARE,
                    pathCount,
                    paths,
                    modeCount,
                    modes,
                    null
            );
            if (status == ERROR_INSUFFICIENT_BUFFER) {
                continue;
            }
            requireSuccess("QueryDisplayConfig", status);
            return new NativeTopology(
                    pathCount.getValue(),
                    paths.getByteArray(0, Math.multiplyExact(pathCount.getValue(), pathSize)),
                    modeCount.getValue(),
                    modes.getByteArray(0, Math.multiplyExact(modeCount.getValue(), modeSize)),
                    queryDatabaseTopologyId()
            );
        }
        throw new IOException("QueryDisplayConfig repeatedly returned ERROR_INSUFFICIENT_BUFFER");
    }

    /** QueryDisplayConfig returns a topology ID only for QDC_DATABASE_CURRENT. */
    private int queryDatabaseTopologyId() throws IOException {
        for (int attempt = 0; attempt < MAX_QUERY_RETRIES; attempt++) {
            IntByReference pathCount = new IntByReference();
            IntByReference modeCount = new IntByReference();
            requireSuccess("GetDisplayConfigBufferSizes(QDC_DATABASE_CURRENT)", user32.GetDisplayConfigBufferSizes(
                    QDC_DATABASE_CURRENT, pathCount, modeCount));
            int pathSize = new WindowsDisplayDiscovery.DisplayConfigPathInfo().size();
            int modeSize = new WindowsDisplayDiscovery.DisplayConfigModeInfo().size();
            Memory paths = new Memory(Math.max(1L, (long) pathCount.getValue() * pathSize));
            Memory modes = new Memory(Math.max(1L, (long) modeCount.getValue() * modeSize));
            Memory topologyId = new Memory(Integer.BYTES);
            int status = user32.QueryDisplayConfig(
                    QDC_DATABASE_CURRENT,
                    pathCount,
                    paths,
                    modeCount,
                    modes,
                    topologyId
            );
            if (status == ERROR_INSUFFICIENT_BUFFER) {
                continue;
            }
            requireSuccess("QueryDisplayConfig(QDC_DATABASE_CURRENT)", status);
            return topologyId.getInt(0);
        }
        throw new IOException("QueryDisplayConfig(QDC_DATABASE_CURRENT) repeatedly returned ERROR_INSUFFICIENT_BUFFER");
    }

    private static boolean sameMode(DisplayMode actual, DisplayMode expected) {
        return actual.width() == expected.width()
                && actual.height() == expected.height()
                && actual.bitsPerPixel() == expected.bitsPerPixel()
                && actual.interlaced() == expected.interlaced()
                && actual.refreshRate().isWithin(expected.refreshRate(), REFRESH_TOLERANCE_HERTZ);
    }

    private static void sleepForTopology() throws IOException {
        try {
            Thread.sleep(TOPOLOGY_POLL_INTERVAL);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for Windows display topology", exception);
        }
    }

    private static void requireSelectableExternalTarget(DisplayInfo target, boolean targetWasExplicitlyConfirmed) throws IOException {
        requireWindows();
        Objects.requireNonNull(target, "target");
        if (target.internal() || !target.active() && !target.targetAvailable()) {
            throw new IOException("A connected or available external display target is required");
        }
        if (target.gdiMappingConfidence() != GdiMappingConfidence.AUTHORITATIVE && !targetWasExplicitlyConfirmed) {
            throw new IOException("The driver did not provide authoritative target-to-GDI mapping; explicit confirmation is required");
        }
    }

    private void requireIndependentActiveTarget(DisplayInfo target, boolean targetWasExplicitlyConfirmed) throws IOException {
        requireSelectableExternalTarget(target, targetWasExplicitlyConfirmed);
        if (!isUsableExtendedTarget(target, discovery.discoverDisplays()) || target.currentMode() == null) {
            throw new IOException("An active external target on a separate extended desktop is required before changing its mode");
        }
        if (target.primary()) {
            throw new IOException("ScreenPilot never changes the primary display");
        }
    }

    private static void requireWindows() {
        if (!Platform.isWindows()) {
            throw new UnsupportedOperationException("Windows display mutation is available only on Windows");
        }
    }

    private static void requireSuccess(String operation, int status) throws IOException {
        if (status != ERROR_SUCCESS) {
            throw new IOException(operation + " failed with Win32 error " + status);
        }
    }

    public interface User32DisplayMutationApi extends StdCallLibrary {
        User32DisplayMutationApi INSTANCE = Native.load("user32", User32DisplayMutationApi.class, W32APIOptions.DEFAULT_OPTIONS);

        int GetDisplayConfigBufferSizes(int flags, IntByReference pathCount, IntByReference modeCount);

        int QueryDisplayConfig(int flags, IntByReference pathCount, Pointer pathArray, IntByReference modeCount,
                               Pointer modeInfoArray, Pointer currentTopologyId);

        int SetDisplayConfig(int pathCount, Pointer pathArray, int modeCount, Pointer modeInfoArray, int flags);

        boolean EnumDisplaySettingsExW(String deviceName, int modeNumber, WindowsDisplayDiscovery.DevModeW mode, int flags);

        int ChangeDisplaySettingsExW(String deviceName, WindowsDisplayDiscovery.DevModeW mode,
                                     Pointer window, int flags, Pointer parameter);
    }

    private record NativeTopology(int pathCount, byte[] pathBytes, int modeCount, byte[] modeBytes, int topologyId) {
    }
}
