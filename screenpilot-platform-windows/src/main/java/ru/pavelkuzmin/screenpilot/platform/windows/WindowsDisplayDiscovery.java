package ru.pavelkuzmin.screenpilot.platform.windows;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import ru.pavelkuzmin.screenpilot.domain.display.ConnectionType;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayBounds;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayId;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayInfo;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayMode;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayTargetAddress;
import ru.pavelkuzmin.screenpilot.domain.display.GdiMappingConfidence;
import ru.pavelkuzmin.screenpilot.domain.display.RefreshRate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only Windows display discovery based on Display Configuration API and EnumDisplaySettingsExW.
 * It deliberately contains no SetDisplayConfig or ChangeDisplaySettingsExW binding.
 */
public final class WindowsDisplayDiscovery {

    private static final int QDC_ALL_PATHS = 0x0000_0001;
    private static final int QDC_VIRTUAL_MODE_AWARE = 0x0000_0010;
    private static final int ERROR_SUCCESS = 0;
    private static final int ERROR_INSUFFICIENT_BUFFER = 122;
    private static final int DISPLAYCONFIG_PATH_ACTIVE = 0x0000_0001;
    private static final int DISPLAYCONFIG_DEVICE_INFO_GET_TARGET_NAME = 2;
    private static final int DISPLAYCONFIG_DEVICE_INFO_GET_TARGET_PREFERRED_MODE = 3;
    private static final int DISPLAYCONFIG_DEVICE_INFO_GET_SOURCE_NAME = 1;
    private static final int DISPLAYCONFIG_DEVICE_INFO_GET_ADVANCED_COLOR_INFO = 9;
    private static final int ENUM_CURRENT_SETTINGS = -1;
    private static final int DM_INTERLACED = 0x0000_0002;
    private static final int DISPLAY_DEVICE_ATTACHED_TO_DESKTOP = 0x0000_0001;
    private static final int DISPLAY_DEVICE_PRIMARY_DEVICE = 0x0000_0004;
    private static final int MAX_RETRIES = 3;

    private final User32DisplayApi user32;

    public WindowsDisplayDiscovery() {
        this(User32DisplayApi.INSTANCE);
    }

    WindowsDisplayDiscovery(User32DisplayApi user32) {
        this.user32 = Objects.requireNonNull(user32, "user32");
    }

    public List<DisplayInfo> discoverDisplays() throws IOException {
        requireWindows();
        List<DisplayConfigPathInfo> paths = distinctTargets(queryPaths());
        List<GdiDisplay> gdiDisplays = enumerateGdiDisplays();
        List<DisplayInfo> displays = new ArrayList<>(paths.size());
        for (DisplayConfigPathInfo path : paths) {
            boolean active = (path.flags & DISPLAYCONFIG_PATH_ACTIVE) != 0;
            String sourceName = active ? querySourceName(path.sourceInfo) : "";
            GdiDisplay gdiDisplay = active ? findGdiDisplay(sourceName, gdiDisplays) : null;
            String gdiDeviceName = gdiDisplay == null ? sourceName : gdiDisplay.deviceName();
            GdiMappingConfidence gdiMappingConfidence = !sourceName.isBlank()
                    ? GdiMappingConfidence.AUTHORITATIVE
                    : gdiDisplay == null ? GdiMappingConfidence.UNAVAILABLE : GdiMappingConfidence.FALLBACK;
            TargetName targetName = queryTargetName(path.targetInfo);
            DisplayMode currentMode = active ? withPathRefreshRate(queryCurrentMode(gdiDeviceName), path.targetInfo.refreshRate) : null;
            DisplayMode preferredMode = queryPreferredMode(path.targetInfo);
            List<DisplayMode> confirmedModes = active ? listConfirmedModes(gdiDeviceName) : List.of();
            int outputTechnology = targetName == null ? path.targetInfo.outputTechnology : targetName.outputTechnology;
            boolean internal = isInternalOutputTechnology(outputTechnology);
            String monitorPath = targetName == null ? "" : nativeString(targetName.monitorDevicePath);
            short manufacturerId = targetName == null ? 0 : targetName.edidManufactureId;
            short productCodeId = targetName == null ? 0 : targetName.edidProductCodeId;
            String displayId = stableDisplayId(monitorPath, manufacturerId, productCodeId,
                    path.targetInfo.adapterId, path.targetInfo.id);
            DisplayBounds bounds = currentMode == null ? null : queryBounds(gdiDeviceName);
            displays.add(new DisplayInfo(
                    new DisplayId(displayId),
                    friendlyName(targetName, path.targetInfo.id, gdiDisplay),
                    manufacturerId == 0 ? "Unknown" : String.format("EDID-%04X", manufacturerId & 0xFFFF),
                    productCodeId == 0 ? "Unknown" : String.format("%04X", productCodeId & 0xFFFF),
                    connectionType(outputTechnology),
                    internal,
                    active,
                    gdiDisplay != null ? gdiDisplay.primary() : isPrimary(gdiDeviceName),
                    path.targetInfo.targetAvailable != 0,
                    gdiDeviceName,
                    gdiMappingConfidence,
                    bounds,
                    currentMode,
                    preferredMode,
                    confirmedModes,
                    supportsAdvancedColor(path.targetInfo),
                    monitorPath,
                    new DisplayTargetAddress(
                            Integer.toUnsignedLong(path.targetInfo.adapterId.lowPart),
                            path.targetInfo.adapterId.highPart,
                            path.targetInfo.id
                    )
            ));
        }
        return displays.stream()
                .sorted(Comparator.comparingInt(WindowsDisplayDiscovery::displaySortGroup)
                        .thenComparing(DisplayInfo::friendlyName))
                .toList();
    }

