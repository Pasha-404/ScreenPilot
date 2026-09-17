package ru.pavelkuzmin.screenpilot.app.ui;

/** Calculates a usable initial scene size from the laptop's available desktop area. */
record ResponsiveWindowSize(double width, double height) {

    static final double MIN_WIDTH = 720;
    static final double MIN_HEIGHT = 480;
    static final double ABSOLUTE_MIN_WIDTH = 480;
    static final double ABSOLUTE_MIN_HEIGHT = 360;
    private static final double PREFERRED_WIDTH = 1_366;
    private static final double PREFERRED_HEIGHT = 820;
    private static final double HORIZONTAL_MARGIN = 48;
    private static final double VERTICAL_MARGIN = 64;

    ResponsiveWindowSize {
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("window size must be finite and positive");
        }
    }

    static ResponsiveWindowSize forAvailableDesktop(double availableWidth, double availableHeight) {
        return new ResponsiveWindowSize(
                initialDimension(availableWidth, HORIZONTAL_MARGIN, ABSOLUTE_MIN_WIDTH, PREFERRED_WIDTH),
                initialDimension(availableHeight, VERTICAL_MARGIN, ABSOLUTE_MIN_HEIGHT, PREFERRED_HEIGHT)
        );
    }

    private static double initialDimension(double available, double margin, double minimum, double preferred) {
        if (!Double.isFinite(available) || available <= 0) {
            return preferred;
        }
        return Math.min(preferred, Math.max(minimum, available - margin));
    }
}
