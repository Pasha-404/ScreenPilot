package ru.pavelkuzmin.screenpilot.player.mpv;

import java.io.IOException;

@FunctionalInterface
interface MpvProcessStarter {
    Process start(MpvLaunchProfile profile) throws IOException;
}
