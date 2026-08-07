package ru.pavelkuzmin.screenpilot.app.ui;

import javafx.stage.Screen;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayBounds;

import java.util.List;
import java.util.Optional;

/** Produces only a candidate mpv screen number; Win32 later verifies the real mpv window. */
final class MpvScreenResolver {

    private MpvScreenResolver() {
    }

    static Optional<Integer> resolve(DisplayBounds target, List<Screen> screens) {
        if (target == null || screens == null || screens.isEmpty() || target.width() == 0 || target.height() == 0) {
            return Optional.empty();
        }
        double centerX = target.x() + target.width() / 2.0;
        double centerY = target.y() + target.height() / 2.0;
        List<Integer> candidates = java.util.stream.IntStream.range(0, screens.size())
                .filter(index -> contains(screens.get(index), centerX, centerY))
                .boxed()
                .toList();
        return candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
    }

    private static boolean contains(Screen screen, double x, double y) {
        javafx.geometry.Rectangle2D bounds = screen.getBounds();
        return x >= bounds.getMinX() && x < bounds.getMaxX() && y >= bounds.getMinY() && y < bounds.getMaxY();
    }
}
