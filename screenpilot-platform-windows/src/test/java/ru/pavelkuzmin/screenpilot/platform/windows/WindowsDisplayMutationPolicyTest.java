package ru.pavelkuzmin.screenpilot.platform.windows;

import org.junit.jupiter.api.Test;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayInfo;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WindowsDisplayMutationPolicyTest {

    @Test
    void acceptsOnlyAnExternalTargetOnASeparateExtendedDesktop() {
        List<DisplayInfo> topology = WindowsDisplayTopologyFixtures.internalAndHdmi();

        assertThat(WindowsDisplayMutator.isUsableExtendedTarget(topology.get(1), topology)).isTrue();
    }

    @Test
    void rejectsAClonedExternalTargetThatSharesTheInternalSource() {
        List<DisplayInfo> topology = WindowsDisplayTopologyFixtures.clonedInternalAndHdmi();

        assertThat(WindowsDisplayMutator.isUsableExtendedTarget(topology.get(1), topology)).isFalse();
    }

    @Test
    void rejectsAnInactiveTargetUntilExtendActivatesIt() {
        List<DisplayInfo> topology = WindowsDisplayTopologyFixtures.internalAndInactiveHdmi();

        assertThat(WindowsDisplayMutator.isUsableExtendedTarget(topology.get(1), topology)).isFalse();
    }
}
