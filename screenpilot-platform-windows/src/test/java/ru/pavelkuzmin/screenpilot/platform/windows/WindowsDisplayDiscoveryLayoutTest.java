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
        WindowsDisplayDiscovery.DisplayConfigDeviceInfoHeader header = new WindowsDisplayDiscovery.DisplayConfigDeviceInfoHeader();
        assertThat(header.nativeOffsetOf("type")).isZero();
        assertThat(header.nativeOffsetOf("size")).isEqualTo(4);
        assertThat(header.nativeOffsetOf("adapterId")).isEqualTo(8);
        assertThat(header.nativeOffsetOf("id")).isEqualTo(16);
        assertThat(new WindowsDisplayDiscovery.TargetName().size()).isEqualTo(420);
        assertThat(new WindowsDisplayDiscovery.SourceName().size()).isEqualTo(84);
        assertThat(new WindowsDisplayDiscovery.DisplayConfig2DRegion().size()).isEqualTo(8);
        assertThat(new WindowsDisplayDiscovery.DisplayConfigVideoSignalInfo().size()).isEqualTo(48);
        assertThat(new WindowsDisplayDiscovery.DisplayConfigTargetMode().size()).isEqualTo(48);
        WindowsDisplayDiscovery.TargetPreferredMode targetPreferredMode = new WindowsDisplayDiscovery.TargetPreferredMode();
        assertThat(targetPreferredMode.size()).isEqualTo(80);
        assertThat(targetPreferredMode.nativeOffsetOf("header")).isZero();
        assertThat(targetPreferredMode.nativeOffsetOf("width")).isEqualTo(20);
        assertThat(targetPreferredMode.nativeOffsetOf("height")).isEqualTo(24);
        assertThat(targetPreferredMode.nativeOffsetOf("targetMode")).isEqualTo(32);
        assertThat(new WindowsDisplayDiscovery.AdvancedColorInfo().size()).isEqualTo(32);
        assertThat(new WindowsDisplayDiscovery.DevModeW().size()).isEqualTo(220);
        assertThat(new WindowsDisplayDiscovery.DisplayDevice().size()).isEqualTo(840);
    }

    @Test
    void classifiesKnownOutputTechnologiesWithoutSelectingADevice() {
        assertThat(WindowsDisplayDiscovery.connectionType(5)).isEqualTo(ConnectionType.HDMI);
        assertThat(WindowsDisplayDiscovery.connectionType(4)).isEqualTo(ConnectionType.DVI);
        assertThat(WindowsDisplayDiscovery.connectionType(0x8000_0000)).isEqualTo(ConnectionType.INTERNAL);
        assertThat(WindowsDisplayDiscovery.connectionType(6)).isEqualTo(ConnectionType.INTERNAL);
        assertThat(WindowsDisplayDiscovery.connectionType(11)).isEqualTo(ConnectionType.INTERNAL);
        assertThat(WindowsDisplayDiscovery.connectionType(13)).isEqualTo(ConnectionType.INTERNAL);
        assertThat(WindowsDisplayDiscovery.isInternalOutputTechnology(6)).isTrue();
        assertThat(WindowsDisplayDiscovery.isInternalOutputTechnology(11)).isTrue();
        assertThat(WindowsDisplayDiscovery.isInternalOutputTechnology(13)).isTrue();
        assertThat(WindowsDisplayDiscovery.isInternalOutputTechnology(5)).isFalse();
    }
}
