package ru.pavelkuzmin.screenpilot.platform.windows;

import com.sun.jna.Memory;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayMode;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayTargetAddress;
import ru.pavelkuzmin.screenpilot.domain.display.RefreshRate;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A lossless, serializable copy of the Windows display configuration required for rollback.
 * Native structures are stored as byte arrays so JNA objects never cross a process boundary.
 */
public final class WindowsDisplaySnapshot {

    static final int SCHEMA_VERSION = 1;
    private static final int DISPLAYCONFIG_PATH_INFO_SIZE = 72;
    private static final int DISPLAYCONFIG_MODE_INFO_SIZE = 64;
    private static final int DEVMODEW_SIZE = 220;
    private static final int DISPLAYCONFIG_PATH_ACTIVE = 0x0000_0001;
    private static final int DISPLAYCONFIG_PATH_MODE_IDX_INVALID = 0xFFFF_FFFF;
    private static final int MAX_PATH_COUNT = 64;
    private static final int MAX_MODE_COUNT = 256;
    private static final int MAX_SOURCE_COUNT = 32;

    private final String topologyFingerprint;
    private final int topologyId;
    private final String targetGdiDeviceName;
    private final DisplayTargetAddress targetAddress;
    private final DisplayMode targetMode;
    private final int pathCount;
    private final byte[] pathBytes;
    private final int modeCount;
    private final byte[] modeBytes;
    private final List<SourceDevMode> activeSourceModes;

    WindowsDisplaySnapshot(
            String topologyFingerprint,
            int topologyId,
            String targetGdiDeviceName,
            DisplayTargetAddress targetAddress,
            DisplayMode targetMode,
            int pathCount,
            byte[] pathBytes,
            int modeCount,
            byte[] modeBytes,
            List<SourceDevMode> activeSourceModes
    ) {
        this.topologyFingerprint = requireText(topologyFingerprint, "topologyFingerprint");
        this.topologyId = topologyId;
        this.targetGdiDeviceName = targetGdiDeviceName == null ? "" : targetGdiDeviceName;
        this.targetAddress = Objects.requireNonNull(targetAddress, "targetAddress");
        this.targetMode = targetMode;
        if (pathCount < 1 || pathCount > MAX_PATH_COUNT || modeCount < 0 || modeCount > MAX_MODE_COUNT) {
            throw new IllegalArgumentException("Invalid DisplayConfig array counts");
        }
        this.pathCount = pathCount;
        this.pathBytes = copyExactLength(pathBytes, Math.multiplyExact(pathCount, DISPLAYCONFIG_PATH_INFO_SIZE), "pathBytes");
        this.modeCount = modeCount;
        this.modeBytes = copyExactLength(modeBytes, Math.multiplyExact(modeCount, DISPLAYCONFIG_MODE_INFO_SIZE), "modeBytes");
        this.activeSourceModes = List.copyOf(Objects.requireNonNullElse(activeSourceModes, List.of()));
        if (this.activeSourceModes.size() > MAX_SOURCE_COUNT) {
            throw new IllegalArgumentException("Invalid source count in Windows display snapshot");
        }
        validateSourceModes(this.activeSourceModes);
        validateTopologyPayload();
    }

    public String topologyFingerprint() {
        return topologyFingerprint;
    }

    public int topologyId() {
        return topologyId;
    }

    public String targetGdiDeviceName() {
        return targetGdiDeviceName;
    }

    public DisplayTargetAddress targetAddress() {
        return targetAddress;
    }

    public DisplayMode targetMode() {
        return targetMode;
    }

    /** An inactive or cloned original target has no independent GDI mode to restore. */
    public boolean hasOriginalTargetMode() {
        return targetMode != null && !targetGdiDeviceName.isBlank();
    }

    int pathCount() {
        return pathCount;
    }

    byte[] pathBytes() {
        return pathBytes.clone();
    }

    int modeCount() {
        return modeCount;
    }

    byte[] modeBytes() {
        return modeBytes.clone();
    }

    List<SourceDevMode> activeSourceModes() {
        return activeSourceModes;
    }

