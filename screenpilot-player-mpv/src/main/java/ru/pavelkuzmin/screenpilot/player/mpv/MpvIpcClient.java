package ru.pavelkuzmin.screenpilot.player.mpv;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small synchronous client for mpv JSON IPC on a local Windows named pipe.
 * It deliberately supports only the request/response path needed to prove the integration gate.
 */
public final class MpvIpcClient implements AutoCloseable {

    private static final Duration RETRY_INTERVAL = Duration.ofMillis(50);

    private final RandomAccessFile pipe;
    private final AtomicInteger requestIds = new AtomicInteger();

    private MpvIpcClient(RandomAccessFile pipe) {
        this.pipe = pipe;
    }

    public static MpvIpcClient connect(String pipeName, Duration timeout) throws IOException {
        Objects.requireNonNull(pipeName, "pipeName");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }

        Instant deadline = Instant.now().plus(timeout);
        IOException lastFailure = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                return new MpvIpcClient(new RandomAccessFile(pipeName, "rw"));
            } catch (IOException exception) {
                lastFailure = exception;
                sleepBeforeRetry(deadline);
            }
        }
        throw new IOException("mpv IPC pipe did not become available: " + pipeName, lastFailure);
    }

    public JsonNode command(List<?> command) throws IOException {
        int requestId = requestIds.incrementAndGet();
        String payload = MpvJsonProtocol.encodeCommand(requestId, command) + "\n";
        pipe.write(payload.getBytes(StandardCharsets.UTF_8));

        while (true) {
            JsonNode response = MpvJsonProtocol.decodeResponse(readUtf8Line());
            if (response.path("request_id").asInt(-1) == requestId) {
                return response;
            }
        }
    }

    private String readUtf8Line() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int next;
        while ((next = pipe.read()) != -1) {
            if (next == '\n') {
                break;
            }
            if (next != '\r') {
                bytes.write(next);
            }
        }
        if (next == -1 && bytes.size() == 0) {
            throw new IOException("mpv IPC pipe closed before a response was received");
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static void sleepBeforeRetry(Instant deadline) throws IOException {
        long remainingMillis = Duration.between(Instant.now(), deadline).toMillis();
        if (remainingMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(Math.min(RETRY_INTERVAL.toMillis(), remainingMillis));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for mpv IPC pipe", exception);
        }
    }

    @Override
    public void close() throws IOException {
        pipe.close();
    }
}
