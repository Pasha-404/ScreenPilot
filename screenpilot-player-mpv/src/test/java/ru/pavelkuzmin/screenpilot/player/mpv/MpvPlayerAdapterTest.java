package ru.pavelkuzmin.screenpilot.player.mpv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import ru.pavelkuzmin.screenpilot.domain.port.ProcessContainment;
import ru.pavelkuzmin.screenpilot.domain.session.PlayerState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MpvPlayerAdapterTest {

    @Test
    void reloadsExactlyOnceWithSoftwareDecodingAfterHardwareDecoderFailure() throws Exception {
        Path media = Files.createTempFile("screenpilot-player-", ".wav");
        try {
            AtomicInteger starts = new AtomicInteger();
            List<Boolean> decoderModes = new ArrayList<>();
            List<FakeIpcSession> sessions = new ArrayList<>();
            MpvProcessStarter starter = profile -> {
                starts.incrementAndGet();
                return new FakeProcess();
            };
            MpvIpcConnector connector = (pipe, timeout) -> {
                FakeIpcSession session = new FakeIpcSession();
                sessions.add(session);
                return session;
            };
            MpvLaunchProfileFactory profiles = (executable, sessionId, softwareDecode) -> {
                decoderModes.add(softwareDecode);
                return MpvLaunchProfile.forPlayer(executable, sessionId, softwareDecode);
            };

            try (MpvPlayerAdapter adapter = new MpvPlayerAdapter(
                    Path.of("vendor/mpv/runtime/mpv.exe"), starter, ProcessContainment.disabled(), connector, profiles)) {
                adapter.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
                var load = adapter.load(media, Duration.ZERO).toCompletableFuture();
                sessions.getFirst().emit("file-loaded", event -> { });
                load.get(2, TimeUnit.SECONDS);
                adapter.pause().toCompletableFuture().get(2, TimeUnit.SECONDS);

                sessions.getFirst().emitLog("warn", "hwdec failed to initialize");
                sessions.getFirst().emitLog("warn", "hwdec failed to initialize again");
                await(() -> starts.get() == 2);
                sessions.get(1).emit("file-loaded", event -> { });
                await(() -> adapter.state() == PlayerState.PAUSED);

                assertThat(decoderModes).containsExactly(false, true);
                assertThat(adapter.state()).isEqualTo(PlayerState.PAUSED);
            }
        } finally {
            Files.deleteIfExists(media);
        }
    }

    @Test
    void completesTheOriginalLoadWhenDecoderFallbackHappensBeforeTheFirstFileLoadedEvent() throws Exception {
        Path media = Files.createTempFile("screenpilot-player-", ".wav");
        try {
            AtomicInteger starts = new AtomicInteger();
            List<FakeIpcSession> sessions = new ArrayList<>();
            MpvProcessStarter starter = profile -> {
                starts.incrementAndGet();
                return new FakeProcess();
            };
            MpvIpcConnector connector = (pipe, timeout) -> {
                FakeIpcSession session = new FakeIpcSession();
                sessions.add(session);
                return session;
            };

            try (MpvPlayerAdapter adapter = new MpvPlayerAdapter(
                    Path.of("vendor/mpv/runtime/mpv.exe"), starter, ProcessContainment.disabled(), connector,
                    MpvLaunchProfile::forPlayer)) {
                adapter.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
                var originalLoad = adapter.load(media, Duration.ZERO).toCompletableFuture();

                sessions.getFirst().emitLog("warn", "hwdec failed to initialize");
                await(() -> starts.get() == 2);
                sessions.get(1).emit("file-loaded", event -> { });

                assertThat(originalLoad.get(2, TimeUnit.SECONDS).source()).isEqualTo(media.toAbsolutePath().normalize());
                assertThat(adapter.state()).isEqualTo(PlayerState.PLAYING);
            }
        } finally {
            Files.deleteIfExists(media);
        }
    }

    @Test
    void explicitlyUnpausesEachNewFileAfterThePlayerReturnsToIdle() throws Exception {
        Path media = Files.createTempFile("screenpilot-player-", ".wav");
        try {
            FakeIpcSession session = new FakeIpcSession();
            MpvProcessStarter starter = profile -> new FakeProcess();
            MpvIpcConnector connector = (pipe, timeout) -> session;

            try (MpvPlayerAdapter adapter = new MpvPlayerAdapter(
                    Path.of("vendor/mpv/runtime/mpv.exe"), starter, ProcessContainment.disabled(), connector,
                    MpvLaunchProfile::forPlayer)) {
                adapter.start().toCompletableFuture().get(2, TimeUnit.SECONDS);

                var firstLoad = adapter.load(media, Duration.ZERO).toCompletableFuture();
                session.emit("file-loaded", event -> { });
                firstLoad.get(2, TimeUnit.SECONDS);
                adapter.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);

                var secondLoad = adapter.load(media, Duration.ZERO).toCompletableFuture();
                session.emit("file-loaded", event -> { });
                secondLoad.get(2, TimeUnit.SECONDS);

                assertThat(adapter.state()).isEqualTo(PlayerState.PLAYING);
                assertThat(session.pauseValues()).containsExactly(false, false);
            }
        } finally {
            Files.deleteIfExists(media);
        }
    }

    @Test
    void refusesToContinueWhenRequiredProcessContainmentCannotBeAttached() {
        FakeProcess process = new FakeProcess();
        ProcessContainment failingContainment = ignored -> {
            throw new IOException("Job Object access denied");
        };

        try (MpvPlayerAdapter adapter = new MpvPlayerAdapter(
                Path.of("vendor/mpv/runtime/mpv.exe"), profile -> process, failingContainment,
                (pipe, timeout) -> {
                    throw new AssertionError("IPC must not be opened for an uncontained output process");
                }, MpvLaunchProfile::forPlayer)) {
            assertThatThrownBy(() -> adapter.start().toCompletableFuture().get(2, TimeUnit.SECONDS))
                    .hasMessageContaining("PLY-001");
            assertThat(process.isAlive()).isFalse();
        }
    }

    private static void await(Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.matches() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(condition.matches()).as("asynchronous adapter condition").isTrue();
    }

    @FunctionalInterface
    private interface Condition {
        boolean matches();
    }

    private static final class FakeIpcSession implements MpvIpcSession {

        private final List<Consumer<JsonNode>> eventListeners = new ArrayList<>();
        private final List<Consumer<IOException>> disconnectListeners = new ArrayList<>();
        private final List<Boolean> pauseValues = new ArrayList<>();

        @Override
        public JsonNode command(List<?> command, Duration timeout) {
            if ("set_property".equals(command.getFirst()) && "pause".equals(command.get(1))) {
                pauseValues.add((Boolean) command.get(2));
            }
            ObjectNode response = JsonNodeFactory.instance.objectNode().put("error", "success");
            if ("get_property".equals(command.getFirst())) {
                response.set("data", property((String) command.get(1)));
            }
            return response;
        }

        @Override
        public Subscription addEventListener(Consumer<JsonNode> listener) {
            eventListeners.add(listener);
            return () -> eventListeners.remove(listener);
        }

        @Override
        public Subscription addDisconnectListener(Consumer<IOException> listener) {
            disconnectListeners.add(listener);
            return () -> disconnectListeners.remove(listener);
        }

        @Override
        public void close() {
            // The fake pipe is inert; its listeners are explicitly unsubscribed by the adapter.
        }

        void emit(String eventName, Consumer<ObjectNode> customize) {
            ObjectNode event = JsonNodeFactory.instance.objectNode().put("event", eventName);
            customize.accept(event);
            for (Consumer<JsonNode> listener : List.copyOf(eventListeners)) {
                listener.accept(event);
            }
        }

        void emitLog(String level, String text) {
            emit("log-message", event -> event.put("level", level).put("text", text));
        }

        List<Boolean> pauseValues() {
            return List.copyOf(pauseValues);
        }

        private static JsonNode property(String name) {
            return switch (name) {
                case "media-title" -> JsonNodeFactory.instance.textNode("Test media");
                case "file-format" -> JsonNodeFactory.instance.textNode("wav");
                case "video-format" -> JsonNodeFactory.instance.nullNode();
                case "video-params" -> JsonNodeFactory.instance.objectNode();
                case "container-fps" -> JsonNodeFactory.instance.nullNode();
                case "track-list" -> JsonNodeFactory.instance.arrayNode();
                default -> JsonNodeFactory.instance.nullNode();
            };
        }
    }

    private static final class FakeProcess extends Process {

        private final CompletableFuture<Process> onExit = new CompletableFuture<>();
        private volatile boolean alive = true;

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive;
        }

        @Override
        public int exitValue() {
            return alive ? 0 : 0;
        }

        @Override
        public void destroy() {
            alive = false;
            onExit.complete(this);
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public CompletableFuture<Process> onExit() {
            return onExit;
        }
    }
}
