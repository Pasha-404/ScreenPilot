package ru.pavelkuzmin.screenpilot.domain.display;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DisplayModeSelectorTest {

    @Test
    void selectsExact23976ModeWithoutRoundingItTo24() {
        DisplayMode current = mode(1920, 1080, 60_000, 1_001, false);
        DisplayMode exact = mode(1920, 1080, 24_000, 1_001, true);

        ModeSelectionResult result = DisplayModeSelector.select(
                new VideoCharacteristics(1920, 1080, Optional.of(RefreshRate.of(24_000, 1_001))),
                current,
                exact,
                List.of(current, exact),
                ModeSelectionSettings.DEFAULT
        );

        assertThat(result.mode()).isEqualTo(exact);
        assertThat(result.confidence()).isEqualTo(ModeSelectionConfidence.EXACT);
        assertThat(result.reason()).isEqualTo(ModeSelectionReason.EXACT_FRAME_RATE_MATCH);
        assertThat(result.requiresModeChange()).isTrue();
    }

    @Test
    void selects50HertzFor25FramesPerSecond() {
        DisplayMode fifty = mode(1920, 1080, 50, 1, true);
        DisplayMode sixty = mode(1920, 1080, 60, 1, false);

        ModeSelectionResult result = DisplayModeSelector.select(
                new VideoCharacteristics(1920, 1080, Optional.of(RefreshRate.of(25, 1))),
                sixty,
                fifty,
                List.of(fifty, sixty),
                ModeSelectionSettings.DEFAULT
        );

        assertThat(result.mode()).isEqualTo(fifty);
        assertThat(result.confidence()).isEqualTo(ModeSelectionConfidence.EXACT);
    }

    @Test
    void keepsCurrentModeForUnknownFrameRate() {
        DisplayMode current = mode(3840, 2160, 60, 1, true);
        DisplayMode fallback = mode(1920, 1080, 60, 1, false);

        ModeSelectionResult result = DisplayModeSelector.select(
                VideoCharacteristics.withUnknownFrameRate(1920, 1080),
                current,
                fallback,
                List.of(current, fallback),
                ModeSelectionSettings.DEFAULT
        );

        assertThat(result.mode()).isEqualTo(current);
        assertThat(result.requiresModeChange()).isFalse();
        assertThat(result.reason()).isEqualTo(ModeSelectionReason.UNKNOWN_FRAME_RATE_PRESERVED_CURRENT);
    }

    @Test
    void uses60HertzAsCompatibleFallbackFor24FramesPerSecond() {
        DisplayMode current = mode(1920, 1080, 60, 1, true);

        ModeSelectionResult result = DisplayModeSelector.select(
                new VideoCharacteristics(1920, 1080, Optional.of(RefreshRate.of(24, 1))),
                current,
                current,
                List.of(current),
                ModeSelectionSettings.DEFAULT
        );

        assertThat(result.confidence()).isEqualTo(ModeSelectionConfidence.COMPATIBLE);
        assertThat(result.reason()).isEqualTo(ModeSelectionReason.COMPATIBLE_24_FPS_RATE);
    }

    @Test
    void normalizesRationalRatesForExactComparison() {
        assertThat(RefreshRate.of(60_000, 1_001)).isEqualTo(RefreshRate.of(60_000 * 3, 1_001 * 3));
        assertThat(RefreshRate.of(60_000, 1_001).hertz()).isCloseTo(59.94, org.assertj.core.data.Offset.offset(0.01));
    }

    private static DisplayMode mode(int width, int height, long numerator, long denominator, boolean preferred) {
        return new DisplayMode(width, height, RefreshRate.of(numerator, denominator), false, 32, preferred);
    }
}
