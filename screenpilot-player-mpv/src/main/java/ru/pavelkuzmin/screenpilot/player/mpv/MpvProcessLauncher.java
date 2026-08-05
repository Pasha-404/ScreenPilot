package ru.pavelkuzmin.screenpilot.player.mpv;

import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.util.Objects;

/** Starts mpv without a shell, preserving every command-line argument as a separate token. */
public final class MpvProcessLauncher {

    public Process launch(MpvLaunchProfile profile) throws IOException {
        Objects.requireNonNull(profile, "profile");
        return new ProcessBuilder(profile.commandLine())
                .redirectErrorStream(true)
                .redirectOutput(Redirect.DISCARD)
                .start();
    }
}
