package ru.pavelkuzmin.screenpilot.domain.display;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Pure selector implementing ScreenPilot's conservative automatic mode policy. */
public final class DisplayModeSelector {

    private static final double MULTIPLE_TOLERANCE_PERCENT = 0.001;

    private DisplayModeSelector() {
    }

    public static ModeSelectionResult select(
            VideoCharacteristics video,
            DisplayMode current,
            DisplayMode preferred,
            List<DisplayMode> confirmedModes,
            ModeSelectionSettings settings
    ) {
        Objects.requireNonNull(video, "video");
        Objects.requireNonNull(confirmedModes, "confirmedModes");
        settings = settings == null ? ModeSelectionSettings.DEFAULT : settings;

        List<DisplayMode> progressive = confirmedModes.stream()
                .filter(mode -> !mode.interlaced())
                .distinct()
                .toList();
        if (progressive.isEmpty()) {
            throw new IllegalArgumentException("At least one confirmed progressive display mode is required");
        }

        if (video.frameRate().isEmpty()) {
            return selectForUnknownFrameRate(current, preferred, progressive);
        }

        RefreshRate fps = video.frameRate().orElseThrow();
        int bestRank = progressive.stream()
                .mapToInt(mode -> rateRank(mode, fps, current, preferred))
                .min()
                .orElseThrow();
        List<DisplayMode> rateCandidates = progressive.stream()
                .filter(mode -> rateRank(mode, fps, current, preferred) == bestRank)
                .toList();

        if (bestRank >= 1 && !settings.prioritizeSmoothness() && current != null) {
            List<DisplayMode> withoutResolutionLoss = rateCandidates.stream()
                    .filter(mode -> mode.width() >= current.width() && mode.height() >= current.height())
                    .toList();
            if (!withoutResolutionLoss.isEmpty()) {
                rateCandidates = withoutResolutionLoss;
            }
        }

        DisplayMode chosen = chooseResolution(video, preferred, rateCandidates);
        return new ModeSelectionResult(chosen, confidenceFor(bestRank), reasonFor(bestRank), !chosen.equals(current));
    }

    private static ModeSelectionResult selectForUnknownFrameRate(
            DisplayMode current,
            DisplayMode preferred,
            List<DisplayMode> progressive
    ) {
        if (current != null && progressive.contains(current)) {
            return new ModeSelectionResult(
                    current,
                    ModeSelectionConfidence.FALLBACK,
                    ModeSelectionReason.UNKNOWN_FRAME_RATE_PRESERVED_CURRENT,
                    false
            );
        }
        DisplayMode chosen = preferred != null && progressive.contains(preferred)
                ? preferred
                : progressive.stream().max(safeModeComparator()).orElseThrow();
        return new ModeSelectionResult(
                chosen,
                ModeSelectionConfidence.FALLBACK,
                ModeSelectionReason.UNKNOWN_FRAME_RATE_PREFERRED_MODE,
                !chosen.equals(current)
        );
    }

    private static int rateRank(DisplayMode mode, RefreshRate fps, DisplayMode current, DisplayMode preferred) {
        if (mode.refreshRate().isIntegerMultipleOf(fps, 1, 5, MULTIPLE_TOLERANCE_PERCENT)) {
            return 0;
        }
        if (is24FpsCompatibility(fps, mode.refreshRate())) {
            return 1;
        }
        if (current != null && mode.refreshRate().isWithin(current.refreshRate(), 0.02)) {
            return 2;
        }
        if (preferred != null && mode.refreshRate().isWithin(preferred.refreshRate(), 0.02)) {
            return 3;
        }
        return 4;
    }

    private static boolean is24FpsCompatibility(RefreshRate fps, RefreshRate candidate) {
        boolean approximately24 = fps.isWithin(RefreshRate.of(24, 1), 0.02)
                || fps.isWithin(RefreshRate.of(24_000, 1_001), 0.02);
        return approximately24 && (candidate.isWithin(RefreshRate.of(60, 1), 0.02)
                || candidate.isWithin(RefreshRate.of(60_000, 1_001), 0.02));
    }

    private static DisplayMode chooseResolution(
            VideoCharacteristics video,
            DisplayMode preferred,
            List<DisplayMode> candidates
    ) {
        if (preferred != null) {
            List<DisplayMode> nativeResolution = candidates.stream()
                    .filter(mode -> mode.hasResolution(preferred.width(), preferred.height()))
                    .toList();
            if (!nativeResolution.isEmpty()) {
                return nativeResolution.stream().min(Comparator.comparing(DisplayMode::refreshRate)).orElseThrow();
            }
        }

        List<DisplayMode> notSmallerThanVideo = candidates.stream()
                .filter(mode -> mode.width() >= video.width() && mode.height() >= video.height())
                .toList();
        if (!notSmallerThanVideo.isEmpty() && preferred != null) {
            return notSmallerThanVideo.stream()
                    .min(Comparator.comparingLong(mode -> resolutionDistance(mode, preferred)))
                    .orElseThrow();
        }

        List<DisplayMode> exactVideoResolution = candidates.stream()
                .filter(mode -> mode.hasResolution(video.width(), video.height()))
                .toList();
        if (!exactVideoResolution.isEmpty()) {
            return exactVideoResolution.stream().max(safeModeComparator()).orElseThrow();
        }

        return candidates.stream().max(safeModeComparator()).orElseThrow();
    }

    private static long resolutionDistance(DisplayMode mode, DisplayMode preferred) {
        long widthDifference = (long) mode.width() - preferred.width();
        long heightDifference = (long) mode.height() - preferred.height();
        return widthDifference * widthDifference + heightDifference * heightDifference;
    }

    private static Comparator<DisplayMode> safeModeComparator() {
        return Comparator.comparingLong(DisplayMode::pixelCount)
                .thenComparing(DisplayMode::refreshRate)
                .thenComparingInt(DisplayMode::bitsPerPixel);
    }

    private static ModeSelectionConfidence confidenceFor(int rank) {
        return switch (rank) {
            case 0 -> ModeSelectionConfidence.EXACT;
            case 1, 2 -> ModeSelectionConfidence.COMPATIBLE;
            default -> ModeSelectionConfidence.FALLBACK;
        };
    }

    private static ModeSelectionReason reasonFor(int rank) {
        return switch (rank) {
            case 0 -> ModeSelectionReason.EXACT_FRAME_RATE_MATCH;
            case 1 -> ModeSelectionReason.COMPATIBLE_24_FPS_RATE;
            case 2 -> ModeSelectionReason.CURRENT_RATE_PRESERVED;
            case 3 -> ModeSelectionReason.PREFERRED_RATE_USED;
            default -> ModeSelectionReason.HIGHEST_SAFE_RATE_FALLBACK;
        };
    }
}
