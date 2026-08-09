package ru.pavelkuzmin.screenpilot.player.mpv;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable command-line profile for the separate mpv process used by the Stage 1 spike.
 * The UI and display-selection logic intentionally do not live here yet.
 */
public record MpvLaunchProfile(Path executable, String ipcPipe, String windowTitle, List<String> arguments) {

    private static final String WINDOWS_PIPE_PREFIX = "\\\\.\\pipe\\";

    public MpvLaunchProfile {
        executable = Objects.requireNonNull(executable, "executable").toAbsolutePath().normalize();
        ipcPipe = requireText(ipcPipe, "ipcPipe");
        windowTitle = requireText(windowTitle, "windowTitle");
        arguments = List.copyOf(arguments);

        if (!ipcPipe.startsWith(WINDOWS_PIPE_PREFIX)) {
            throw new IllegalArgumentException("mpv IPC must use a local Windows named pipe");
        }
    }

    public static MpvLaunchProfile forSpike(Path executable, UUID sessionId) {
        return forSpike(executable, sessionId, null);
    }

    public static MpvLaunchProfile forSpike(Path executable, UUID sessionId, Integer targetScreen) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (targetScreen != null && targetScreen < 0) {
            throw new IllegalArgumentException("targetScreen must be zero or greater");
        }

        MpvLaunchProfile profile = create(executable, sessionId, "ScreenPilot mpv spike ", false);
        List<String> arguments = new ArrayList<>(profile.arguments());
        if (targetScreen != null) {
            arguments.add("--screen=" + targetScreen);
            arguments.add("--fullscreen");
            arguments.add("--fs-screen=" + targetScreen);
        }
        return new MpvLaunchProfile(profile.executable(), profile.ipcPipe(), profile.windowTitle(), arguments);
    }

    /** Production player profile. A caller may explicitly opt into a known mpv screen number. */
    public static MpvLaunchProfile forPlayer(Path executable, UUID sessionId, boolean softwareDecode) {
        return forPlayer(executable, sessionId, softwareDecode, null);
    }

    /**
     * Production player profile placed fullscreen on a manually verified mpv screen when requested.
     * The numeric screen mapping is intentionally supplied by the composition root: Windows display
     * discovery identifies the physical target, while mpv owns its own screen numbering.
     */
    public static MpvLaunchProfile forPlayer(
            Path executable,
            UUID sessionId,
            boolean softwareDecode,
            Integer targetScreen
    ) {
        if (targetScreen != null && targetScreen < 0) {
            throw new IllegalArgumentException("targetScreen must be zero or greater");
        }
        MpvLaunchProfile profile = create(executable, sessionId, "ScreenPilot Output ", softwareDecode);
        List<String> arguments = new ArrayList<>(profile.arguments());
        if (targetScreen != null) {
            arguments.add("--screen=" + targetScreen);
            arguments.add("--fullscreen");
            arguments.add("--fs-screen=" + targetScreen);
        }
        return new MpvLaunchProfile(profile.executable(), profile.ipcPipe(), profile.windowTitle(), arguments);
    }

    /** Headless local-file probe: no window, video output, audio output or frame playback. */
    static MpvLaunchProfile forMetadataProbe(Path executable, UUID sessionId) {
        MpvLaunchProfile profile = create(executable, sessionId, "ScreenPilot probe ", false);
        List<String> arguments = new ArrayList<>(profile.arguments());
        arguments.remove("--force-window=immediate");
        arguments.remove("--no-border");
        arguments.remove("--ontop=yes");
        arguments.remove("--vo=gpu-next");
        arguments.remove("--gpu-context=d3d11");
        arguments.remove("--hwdec=auto-safe");
        arguments.add("--vo=null");
        arguments.add("--ao=null");
        arguments.add("--pause=yes");
        return new MpvLaunchProfile(profile.executable(), profile.ipcPipe(), profile.windowTitle(), arguments);
    }

    /** Compatibility name retained for the existing real-mpv integration test. */
    static MpvLaunchProfile forHeadlessTest(Path executable, UUID sessionId) {
        MpvLaunchProfile profile = create(executable, sessionId, "ScreenPilot test ", false);
        List<String> arguments = new ArrayList<>(profile.arguments());
        arguments.add("--vo=null");
        arguments.add("--ao=null");
        return new MpvLaunchProfile(profile.executable(), profile.ipcPipe(), profile.windowTitle(), arguments);
    }

    private static MpvLaunchProfile create(Path executable, UUID sessionId, String titlePrefix, boolean softwareDecode) {
        Objects.requireNonNull(sessionId, "sessionId");
        String suffix = sessionId.toString();
        String pipe = WINDOWS_PIPE_PREFIX + "screenpilot-mpv-" + suffix;
        String title = titlePrefix + suffix;
        List<String> arguments = new ArrayList<>(List.of(
                "--no-config",
                "--idle=yes",
                "--force-window=immediate",
                "--keep-open=yes",
                "--terminal=no",
                "--input-default-bindings=no",
                "--osc=no",
                "--no-border",
                "--ontop=yes",
                "--vo=gpu-next",
                "--gpu-context=d3d11",
                "--hwdec=" + (softwareDecode ? "no" : "auto-safe"),
                "--audio-client-name=ScreenPilot",
                "--input-ipc-server=" + pipe,
                "--title=" + title
        ));
        return new MpvLaunchProfile(executable, pipe, title, arguments);
    }

    public List<String> commandLine() {
        List<String> commandLine = new ArrayList<>(arguments.size() + 1);
        commandLine.add(executable.toString());
        commandLine.addAll(arguments);
        return List.copyOf(commandLine);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
