package ru.pavelkuzmin.screenpilot.platform.windows;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WindowsDisplayMutatorFlagsTest {

    @Test
    void restoresTheSavedDatabaseTopologyWithoutAllowingWindowsToChangeIt() {
        assertThat(WindowsDisplayMutator.databaseCurrentFlags()).isEqualTo(0x0000_008F);
    }
}