    /**
     * Fast, read-only signature of the active and available Windows paths. It deliberately avoids
     * GDI mode enumeration so the poller can skip expensive work when nothing has changed.
     */
    public String currentTopologyFingerprint() throws IOException {
        requireWindows();
        StringBuilder payload = new StringBuilder();
        distinctTargets(queryPaths()).stream()
                .sorted(Comparator.comparing(WindowsDisplayDiscovery::targetAddressKey))
                .forEach(path -> payload
                        .append(targetAddressKey(path)).append('|')
                        .append(Integer.toUnsignedString(path.sourceInfo.adapterId.lowPart)).append(':')
                        .append(path.sourceInfo.adapterId.highPart).append(':').append(path.sourceInfo.id).append('|')
                        .append(path.targetInfo.outputTechnology).append('|')
                        .append(path.targetInfo.targetAvailable).append('|')
                        .append(path.flags).append('\n'));
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload.toString().getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java runtime does not provide SHA-256", exception);
        }
    }

    private static String targetAddressKey(DisplayConfigPathInfo path) {
        return Integer.toUnsignedString(path.targetInfo.adapterId.lowPart)
                + ":" + path.targetInfo.adapterId.highPart + ":" + path.targetInfo.id;
    }

    private static List<DisplayConfigPathInfo> distinctTargets(List<DisplayConfigPathInfo> paths) {
        java.util.Map<String, DisplayConfigPathInfo> distinct = new java.util.LinkedHashMap<>();
        for (DisplayConfigPathInfo candidate : paths) {
            String key = targetAddressKey(candidate);
            DisplayConfigPathInfo existing = distinct.get(key);
            if (existing == null || isActive(candidate) && !isActive(existing)
                    || candidate.targetInfo.targetAvailable != 0 && existing.targetInfo.targetAvailable == 0) {
                distinct.put(key, candidate);
            }
        }
        return List.copyOf(distinct.values());
    }

    private static boolean isActive(DisplayConfigPathInfo path) {
        return (path.flags & DISPLAYCONFIG_PATH_ACTIVE) != 0;
    }

    private static int displaySortGroup(DisplayInfo display) {
        if (display.internal()) {
            return 3;
        }
        if (display.active() && display.connectionType() == ConnectionType.HDMI) {
            return 0;
        }
        return display.active() ? 1 : 2;
    }

    private List<GdiDisplay> enumerateGdiDisplays() {
        List<GdiDisplay> displays = new ArrayList<>();
        for (int adapterIndex = 0; ; adapterIndex++) {
            DisplayDevice adapter = newDisplayDevice();
            if (!user32.EnumDisplayDevicesW(null, adapterIndex, adapter, 0)) {
                break;
            }
            adapter.read();
            String adapterName = nativeString(adapter.deviceName);
            String monitorName = "";
            for (int monitorIndex = 0; ; monitorIndex++) {
                DisplayDevice monitor = newDisplayDevice();
                if (!user32.EnumDisplayDevicesW(adapterName, monitorIndex, monitor, 0)) {
                    break;
                }
                monitor.read();
                String candidateName = nativeString(monitor.deviceString);
                if (!candidateName.isBlank()) {
                    monitorName = candidateName;
                    break;
                }
            }
            if (monitorName.isBlank()) {
                monitorName = nativeString(adapter.deviceString);
            }
            displays.add(new GdiDisplay(
                    adapterName,
                    monitorName,
                    (adapter.stateFlags & DISPLAY_DEVICE_ATTACHED_TO_DESKTOP) != 0,
                    (adapter.stateFlags & DISPLAY_DEVICE_PRIMARY_DEVICE) != 0
            ));
        }
        return displays;
    }

    private static GdiDisplay findGdiDisplay(String sourceName, List<GdiDisplay> gdiDisplays) {
        if (!sourceName.isBlank()) {
            for (GdiDisplay display : gdiDisplays) {
                if (sourceName.equalsIgnoreCase(display.deviceName())) {
                    return display;
                }
            }
        }
        // DISPLAYCONFIG source IDs are local to an adapter and are not an index into EnumDisplayDevices.
        // A missing GET_SOURCE_NAME result therefore remains unavailable rather than being guessed.
        return null;
    }

    public List<DisplayMode> listConfirmedModes(String gdiDeviceName) throws IOException {
        if (gdiDeviceName == null || gdiDeviceName.isBlank()) {
            return List.of();
        }
        Set<DisplayMode> modes = new LinkedHashSet<>();
        for (int index = 0; ; index++) {
            DevModeW mode = new DevModeW();
            mode.dmSize = (short) mode.size();
            mode.write();
            if (!user32.EnumDisplaySettingsExW(gdiDeviceName, index, mode, 0)) {
                break;
            }
            mode.read();
            if (mode.dmPelsWidth >= 640 && mode.dmPelsHeight >= 480
                    && mode.dmBitsPerPel >= 32 && mode.dmDisplayFrequency > 0) {
                modes.add(toDisplayMode(mode, false));
            }
        }
        return modes.stream()
                .sorted(Comparator.comparingInt(DisplayMode::width)
                        .thenComparingInt(DisplayMode::height)
                        .thenComparing(DisplayMode::refreshRate)
                        .thenComparing(DisplayMode::interlaced))
                .toList();
    }

    private List<DisplayConfigPathInfo> queryPaths() throws IOException {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            IntByReference pathCount = new IntByReference();
            IntByReference modeCount = new IntByReference();
            int bufferStatus = user32.GetDisplayConfigBufferSizes(QDC_ALL_PATHS | QDC_VIRTUAL_MODE_AWARE, pathCount, modeCount);
            requireSuccess("GetDisplayConfigBufferSizes", bufferStatus);

            int requestedPathCount = pathCount.getValue();
            Memory paths = new Memory(Math.max(1L, (long) requestedPathCount * new DisplayConfigPathInfo().size()));
            Memory modes = new Memory(Math.max(1L,
                    (long) modeCount.getValue() * new DisplayConfigModeInfo().size()));
            int queryStatus = user32.QueryDisplayConfig(
                    QDC_ALL_PATHS | QDC_VIRTUAL_MODE_AWARE,
                    pathCount,
                    paths,
                    modeCount,
                    modes,
                    null
            );
            if (queryStatus == ERROR_INSUFFICIENT_BUFFER) {
                continue;
            }
            requireSuccess("QueryDisplayConfig", queryStatus);
            List<DisplayConfigPathInfo> result = new ArrayList<>(pathCount.getValue());
            int pathSize = new DisplayConfigPathInfo().size();
            for (int index = 0; index < pathCount.getValue(); index++) {
                DisplayConfigPathInfo path = new DisplayConfigPathInfo(paths.share((long) index * pathSize));
                path.read();
                result.add(path);
            }
            return result;
        }
        throw new IOException("QueryDisplayConfig repeatedly returned ERROR_INSUFFICIENT_BUFFER");
    }

    private TargetName queryTargetName(DisplayConfigPathTargetInfo target) {
        TargetName packet = new TargetName();
        packet.header.adapterId = target.adapterId.copy();
        packet.header.id = target.id;
        packet.header.type = DISPLAYCONFIG_DEVICE_INFO_GET_TARGET_NAME;
        packet.header.size = packet.size();
        packet.write();
        int status = user32.DisplayConfigGetDeviceInfo(packet.getPointer());
        if (status != ERROR_SUCCESS) {
            // Some WDDM drivers return ERROR_GEN_FAILURE despite exposing a usable path. The path itself
            // remains authoritative for connection type and target availability, so retain it with metadata absent.
            return null;
        }
        packet.read();
        return packet;
    }

    private String querySourceName(DisplayConfigPathSourceInfo source) {
        SourceName packet = new SourceName();
        packet.header.adapterId = source.adapterId.copy();
        packet.header.id = source.id;
        packet.header.type = DISPLAYCONFIG_DEVICE_INFO_GET_SOURCE_NAME;
        packet.header.size = packet.size();
        packet.write();
        if (user32.DisplayConfigGetDeviceInfo(packet.getPointer()) != ERROR_SUCCESS) {
            // See the matching target-name fallback: keep discovery functional on drivers that expose
            // paths but reject the optional DISPLAYCONFIG_DEVICE_INFO_GET_* metadata packets.
            return "";
        }
        packet.read();
        return nativeString(packet.viewGdiDeviceName);
    }

    private DisplayMode queryPreferredMode(DisplayConfigPathTargetInfo target) throws IOException {
        TargetPreferredMode packet = new TargetPreferredMode();
        packet.header.adapterId = target.adapterId.copy();
        packet.header.id = target.id;
        packet.header.type = DISPLAYCONFIG_DEVICE_INFO_GET_TARGET_PREFERRED_MODE;
        packet.header.size = packet.size();
        packet.write();
        int status = user32.DisplayConfigGetDeviceInfo(packet.getPointer());
        if (status != ERROR_SUCCESS) {
            return null;
        }
        packet.read();
        DisplayConfigVideoSignalInfo videoSignal = packet.targetMode.targetVideoSignalInfo;
        if (packet.width == 0 || packet.height == 0 || videoSignal.vSyncFreq.denominator == 0) {
            return null;
        }
        return new DisplayMode(
                packet.width,
                packet.height,
                RefreshRate.of(Integer.toUnsignedLong(videoSignal.vSyncFreq.numerator),
                        Integer.toUnsignedLong(videoSignal.vSyncFreq.denominator)),
                videoSignal.scanLineOrdering != 1,
                32,
                true
        );
    }

    private boolean supportsAdvancedColor(DisplayConfigPathTargetInfo target) {
        AdvancedColorInfo packet = new AdvancedColorInfo();
        packet.header.adapterId = target.adapterId.copy();
        packet.header.id = target.id;
        packet.header.type = DISPLAYCONFIG_DEVICE_INFO_GET_ADVANCED_COLOR_INFO;
        packet.header.size = packet.size();
        packet.write();
        if (user32.DisplayConfigGetDeviceInfo(packet.getPointer()) != ERROR_SUCCESS) {
            return false;
        }
        packet.read();
        return (packet.value & 0x1) != 0;
    }

    private DisplayMode queryCurrentMode(String gdiDeviceName) {
        if (gdiDeviceName == null || gdiDeviceName.isBlank()) {
            return null;
        }
        DevModeW mode = new DevModeW();
        mode.dmSize = (short) mode.size();
        mode.write();
        if (!user32.EnumDisplaySettingsExW(gdiDeviceName, ENUM_CURRENT_SETTINGS, mode, 0)) {
            return null;
        }
        mode.read();
        if (mode.dmPelsWidth <= 0 || mode.dmPelsHeight <= 0 || mode.dmDisplayFrequency <= 0) {
            return null;
        }
        return toDisplayMode(mode, false);
    }

    private DisplayBounds queryBounds(String gdiDeviceName) {
        DevModeW mode = new DevModeW();
        mode.dmSize = (short) mode.size();
        mode.write();
        if (!user32.EnumDisplaySettingsExW(gdiDeviceName, ENUM_CURRENT_SETTINGS, mode, 0)) {
            return null;
        }
        mode.read();
        ByteBuffer position = ByteBuffer.wrap(mode.dmUnion).order(ByteOrder.LITTLE_ENDIAN);
        return new DisplayBounds(position.getInt(0), position.getInt(4), mode.dmPelsWidth, mode.dmPelsHeight);
    }

    private boolean isPrimary(String gdiDeviceName) {
        if (gdiDeviceName == null || gdiDeviceName.isBlank()) {
            return false;
        }
        for (int index = 0; ; index++) {
            DisplayDevice device = new DisplayDevice();
            device.cb = device.size();
            device.write();
            if (!user32.EnumDisplayDevicesW(null, index, device, 0)) {
                return false;
            }
            device.read();
            if (gdiDeviceName.equalsIgnoreCase(nativeString(device.deviceName))) {
                return (device.stateFlags & DISPLAY_DEVICE_PRIMARY_DEVICE) != 0;
            }
        }
    }

    private static DisplayDevice newDisplayDevice() {
        DisplayDevice device = new DisplayDevice();
        device.cb = device.size();
        device.write();
        return device;
    }

    private static DisplayMode toDisplayMode(DevModeW mode, boolean preferred) {
        return new DisplayMode(
                mode.dmPelsWidth,
                mode.dmPelsHeight,
                RefreshRate.of(Integer.toUnsignedLong(mode.dmDisplayFrequency), 1),
                (mode.dmDisplayFlags & DM_INTERLACED) != 0,
                mode.dmBitsPerPel,
                preferred
        );
    }

    private static DisplayMode withPathRefreshRate(DisplayMode mode, DisplayConfigRational refreshRate) {
        if (mode == null || refreshRate.denominator == 0 || refreshRate.numerator == 0) {
            return mode;
        }
        return new DisplayMode(
                mode.width(),
                mode.height(),
                RefreshRate.of(Integer.toUnsignedLong(refreshRate.numerator),
                        Integer.toUnsignedLong(refreshRate.denominator)),
                mode.interlaced(),
                mode.bitsPerPixel(),
                mode.preferred()
        );
    }

    private static String friendlyName(TargetName targetName, int targetId, GdiDisplay gdiDisplay) {
        if (targetName == null) {
            if (gdiDisplay != null && !gdiDisplay.monitorName().isBlank()) {
                return gdiDisplay.monitorName();
            }
            return "Display target " + targetId;
        }
        String name = nativeString(targetName.monitorFriendlyDeviceName);
        return name.isBlank() ? "External display " + targetName.header.id : name;
    }

    private static String stableDisplayId(
            String monitorPath,
            short manufacturerId,
            short productCode,
            Luid adapterId,
            int targetId
    ) {
        if (!monitorPath.isBlank()) {
            return monitorPath + "#" + String.format("%04X-%04X", manufacturerId & 0xFFFF, productCode & 0xFFFF);
        }
        return "luid:" + Integer.toUnsignedString(adapterId.lowPart) + ":" + adapterId.highPart + "#target:" + targetId;
    }

    static ConnectionType connectionType(int outputTechnology) {
        return switch (outputTechnology) {
            case OutputTechnology.INTERNAL,
                    OutputTechnology.LVDS,
                    OutputTechnology.DISPLAYPORT_EMBEDDED,
                    OutputTechnology.UDI_EMBEDDED -> ConnectionType.INTERNAL;
            case OutputTechnology.HDMI -> ConnectionType.HDMI;
            case OutputTechnology.DISPLAYPORT_EXTERNAL -> ConnectionType.DISPLAY_PORT;
            case OutputTechnology.DVI -> ConnectionType.DVI;
            case OutputTechnology.UDI_EXTERNAL -> ConnectionType.USB_C_OR_ADAPTER;
            case OutputTechnology.OTHER -> ConnectionType.OTHER;
            default -> ConnectionType.UNKNOWN;
        };
    }

    static boolean isInternalOutputTechnology(int outputTechnology) {
        return switch (outputTechnology) {
            case OutputTechnology.INTERNAL,
                    OutputTechnology.LVDS,
                    OutputTechnology.DISPLAYPORT_EMBEDDED,
                    OutputTechnology.UDI_EMBEDDED -> true;
            default -> false;
        };
    }

    private static String nativeString(char[] characters) {
        String value = Native.toString(characters);
        return value == null ? "" : value.trim();
    }

    private static void requireWindows() {
        if (!Platform.isWindows()) {
            throw new UnsupportedOperationException("Windows display discovery is available only on Windows");
        }
    }

    private static void requireSuccess(String operation, int status) throws IOException {
        if (status != ERROR_SUCCESS) {
            throw new IOException(operation + " failed with Win32 error " + status);
        }
    }

    public interface User32DisplayApi extends StdCallLibrary {
        User32DisplayApi INSTANCE = Native.load("user32", User32DisplayApi.class, W32APIOptions.DEFAULT_OPTIONS);

        int GetDisplayConfigBufferSizes(int flags, IntByReference pathCount, IntByReference modeCount);

        int QueryDisplayConfig(
                int flags,
                IntByReference pathCount,
                Pointer pathArray,
                IntByReference modeCount,
                Pointer modeInfoArray,
                Pointer currentTopologyId
        );

        int DisplayConfigGetDeviceInfo(Pointer requestPacket);

        boolean EnumDisplaySettingsExW(String deviceName, int modeNumber, DevModeW mode, int flags);

        boolean EnumDisplayDevicesW(String deviceName, int deviceNumber, DisplayDevice displayDevice, int flags);
    }

    @Structure.FieldOrder({"lowPart", "highPart"})
    public static class Luid extends Structure {
        public int lowPart;
        public int highPart;

        Luid copy() {
            Luid copy = new Luid();
            copy.lowPart = lowPart;
            copy.highPart = highPart;
            return copy;
        }
    }

    @Structure.FieldOrder({"numerator", "denominator"})
    public static class DisplayConfigRational extends Structure {
        public int numerator;
        public int denominator;
    }

    @Structure.FieldOrder({"adapterId", "id", "modeInfoIndex", "statusFlags"})
    public static class DisplayConfigPathSourceInfo extends Structure {
        public Luid adapterId = new Luid();
        public int id;
        public int modeInfoIndex;
        public int statusFlags;
    }

    @Structure.FieldOrder({
            "adapterId", "id", "modeInfoIndex", "outputTechnology", "rotation", "scaling",
            "refreshRate", "scanLineOrdering", "targetAvailable", "statusFlags"
    })
    public static class DisplayConfigPathTargetInfo extends Structure {
        public Luid adapterId = new Luid();
        public int id;
        public int modeInfoIndex;
        public int outputTechnology;
        public int rotation;
        public int scaling;
        public DisplayConfigRational refreshRate = new DisplayConfigRational();
        public int scanLineOrdering;
        public int targetAvailable;
        public int statusFlags;
    }

    @Structure.FieldOrder({"sourceInfo", "targetInfo", "flags"})
    public static class DisplayConfigPathInfo extends Structure {
        public DisplayConfigPathSourceInfo sourceInfo = new DisplayConfigPathSourceInfo();
        public DisplayConfigPathTargetInfo targetInfo = new DisplayConfigPathTargetInfo();
        public int flags;

        DisplayConfigPathInfo() {
        }

        DisplayConfigPathInfo(Pointer pointer) {
            super(pointer);
        }
    }

    /** Fixed-size outer layout of DISPLAYCONFIG_MODE_INFO; its final 48 bytes are a tagged union. */
    @Structure.FieldOrder({"infoType", "id", "adapterId", "modeInfo"})
    public static class DisplayConfigModeInfo extends Structure {
        public int infoType;
        public int id;
        public Luid adapterId = new Luid();
        public byte[] modeInfo = new byte[48];
    }

    @Structure.FieldOrder({"type", "size", "adapterId", "id"})
    public static class DisplayConfigDeviceInfoHeader extends Structure {
        public int type;
        public int size;
        public Luid adapterId = new Luid();
        public int id;

        int nativeOffsetOf(String fieldName) {
            return fieldOffset(fieldName);
        }
    }

    @Structure.FieldOrder({
            "header", "flags", "outputTechnology", "edidManufactureId", "edidProductCodeId", "connectorInstance",
            "monitorFriendlyDeviceName", "monitorDevicePath"
    })
    public static class TargetName extends Structure {
        public DisplayConfigDeviceInfoHeader header = new DisplayConfigDeviceInfoHeader();
        public int flags;
        public int outputTechnology;
        public short edidManufactureId;
        public short edidProductCodeId;
        public int connectorInstance;
        public char[] monitorFriendlyDeviceName = new char[64];
        public char[] monitorDevicePath = new char[128];
    }

    @Structure.FieldOrder({"cx", "cy"})
    public static class DisplayConfig2DRegion extends Structure {
        public int cx;
        public int cy;
    }

    @Structure.FieldOrder({
            "pixelRate", "hSyncFreq", "vSyncFreq", "activeSize", "totalSize", "videoStandard", "scanLineOrdering"
    })
    public static class DisplayConfigVideoSignalInfo extends Structure {
        public long pixelRate;
        public DisplayConfigRational hSyncFreq = new DisplayConfigRational();
        public DisplayConfigRational vSyncFreq = new DisplayConfigRational();
        public DisplayConfig2DRegion activeSize = new DisplayConfig2DRegion();
        public DisplayConfig2DRegion totalSize = new DisplayConfig2DRegion();
        public int videoStandard;
        public int scanLineOrdering;
    }

    @Structure.FieldOrder({"targetVideoSignalInfo"})
    public static class DisplayConfigTargetMode extends Structure {
        public DisplayConfigVideoSignalInfo targetVideoSignalInfo = new DisplayConfigVideoSignalInfo();
    }

    @Structure.FieldOrder({"header", "width", "height", "targetMode"})
    public static class TargetPreferredMode extends Structure {
        public DisplayConfigDeviceInfoHeader header = new DisplayConfigDeviceInfoHeader();
        public int width;
        public int height;
        public DisplayConfigTargetMode targetMode = new DisplayConfigTargetMode();

        int nativeOffsetOf(String fieldName) {
            return fieldOffset(fieldName);
        }
    }

    @Structure.FieldOrder({"header", "viewGdiDeviceName"})
    public static class SourceName extends Structure {
        public DisplayConfigDeviceInfoHeader header = new DisplayConfigDeviceInfoHeader();
        public char[] viewGdiDeviceName = new char[32];
    }

    @Structure.FieldOrder({"header", "value", "colorEncoding", "bitsPerColorChannel"})
    public static class AdvancedColorInfo extends Structure {
        public DisplayConfigDeviceInfoHeader header = new DisplayConfigDeviceInfoHeader();
        public int value;
        public int colorEncoding;
        public int bitsPerColorChannel;
    }

    @Structure.FieldOrder({
            "dmDeviceName", "dmSpecVersion", "dmDriverVersion", "dmSize", "dmDriverExtra", "dmFields", "dmUnion",
            "dmColor", "dmDuplex", "dmYResolution", "dmTTOption", "dmCollate", "dmFormName", "dmLogPixels",
            "dmBitsPerPel", "dmPelsWidth", "dmPelsHeight", "dmDisplayFlags", "dmDisplayFrequency", "dmICMMethod",
            "dmICMIntent", "dmMediaType", "dmDitherType", "dmReserved1", "dmReserved2", "dmPanningWidth", "dmPanningHeight"
    })
    public static class DevModeW extends Structure {
        public char[] dmDeviceName = new char[32];
        public short dmSpecVersion;
        public short dmDriverVersion;
        public short dmSize;
        public short dmDriverExtra;
        public int dmFields;
        public byte[] dmUnion = new byte[16];
        public short dmColor;
        public short dmDuplex;
        public short dmYResolution;
        public short dmTTOption;
        public short dmCollate;
        public char[] dmFormName = new char[32];
        public short dmLogPixels;
        public int dmBitsPerPel;
        public int dmPelsWidth;
        public int dmPelsHeight;
        public int dmDisplayFlags;
        public int dmDisplayFrequency;
        public int dmICMMethod;
        public int dmICMIntent;
        public int dmMediaType;
        public int dmDitherType;
        public int dmReserved1;
        public int dmReserved2;
        public int dmPanningWidth;
        public int dmPanningHeight;

        public DevModeW() {
            super(ALIGN_MSVC);
        }

    }

    @Structure.FieldOrder({"cb", "deviceName", "deviceString", "stateFlags", "deviceId", "deviceKey"})
    public static class DisplayDevice extends Structure {
        public int cb;
        public char[] deviceName = new char[32];
        public char[] deviceString = new char[128];
        public int stateFlags;
        public char[] deviceId = new char[128];
        public char[] deviceKey = new char[128];
    }

    static final class OutputTechnology {
        static final int OTHER = -1;
        static final int DVI = 4;
        static final int HDMI = 5;
        static final int LVDS = 6;
        static final int DISPLAYPORT_EXTERNAL = 10;
        static final int DISPLAYPORT_EMBEDDED = 11;
        static final int UDI_EXTERNAL = 12;
        static final int UDI_EMBEDDED = 13;
        static final int INTERNAL = 0x8000_0000;

        private OutputTechnology() {
        }
    }

    private record GdiDisplay(String deviceName, String monitorName, boolean attachedToDesktop, boolean primary) {
    }
}
