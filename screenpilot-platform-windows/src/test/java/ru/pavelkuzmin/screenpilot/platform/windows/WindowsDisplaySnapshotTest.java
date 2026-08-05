package ru.pavelkuzmin.screenpilot.platform.windows;

import org.junit.jupiter.api.Test;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayMode;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayTargetAddress;
import ru.pavelkuzmin.screenpilot.domain.display.RefreshRate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WindowsDisplaySnapshotTest {

    @Test
    void roundTripsLosslessRecoveryPayloadWithoutLeakingNativeStructures() {
        DisplayMode targetMode = new DisplayMode(1920, 1080, RefreshRate.of(60_000, 1_001), false, 32, false);
        WindowsDisplaySnapshot snapshot = new WindowsDisplaySnapshot(
                "topology-fingerprint",
                7,
                "\\\\.\\DISPLAY2",
                new DisplayTargetAddress(42_628, 0, 257),
                targetMode,
                2,
                new byte[144],
                3,
                new byte[192],
                List.of(
                        new WindowsDisplaySnapshot.SourceDevMode("\\\\.\\DISPLAY1", new byte[220]),
                        new WindowsDisplaySnapshot.SourceDevMode("\\\\.\\DISPLAY2", new byte[220])
                )
        );

        WindowsDisplaySnapshot restored = WindowsDisplaySnapshot.fromRecoveryPayload(snapshot.toRecoveryPayload());

        assertThat(restored.topologyFingerprint()).isEqualTo("topology-fingerprint");
        assertThat(restored.topologyId()).isEqualTo(7);
        assertThat(restored.targetGdiDeviceName()).isEqualTo("\\\\.\\DISPLAY2");
        assertThat(restored.targetAddress()).isEqualTo(new DisplayTargetAddress(42_628, 0, 257));
        assertThat(restored.targetMode()).isEqualTo(targetMode);
        assertThat(restored.pathBytes()).containsExactly(new byte[144]);
        assertThat(restored.modeBytes()).containsExactly(new byte[192]);
        assertThat(restored.activeSourceModes()).hasSize(2);
        assertThat(restored.activeSourceModes().get(1).devModeBytes()).containsExactly(new byte[220]);
    }

    @Test
    void rejectsMalformedOrIncompleteRecoveryPayload() {
        assertThatThrownBy(() -> WindowsDisplaySnapshot.fromRecoveryPayload("version=1\nsourceCount=0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint");
        assertThatThrownBy(() -> WindowsDisplaySnapshot.fromRecoveryPayload("version=1\nversion=1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed");
    }

    @Test
    void roundTripsTopologyWithoutModeInfo() {
        WindowsDisplaySnapshot snapshot = new WindowsDisplaySnapshot(
                "topology-fingerprint",
                7,
                "\\\\.\\DISPLAY2",
                new DisplayTargetAddress(42_628, 0, 257),
                new DisplayMode(1920, 1080, RefreshRate.of(60_000, 1_001), false, 32, false),
                2,
                new byte[144],
                0,
                new byte[0],
                List.of(new WindowsDisplaySnapshot.SourceDevMode("\\\\.\\DISPLAY2", new byte[220]))
        );

        WindowsDisplaySnapshot restored = WindowsDisplaySnapshot.fromRecoveryPayload(snapshot.toRecoveryPayload());

        assertThat(restored.modeCount()).isZero();
        assertThat(restored.modeBytes()).isEmpty();
    }

    @Test
    void roundTripsInactiveOriginalTargetWithoutInventingAGdiMode() {
        WindowsDisplaySnapshot snapshot = new WindowsDisplaySnapshot(
                "internal-only-topology",
                1,
                "",
                new DisplayTargetAddress(42_628, 0, 257),
                null,
                1,
                new byte[72],
                1,
                new byte[64],
                List.of(new WindowsDisplaySnapshot.SourceDevMode("\\\\.\\DISPLAY1", new byte[220]))
        );

        WindowsDisplaySnapshot restored = WindowsDisplaySnapshot.fromRecoveryPayload(snapshot.toRecoveryPayload());

        assertThat(restored.targetGdiDeviceName()).isEmpty();
        assertThat(restored.targetMode()).isNull();
        assertThat(restored.hasOriginalTargetMode()).isFalse();
    }
}
