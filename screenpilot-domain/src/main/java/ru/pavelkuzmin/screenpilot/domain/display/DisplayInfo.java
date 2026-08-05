package ru.pavelkuzmin.screenpilot.domain.display;

import java.util.List;
import java.util.Objects;

/** Platform-neutral display description used by the application and future UI. */
public record DisplayInfo(
        DisplayId id,
        String friendlyName,
        String manufacturer,
        String model,
        ConnectionType connectionType,
        boolean internal,
        boolean active,
        boolean primary,
        boolean targetAvailable,
        String gdiDeviceName,
        GdiMappingConfidence gdiMappingConfidence,
        DisplayBounds bounds,
        DisplayMode currentMode,
        DisplayMode preferredMode,
        List<DisplayMode> confirmedModes,
        boolean advancedColorSupported,
        String monitorDevicePath,
        DisplayTargetAddress targetAddress
) {
    public DisplayInfo {
        id = Objects.requireNonNull(id, "id");
        friendlyName = safeText(friendlyName, "External display");
        manufacturer = safeText(manufacturer, "Unknown");
        model = safeText(model, "Unknown");
        connectionType = connectionType == null ? ConnectionType.UNKNOWN : connectionType;
        gdiDeviceName = safeText(gdiDeviceName, "");
        gdiMappingConfidence = gdiMappingConfidence == null ? GdiMappingConfidence.UNAVAILABLE : gdiMappingConfidence;
        confirmedModes = confirmedModes == null ? List.of() : List.copyOf(confirmedModes);
        monitorDevicePath = safeText(monitorDevicePath, "");
        targetAddress = Objects.requireNonNull(targetAddress, "targetAddress");
    }

    private static String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
