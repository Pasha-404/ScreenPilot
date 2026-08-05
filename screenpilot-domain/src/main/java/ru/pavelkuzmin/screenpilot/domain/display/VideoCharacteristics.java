package ru.pavelkuzmin.screenpilot.domain.display;

import java.util.Optional;

/** Video properties needed by the pure display-mode selection algorithm. */
public record VideoCharacteristics(int width, int height, Optional<RefreshRate> frameRate) {
    public VideoCharacteristics {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Video dimensions must be positive");
        }
        frameRate = frameRate == null ? Optional.empty() : frameRate;
    }

    public static VideoCharacteristics withUnknownFrameRate(int width, int height) {
        return new VideoCharacteristics(width, height, Optional.empty());
    }
}
