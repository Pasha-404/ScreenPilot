package ru.pavelkuzmin.screenpilot.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResponsiveWindowSizeTest {

    @Test
    void leaves_room_for_window_decorations_onA1366By768Laptop() {
        ResponsiveWindowSize size = ResponsiveWindowSize.forAvailableDesktop(1_366, 728);

        assertThat(size.width()).isEqualTo(1_318);
        assertThat(size.height()).isEqualTo(664);
    }

    @Test
    void capsInitialSizeOnLargerDisplays() {
        ResponsiveWindowSize size = ResponsiveWindowSize.forAvailableDesktop(2_560, 1_400);

        assertThat(size.width()).isEqualTo(1_366);
        assertThat(size.height()).isEqualTo(820);
    }

    @Test
    void usesTheAvailableLogicalDesktopInsteadOfForcingTheNormalMinimumOffScreen() {
        ResponsiveWindowSize size = ResponsiveWindowSize.forAvailableDesktop(640, 500);

        assertThat(size.width()).isEqualTo(592);
        assertThat(size.height()).isEqualTo(436);
    }
}
