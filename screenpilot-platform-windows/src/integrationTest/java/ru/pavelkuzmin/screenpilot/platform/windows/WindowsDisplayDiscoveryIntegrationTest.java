package ru.pavelkuzmin.screenpilot.platform.windows;

import com.sun.jna.Platform;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.pavelkuzmin.screenpilot.domain.display.DisplayInfo;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("hardware")
class WindowsDisplayDiscoveryIntegrationTest {

    @Test
    void discoversTheCurrentWindowsTopologyWithoutMutatingIt() throws Exception {
        assumeTrue(Platform.isWindows(), "Windows display APIs require Windows");

        List<DisplayInfo> displays = new WindowsDisplayDiscovery().discoverDisplays();

        assertThat(displays).isNotEmpty();
        assertThat(displays).anyMatch(DisplayInfo::internal);
        assertThat(displays).allMatch(display -> !display.id().value().isBlank());
    }
}
