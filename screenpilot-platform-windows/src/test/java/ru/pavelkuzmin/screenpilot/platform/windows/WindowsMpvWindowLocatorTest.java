package ru.pavelkuzmin.screenpilot.platform.windows;

import org.junit.jupiter.api.Test;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayBounds;

import static org.assertj.core.api.Assertions.assertThat;

class WindowsMpvWindowLocatorTest {

    @Test
    void acceptsWindowWhoseCentreIsOnSelectedTarget() {
        assertThat(WindowsMpvWindowLocator.centerIsInside(
                new DisplayBounds(1_920, 0, 1_920, 1_080),
                new DisplayBounds(1_920, 0, 1_920, 1_080)
        )).isTrue();
    }

    @Test
    void rejectsWindowWhoseCentreIsOnInternalDisplay() {
        assertThat(WindowsMpvWindowLocator.centerIsInside(
                new DisplayBounds(0, 0, 1_920, 1_080),
                new DisplayBounds(1_920, 0, 1_920, 1_080)
        )).isFalse();
    }
}
