package ru.pavelkuzmin.screenpilot.platform.windows;

import org.junit.jupiter.api.Test;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayBounds;

import static org.assertj.core.api.Assertions.assertThat;

class WindowsMpvWindowLocatorTest {

    @Test
    void acceptsAWindowThatExactlyCoversASelectedPhysicalTarget() {
        assertThat(WindowsMpvWindowLocator.exactlyMatches(
                new DisplayBounds(1_920, 0, 1_920, 1_080),
                new DisplayBounds(1_920, 0, 1_920, 1_080)
        )).isTrue();
    }

    @Test
    void rejectsAWindowThatOnlyHasItsCentreOnTheSelectedTarget() {
        assertThat(WindowsMpvWindowLocator.exactlyMatches(
                new DisplayBounds(960, 0, 1_920, 1_080),
                new DisplayBounds(1_920, 0, 1_920, 1_080)
        )).isFalse();
    }

    @Test
    void rejectsAWindowWithLogicalSizedBoundsOnAMixedDpiTarget() {
        assertThat(WindowsMpvWindowLocator.exactlyMatches(
                new DisplayBounds(-1_536, 0, 1_536, 864),
                new DisplayBounds(-1_920, 0, 1_920, 1_080)
        )).isFalse();
    }
}
