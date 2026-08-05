package ru.pavelkuzmin.screenpilot.player.mpv;

import java.nio.file.Path;
import java.util.UUID;

@FunctionalInterface
interface MpvLaunchProfileFactory {
    MpvLaunchProfile create(Path executable, UUID sessionId, boolean softwareDecode);
}
