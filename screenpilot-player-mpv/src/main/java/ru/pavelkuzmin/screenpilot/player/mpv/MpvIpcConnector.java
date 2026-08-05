package ru.pavelkuzmin.screenpilot.player.mpv;

import java.io.IOException;
import java.time.Duration;

@FunctionalInterface
interface MpvIpcConnector {
    MpvIpcSession connect(String pipeName, Duration timeout) throws IOException;
}
