package ru.pavelkuzmin.screenpilot.domain.display;

/** Stable identifier assembled from monitor path and EDID when Windows makes them available. */
public record DisplayId(String value) {
    public DisplayId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Display ID must not be blank");
        }
    }
}
