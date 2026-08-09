package ru.pavelkuzmin.screenpilot.player.mpv;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MpvLaunchProfileTest {

    @Test
    void createsUniqueLocalPipeAndSafeCommandTokens() {
        UUID session = UUID.fromString("f77dffb5-082b-4e87-b2cf-e6dc550249ef");
        MpvLaunchProfile profile = MpvLaunchProfile.forSpike(Path.of("vendor/mpv/runtime/mpv.exe"), session);

        assertThat(profile.ipcPipe()).isEqualTo("\\\\.\\pipe\\screenpilot-mpv-" + session);
        assertThat(profile.commandLine())
                .contains("--no-config", "--vo=gpu-next", "--gpu-context=d3d11", "--hwdec=auto-safe")
                .contains("--input-ipc-server=" + profile.ipcPipe())
                .doesNotContain("cmd.exe", "/c");
        assertThat(profile.commandLine().getFirst()).endsWith("vendor\\mpv\\runtime\\mpv.exe");
    }

    @Test
    void placesFullscreenWindowOnExplicitMpvScreenOnlyWhenRequested() {
        MpvLaunchProfile profile = MpvLaunchProfile.forSpike(
                Path.of("vendor/mpv/runtime/mpv.exe"), UUID.randomUUID(), 1);

        assertThat(profile.arguments())
                .contains("--screen=1", "--fullscreen", "--fs-screen=1");
    }

    @Test
    void placesProductionPlayerOnExplicitMpvScreenOnlyWhenRequested() {
        MpvLaunchProfile explicit = MpvLaunchProfile.forPlayer(
                Path.of("vendor/mpv/runtime/mpv.exe"), UUID.randomUUID(), false, 1);
        MpvLaunchProfile defaultPlacement = MpvLaunchProfile.forPlayer(
                Path.of("vendor/mpv/runtime/mpv.exe"), UUID.randomUUID(), false);

        assertThat(explicit.arguments())
                .contains("--screen=1", "--fullscreen", "--fs-screen=1");
        assertThat(defaultPlacement.arguments())
                .doesNotContain("--screen=1", "--fullscreen", "--fs-screen=1");
    }

    @Test
    void metadataProbeHasNoWindowOrMediaOutput() {
        MpvLaunchProfile profile = MpvLaunchProfile.forMetadataProbe(
                Path.of("vendor/mpv/runtime/mpv.exe"), UUID.randomUUID());

        assertThat(profile.arguments())
                .contains("--no-config", "--vo=null", "--ao=null", "--pause=yes")
                .doesNotContain("--force-window=immediate", "--vo=gpu-next", "--gpu-context=d3d11");
    }
}