    /** Opaque, checksummed payload stored by the domain-level recovery journal. */
    public String toRecoveryPayload() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("version", Integer.toString(SCHEMA_VERSION));
        fields.put("fingerprint", encoded(topologyFingerprint));
        fields.put("topologyId", Integer.toUnsignedString(topologyId));
        fields.put("targetGdi", encoded(targetGdiDeviceName));
        fields.put("targetLuidLow", Long.toUnsignedString(targetAddress.adapterLuidLowPart()));
        fields.put("targetLuidHigh", Integer.toUnsignedString(targetAddress.adapterLuidHighPart()));
        fields.put("targetId", Integer.toUnsignedString(targetAddress.targetId()));
        fields.put("targetMode", targetMode == null ? "_" : encodeMode(targetMode));
        fields.put("pathCount", Integer.toString(pathCount));
        fields.put("pathBytes", encoded(pathBytes));
        fields.put("modeCount", Integer.toString(modeCount));
        fields.put("modeBytes", encoded(modeBytes));
        fields.put("sourceCount", Integer.toString(activeSourceModes.size()));
        for (int index = 0; index < activeSourceModes.size(); index++) {
            SourceDevMode source = activeSourceModes.get(index);
            fields.put("source." + index + ".gdi", encoded(source.gdiDeviceName()));
            fields.put("source." + index + ".devmode", encoded(source.devModeBytes()));
        }
        return fields.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    public static WindowsDisplaySnapshot fromRecoveryPayload(String payload) {
        Map<String, String> fields = parsePayload(payload);
        int version = requiredInt(fields, "version");
        if (version != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported Windows display snapshot version " + version);
        }
        int pathCount = requiredInt(fields, "pathCount");
        int modeCount = requiredInt(fields, "modeCount");
        if (pathCount < 1 || pathCount > MAX_PATH_COUNT || modeCount < 0 || modeCount > MAX_MODE_COUNT) {
            throw new IllegalArgumentException("Invalid DisplayConfig array counts");
        }
        int sourceCount = requiredInt(fields, "sourceCount");
        if (sourceCount < 0 || sourceCount > MAX_SOURCE_COUNT) {
            throw new IllegalArgumentException("Invalid source count in Windows display snapshot");
        }
        List<SourceDevMode> sources = new ArrayList<>(sourceCount);
        for (int index = 0; index < sourceCount; index++) {
            sources.add(new SourceDevMode(
                    decodedText(required(fields, "source." + index + ".gdi")),
                    decodedBytesOfExactLength(required(fields, "source." + index + ".devmode"), DEVMODEW_SIZE,
                            "source." + index + ".devmode")
            ));
        }
        return new WindowsDisplaySnapshot(
                decodedText(required(fields, "fingerprint")),
                Integer.parseUnsignedInt(required(fields, "topologyId")),
                decodedText(required(fields, "targetGdi")),
                new DisplayTargetAddress(
                        Long.parseUnsignedLong(required(fields, "targetLuidLow")),
                        Integer.parseUnsignedInt(required(fields, "targetLuidHigh")),
                        Integer.parseUnsignedInt(required(fields, "targetId"))
                ),
                decodeOptionalMode(required(fields, "targetMode")),
                pathCount,
                decodedBytesOfExactLength(required(fields, "pathBytes"),
                        Math.multiplyExact(pathCount, DISPLAYCONFIG_PATH_INFO_SIZE), "pathBytes"),
                modeCount,
                decodedBytesOfExactLength(required(fields, "modeBytes"),
                        Math.multiplyExact(modeCount, DISPLAYCONFIG_MODE_INFO_SIZE), "modeBytes"),
                sources
        );
    }

    static record SourceDevMode(String gdiDeviceName, byte[] devModeBytes) {
        SourceDevMode {
            gdiDeviceName = requireText(gdiDeviceName, "gdiDeviceName");
            devModeBytes = copyExactLength(devModeBytes, DEVMODEW_SIZE, "devModeBytes");
        }

        @Override
        public byte[] devModeBytes() {
            return devModeBytes.clone();
        }
    }

