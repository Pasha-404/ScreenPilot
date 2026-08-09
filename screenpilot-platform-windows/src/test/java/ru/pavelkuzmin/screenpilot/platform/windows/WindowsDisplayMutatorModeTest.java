package ru.pavelkuzmin.screenpilot.platform.windows;

import org.junit.jupiter.api.Test;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayMode;
import ru.pavelkuzmin.screenpilot.domain.display.RefreshRate;

import static org.assertj.core.api.Assertions.assertThat;

class WindowsDisplayMutatorModeTest {

    @Test
    void acceptsOnlyTheExactModeReturnedByEnumDisplaySettings() {
        WindowsDisplayDiscovery.DevModeW enumerated = new WindowsDisplayDiscovery.DevModeW();
        enumerated.dmPelsWidth = 640;
        enumerated.dmPelsHeight = 480;
        enumerated.dmBitsPerPel = 32;
        enumerated.dmDisplayFrequency = 119;
        enumerated.dmDisplayFlags = 0;
        DisplayMode requested = new DisplayMode(640, 480, RefreshRate.of(119, 1), false, 32, false);

        assertThat(WindowsDisplayMutator.matchesEnumeratedMode(enumerated, requested)).isTrue();
        assertThat(WindowsDisplayMutator.matchesEnumeratedMode(enumerated,
                new DisplayMode(640, 480, RefreshRate.of(120, 1), false, 32, false))).isFalse();
    }
}
