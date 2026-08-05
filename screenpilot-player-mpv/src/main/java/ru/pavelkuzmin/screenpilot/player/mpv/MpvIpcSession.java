package ru.pavelkuzmin.screenpilot.player.mpv;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/** Asynchronous mpv JSON IPC boundary, independently replaceable in adapter tests. */
interface MpvIpcSession extends AutoCloseable {
    JsonNode command(List<?> command, Duration timeout) throws IOException;

    Subscription addEventListener(Consumer<JsonNode> listener);

    Subscription addDisconnectListener(Consumer<IOException> listener);

    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