    private static Map<String, String> parsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("Windows display snapshot payload is blank");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : payload.split("\\R")) {
            int separator = line.indexOf('=');
            if (separator < 1 || separator == line.length() - 1 || values.put(line.substring(0, separator),
                    line.substring(separator + 1)) != null) {
                throw new IllegalArgumentException("Malformed Windows display snapshot payload");
            }
        }
        return values;
    }

    private static String encodeMode(DisplayMode mode) {
        return mode.width() + "," + mode.height() + "," + mode.refreshRate().numerator() + ","
                + mode.refreshRate().denominator() + "," + mode.interlaced() + "," + mode.bitsPerPixel() + ","
                + mode.preferred();
    }

    private static DisplayMode decodeMode(String value) {
        String[] fields = value.split(",", -1);
        if (fields.length != 7) {
            throw new IllegalArgumentException("Malformed target mode in Windows display snapshot");
        }
        return new DisplayMode(
                Integer.parseInt(fields[0]),
                Integer.parseInt(fields[1]),
                RefreshRate.of(Long.parseLong(fields[2]), Long.parseLong(fields[3])),
                Boolean.parseBoolean(fields[4]),
                Integer.parseInt(fields[5]),
                Boolean.parseBoolean(fields[6])
        );
    }

    private static DisplayMode decodeOptionalMode(String value) {
        return "_".equals(value) ? null : decodeMode(value);
    }

    private static String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + name + " in Windows display snapshot");
        }
        return value;
    }

    private static int requiredInt(Map<String, String> fields, String name) {
        return Integer.parseInt(required(fields, name));
    }

    private static String encoded(String value) {
        return encoded(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String encoded(byte[] bytes) {
        return bytes.length == 0 ? "_" : Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String decodedText(String value) {
        return new String(decodedBytes(value), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] decodedBytes(String value) {
        if ("_".equals(value)) {
            return new byte[0];
        }
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid base64 in Windows display snapshot", exception);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static byte[] copyExactLength(byte[] value, int expectedLength, String name) {
        if (value == null || value.length != expectedLength) {
            throw new IllegalArgumentException(name + " has unexpected size");
        }
        return value.clone();
    }

    private static byte[] decodedBytesOfExactLength(String value, int expectedLength, String name) {
        if (expectedLength == 0 && "_".equals(value)) {
            return new byte[0];
        }
        int maximumEncodedLength = Math.addExact(Math.multiplyExact((expectedLength + 2) / 3, 4), 4);
        if (value.length() > maximumEncodedLength) {
            throw new IllegalArgumentException(name + " exceeds its expected size");
        }
        byte[] decoded = decodedBytes(value);
        if (decoded.length != expectedLength) {
            throw new IllegalArgumentException(name + " has unexpected size");
        }
        return decoded;
    }

    private void validateTopologyPayload() {
        Memory paths = new Memory(pathBytes.length);
        paths.write(0, pathBytes, 0, pathBytes.length);
        boolean targetAppearsInActivePath = false;
        int pathSize = new WindowsDisplayDiscovery.DisplayConfigPathInfo().size();
        if (pathSize != DISPLAYCONFIG_PATH_INFO_SIZE) {
            throw new IllegalStateException("Unexpected DISPLAYCONFIG_PATH_INFO layout");
        }
        for (int index = 0; index < pathCount; index++) {
            WindowsDisplayDiscovery.DisplayConfigPathInfo path = new WindowsDisplayDiscovery.DisplayConfigPathInfo(
                    paths.share((long) index * pathSize));
            path.read();
            if ((path.flags & DISPLAYCONFIG_PATH_ACTIVE) == 0) {
                throw new IllegalArgumentException("Snapshot contains an inactive DisplayConfig path");
            }
            validateModeIndex(path.sourceInfo.modeInfoIndex, "source", index);
            validateModeIndex(path.targetInfo.modeInfoIndex, "target", index);
            if (path.targetInfo.adapterId.lowPart == (int) targetAddress.adapterLuidLowPart()
                    && path.targetInfo.adapterId.highPart == targetAddress.adapterLuidHighPart()
                    && path.targetInfo.id == targetAddress.targetId()) {
                targetAppearsInActivePath = true;
            }
        }
        if (!targetAppearsInActivePath) {
            throw new IllegalArgumentException("Snapshot topology does not contain its selected target");
        }
        if (targetMode != null && activeSourceModes.stream()
                .noneMatch(source -> source.gdiDeviceName().equalsIgnoreCase(targetGdiDeviceName))) {
            throw new IllegalArgumentException("Snapshot target mode has no matching source DEVMODE");
        }
    }

    private void validateModeIndex(int rawIndex, String endpoint, int pathIndex) {
        if (rawIndex == DISPLAYCONFIG_PATH_MODE_IDX_INVALID) {
            return;
        }
        int modeIndex = rawIndex & 0xFFFF;
        if (modeIndex >= modeCount) {
            throw new IllegalArgumentException("Snapshot " + endpoint + " mode index is outside its mode array for path " + pathIndex);
        }
    }

    private static void validateSourceModes(List<SourceDevMode> sourceModes) {
        java.util.Set<String> seenNames = new java.util.HashSet<>();
        for (SourceDevMode source : sourceModes) {
            if (!seenNames.add(source.gdiDeviceName().toUpperCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("Snapshot contains duplicate GDI source names");
            }
        }
    }
}
