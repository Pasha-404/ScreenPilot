package ru.pavelkuzmin.screenpilot.domain.display;

/** Physical-pixel rectangle in the Windows virtual desktop. */
public record DisplayBounds(int x, int y, int width, int height) {
    public DisplayBounds {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Display bounds must not be negative");
        }
    }
}
