package ru.pavelkuzmin.screenpilot.player.mpv;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Thread-safe JSON IPC client for a local mpv Windows named pipe. The reader is permanently owned
 * by this instance so replies and unsolicited events never race each other or get discarded.
 */
public final class MpvIpcClient implements MpvIpcSession {

    private static final Logger LOG = LoggerFactory.getLogger(MpvIpcClient.class);
    private static final Duration RETRY_INTERVAL = Duration.ofMillis(50);
    private static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(3);

    private final HANDLE pipe;
    private final AtomicLong requestIds = new AtomicLong();
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<JsonNode>> eventListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<IOException>> disconnectListeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService timeouts;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object writeLock = new Object();
    private final Thread readerThread;

    private MpvIpcClient(HANDLE pipe) {
        this.pipe = pipe;
        this.timeouts = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mpv-ipc-timeouts");
            thread.setDaemon(true);
            return thread;
        });
        this.readerThread = new Thread(this::readLoop, "mpv-ipc-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    public static MpvIpcClient connect(String pipeName, Duration timeout) throws IOException {
        Objects.requireNonNull(pipeName, "pipeName");
        validateTimeout(timeout);

        Instant deadline = Instant.now().plus(timeout);
        IOException lastFailure = null;
        while (Instant.now().isBefore(deadline)) {
            HANDLE pipe = Kernel32.INSTANCE.CreateFile(
                    pipeName,
                    WinNT.GENERIC_READ | WinNT.GENERIC_WRITE,
                    0,
                    null,
                    WinNT.OPEN_EXISTING,
                    WinNT.FILE_ATTRIBUTE_NORMAL | WinNT.FILE_FLAG_OVERLAPPED,
                    null
            );
            if (!isInvalid(pipe)) {
                return new MpvIpcClient(pipe);
            }
            lastFailure = new IOException("CreateFile for mpv IPC pipe failed with Win32 error "
                    + Kernel32.INSTANCE.GetLastError());
            sleepBeforeRetry(deadline);
        }
        throw new IOException("mpv IPC pipe did not become available: " + pipeName, lastFailure);
    }

    /** Compatibility helper for the Stage 1 spike; new code should provide its own timeout. */
    public JsonNode command(List<?> command) throws IOException {
        return command(command, DEFAULT_COMMAND_TIMEOUT);
    }

    @Override
    public JsonNode command(List<?> command, Duration timeout) throws IOException {
        validateTimeout(timeout);
        if (closed.get()) {
            throw new IOException("mpv IPC client is closed");
        }
        long requestId = requestIds.incrementAndGet();
        CompletableFuture<JsonNode> reply = new CompletableFuture<>();
        pending.put(requestId, reply);
        timeouts.schedule(() -> {
            if (pending.remove(requestId, reply)) {
                reply.completeExceptionally(new TimeoutException("mpv command timed out after " + timeout));
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);

        try {
            String payload = MpvJsonProtocol.encodeCommand(requestId, command) + "\n";
            synchronized (writeLock) {
                if (closed.get()) {
                    throw new IOException("mpv IPC client is closed");
                }
                write(payload.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception exception) {
            pending.remove(requestId, reply);
            reply.completeExceptionally(exception);
        }

        try {
            return reply.get(timeout.plusMillis(250).toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for mpv command response", exception);
        } catch (TimeoutException exception) {
            pending.remove(requestId, reply);
            throw new IOException("mpv command response timed out after " + timeout, exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("mpv command failed", cause);
        }
    }

    @Override
    public Subscription addEventListener(Consumer<JsonNode> listener) {
        eventListeners.add(Objects.requireNonNull(listener, "listener"));
        return () -> eventListeners.remove(listener);
    }

    @Override
    public Subscription addDisconnectListener(Consumer<IOException> listener) {
        disconnectListeners.add(Objects.requireNonNull(listener, "listener"));
        return () -> disconnectListeners.remove(listener);
    }

    private void readLoop() {
        try {
            while (!closed.get()) {
                dispatch(MpvJsonProtocol.decodeResponse(readUtf8Line()));
            }
        } catch (IOException exception) {
            if (!closed.get()) {
                notifyDisconnected(exception);
            }
        }
    }

    private void dispatch(JsonNode message) {
        long requestId = message.path("request_id").asLong(0);
        CompletableFuture<JsonNode> reply = requestId == 0 ? null : pending.remove(requestId);
        if (reply != null) {
            reply.complete(message);
            return;
        }
        if (message.path("event").isTextual()) {
            for (Consumer<JsonNode> listener : eventListeners) {
                try {
                    listener.accept(message);
                } catch (RuntimeException exception) {
                    LOG.debug("Ignoring failing mpv IPC event listener", exception);
                }
            }
            return;
        }
        LOG.debug("Ignoring uncorrelated mpv IPC message: {}", message);
    }

    private String readUtf8Line() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int next;
        while ((next = readByte()) != -1) {
            if (next == '\n') {
                break;
            }
            if (next != '\r') {
                bytes.write(next);
            }
        }
        if (next == -1 && bytes.size() == 0) {
            throw new IOException("mpv IPC pipe closed before a message was received");
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private void notifyDisconnected(IOException exception) {
        IOException failure = new IOException("mpv IPC pipe disconnected", exception);
        pending.forEach((requestId, future) -> {
            if (pending.remove(requestId, future)) {
                future.completeExceptionally(failure);
            }
        });
        for (Consumer<IOException> listener : disconnectListeners) {
            try {
                listener.accept(failure);
            } catch (RuntimeException listenerFailure) {
                LOG.debug("Ignoring failing mpv IPC disconnect listener", listenerFailure);
            }
        }
    }

    private void write(byte[] bytes) throws IOException {
        int written = transfer("WriteFile", bytes, false);
        if (written != bytes.length) {
            throw new IOException("WriteFile wrote " + written + " of " + bytes.length + " bytes to mpv IPC pipe");
        }
    }

    private int readByte() throws IOException {
        byte[] buffer = new byte[1];
        return transfer("ReadFile", buffer, true) == 0 ? -1 : Byte.toUnsignedInt(buffer[0]);
    }

    /**
     * mpv documents that Windows named-pipe clients need overlapped I/O to send commands while
     * another thread waits for events. Synchronous ReadFile blocks a concurrent WriteFile on this
     * pipe implementation, so every operation owns a separate OVERLAPPED structure and event.
     */
    private int transfer(String operation, byte[] buffer, boolean read) throws IOException {
        HANDLE event = Kernel32.INSTANCE.CreateEvent(null, true, false, null);
        if (isInvalid(event)) {
            throw nativeFailure("CreateEvent for " + operation);
        }
        WinBase.OVERLAPPED overlapped = new WinBase.OVERLAPPED();
        overlapped.hEvent = event;
        overlapped.write();
        IntByReference transferred = new IntByReference();
        Memory nativeBuffer = new Memory(buffer.length);
        if (!read) {
            nativeBuffer.write(0, buffer, 0, buffer.length);
        }
        try {
            boolean completed = read
                    ? OverlappedKernel32.INSTANCE.ReadFile(pipe, nativeBuffer, buffer.length, transferred, overlapped)
                    : OverlappedKernel32.INSTANCE.WriteFile(pipe, nativeBuffer, buffer.length, transferred, overlapped);
            if (!completed && Kernel32.INSTANCE.GetLastError() != WinError.ERROR_IO_PENDING) {
                throw nativeFailure(operation);
            }
            if (!completed && !OverlappedKernel32.INSTANCE.GetOverlappedResult(pipe, overlapped, transferred, true)) {
                throw nativeFailure("GetOverlappedResult after " + operation);
            }
            int bytesTransferred = transferred.getValue();
            if (read && bytesTransferred > 0) {
                nativeBuffer.read(0, buffer, 0, Math.min(bytesTransferred, buffer.length));
            }
            return bytesTransferred;
        } finally {
            Kernel32.INSTANCE.CloseHandle(event);
        }
    }

    private static boolean isInvalid(HANDLE handle) {
        return handle == null || handle.getPointer() == null || Pointer.nativeValue(handle.getPointer()) == -1L;
    }

    private static IOException nativeFailure(String operation) {
        return new IOException(operation + " on mpv IPC pipe failed with Win32 error " + Kernel32.INSTANCE.GetLastError());
    }

    private static void validateTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
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
        if (closed.compareAndSet(false, true)) {
            timeouts.shutdownNow();
            OverlappedKernel32.INSTANCE.CancelIoEx(pipe, null);
            Kernel32.INSTANCE.CloseHandle(pipe);
            readerThread.interrupt();
            notifyDisconnected(new IOException("mpv IPC client was closed"));
        }
    }

    private interface OverlappedKernel32 extends StdCallLibrary {
        OverlappedKernel32 INSTANCE = Native.load("kernel32", OverlappedKernel32.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean ReadFile(HANDLE file, Pointer buffer, int bytesToRead, IntByReference bytesRead,
                         WinBase.OVERLAPPED overlapped);

        boolean WriteFile(HANDLE file, Pointer buffer, int bytesToWrite, IntByReference bytesWritten,
                          WinBase.OVERLAPPED overlapped);

        boolean GetOverlappedResult(HANDLE file, WinBase.OVERLAPPED overlapped,
                                    IntByReference bytesTransferred, boolean wait);

        boolean CancelIoEx(HANDLE file, WinBase.OVERLAPPED overlapped);
    }
}
