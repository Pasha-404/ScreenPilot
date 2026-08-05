package ru.pavelkuzmin.screenpilot.platform.windows;

import org.junit.jupiter.api.Test;
import ru.pavelkuzmin.screenpilot.domain.display.ConnectionType;

import static org.assertj.core.api.Assertions.assertThat;

class WindowsDisplayDiscoveryLayoutTest {

    @Test
    void matchesFixedSizeWindowsSdkStructures() {
        assertThat(new WindowsDisplayDiscovery.Luid().size()).isEqualTo(8);
        assertThat(new WindowsDisplayDiscovery.DisplayConfigPathInfo().size()).isEqualTo(72);
        assertThat(new WindowsDisplayDiscovery.DisplayConfigModeInfo().size()).isEqualTo(64);
        assertThat(new WindowsDisplayDiscovery.DisplayConfigDeviceInfoHeader().size()).isEqualTo(20);
        assertThat(new WindowsDisplayDiscovery.TargetName().size()).isEqualTo(420);
        assertThat(new WindowsDisplayDiscovery.SourceName().size()).isEqualTo(84);
        assertThat(new WindowsDisplayDiscovery.TargetPreferredMode().size()).isEqualTo(40);
        assertThat(new WindowsDisplayDiscovery.AdvancedColorInfo().size()).isEqualTo(32);
        assertThat(new WindowsDisplayDiscovery.DevModeW().size()).isEqualTo(220);
        assertThat(new WindowsDisplayDiscovery.DisplayDevice().size()).isEqualTo(840);
    }

    @Test
    void classifiesKnownOutputTechnologiesWithoutSelectingADevice() {
        assertThat(WindowsDisplayDiscovery.connectionType(5)).isEqualTo(ConnectionType.HDMI);
        assertThat(WindowsDisplayDiscovery.connectionType(4)).isEqualTo(ConnectionType.DVI);
        assertThat(WindowsDisplayDiscovery.connectionType(0x8000_0000)).isEqualTo(ConnectionType.INTERNAL);
    }
}
