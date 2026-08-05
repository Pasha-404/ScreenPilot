package ru.pavelkuzmin.screenpilot.domain.display;

import java.util.Objects;

/** A mode confirmed by Windows and the display driver. */
public record DisplayMode(
        int width,
        int height,
        RefreshRate refreshRate,
        boolean interlaced,
        int bitsPerPixel,
        boolean preferred
) {
    public DisplayMode {
        if (width < 1 || height < 1 || bitsPerPixel < 1) {
            throw new IllegalArgumentException("Display mode dimensions and bit depth must be positive");
        }
        refreshRate = Objects.requireNonNull(refreshRate, "refreshRate");
    }

    public long pixelCount() {
        return (long) width * height;
    }

    public boolean hasResolution(int candidateWidth, int candidateHeight) {
        return width == candidateWidth && height == candidateHeight;
    }
}
