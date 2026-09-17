package ru.pavelkuzmin.screenpilot.platform.windows;

import com.sun.jna.Memory;
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
        DisplayTargetAddress target = new DisplayTargetAddress(42_628, 0, 257);
        byte[] paths = validPathBytes(target, 2);
        byte[] modes = new byte[192];
        WindowsDisplaySnapshot snapshot = new WindowsDisplaySnapshot(
                "topology-fingerprint",
                7,
                "\\\\.\\DISPLAY2",
                target,
                targetMode,
                2,
                paths,
                3,
                modes,
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
        assertThat(restored.pathBytes()).containsExactly(paths);
        assertThat(restored.modeBytes()).containsExactly(modes);
        assertThat(restored.activeSourceModes()).hasSize(2);
        assertThat(restored.activeSourceModes().get(1).devModeBytes()).containsExactly(new byte[220]);
    }

    @Test
    void rejectsMalformedOrIncompleteRecoveryPayload() {
        assertThatThrownBy(() -> WindowsDisplaySnapshot.fromRecoveryPayload("version=1\nsourceCount=0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WindowsDisplaySnapshot.fromRecoveryPayload("version=1\nversion=1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed");
    }

    @Test
    void roundTripsTopologyWithoutModeInfo() {
        DisplayTargetAddress target = new DisplayTargetAddress(42_628, 0, 257);
        WindowsDisplaySnapshot snapshot = new WindowsDisplaySnapshot(
                "topology-fingerprint",
                7,
                "\\\\.\\DISPLAY2",
                target,
                new DisplayMode(1920, 1080, RefreshRate.of(60_000, 1_001), false, 32, false),
                2,
                validPathBytes(target, 2),
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
        DisplayTargetAddress target = new DisplayTargetAddress(42_628, 0, 257);
        WindowsDisplaySnapshot snapshot = new WindowsDisplaySnapshot(
                "internal-only-topology",
                1,
                "",
                target,
                null,
                1,
                validPathBytes(target, 1),
                1,
                new byte[64],
                List.of(new WindowsDisplaySnapshot.SourceDevMode("\\\\.\\DISPLAY1", new byte[220]))
        );

        WindowsDisplaySnapshot restored = WindowsDisplaySnapshot.fromRecoveryPayload(snapshot.toRecoveryPayload());

        assertThat(restored.targetGdiDeviceName()).isEmpty();
        assertThat(restored.targetMode()).isNull();
        assertThat(restored.hasOriginalTargetMode()).isFalse();
    }

    @Test
    void rejectsPayloadWhoseTargetIsNotPresentInTheRestoredTopology() {
        DisplayTargetAddress target = new DisplayTargetAddress(42_628, 0, 257);
        WindowsDisplaySnapshot snapshot = new WindowsDisplaySnapshot(
                "topology-fingerprint", 7, "", target, null, 1,
                validPathBytes(target, 1), 0, new byte[0],
                List.of(new WindowsDisplaySnapshot.SourceDevMode("\\\\.\\DISPLAY1", new byte[220]))
        );

        String tampered = snapshot.toRecoveryPayload().replace("targetId=257", "targetId=258");

        assertThatThrownBy(() -> WindowsDisplaySnapshot.fromRecoveryPayload(tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not contain its selected target");
    }

    @Test
    void rejectsPayloadWithAnArrayLengthThatDoesNotMatchItsDeclaredNativeLayout() {
        DisplayTargetAddress target = new DisplayTargetAddress(42_628, 0, 257);
        WindowsDisplaySnapshot snapshot = new WindowsDisplaySnapshot(
                "topology-fingerprint", 7, "", target, null, 1,
                validPathBytes(target, 1), 0, new byte[0],
                List.of(new WindowsDisplaySnapshot.SourceDevMode("\\\\.\\DISPLAY1", new byte[220]))
        );

        String tampered = snapshot.toRecoveryPayload().replace("pathCount=1", "pathCount=2");

        assertThatThrownBy(() -> WindowsDisplaySnapshot.fromRecoveryPayload(tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pathBytes");
    }

    private static byte[] validPathBytes(DisplayTargetAddress target, int count) {
        int pathSize = new WindowsDisplayDiscovery.DisplayConfigPathInfo().size();
        Memory memory = new Memory((long) pathSize * count);
        for (int index = 0; index < count; index++) {
            WindowsDisplayDiscovery.DisplayConfigPathInfo path = new WindowsDisplayDiscovery.DisplayConfigPathInfo(
                    memory.share((long) index * pathSize));
            path.sourceInfo.adapterId.lowPart = (int) target.adapterLuidLowPart();
            path.sourceInfo.adapterId.highPart = target.adapterLuidHighPart();
            path.sourceInfo.id = index;
            path.sourceInfo.modeInfoIndex = -1;
            path.targetInfo.adapterId.lowPart = (int) target.adapterLuidLowPart();
            path.targetInfo.adapterId.highPart = target.adapterLuidHighPart();
            path.targetInfo.id = index == 0 ? target.targetId() : target.targetId() + index;
            path.targetInfo.modeInfoIndex = -1;
            path.flags = 1;
            path.write();
        }
        return memory.getByteArray(0, Math.toIntExact((long) pathSize * count));
    }
}
