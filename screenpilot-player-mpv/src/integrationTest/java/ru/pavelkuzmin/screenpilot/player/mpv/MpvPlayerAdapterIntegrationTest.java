package ru.pavelkuzmin.screenpilot.player.mpv;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import ru.pavelkuzmin.screenpilot.domain.media.MediaInfo;
import ru.pavelkuzmin.screenpilot.domain.port.ProcessContainment;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Uses the locally provisioned mpv runtime with null audio/video outputs and changes no Windows settings. */
@Tag("integration")
@EnabledOnOs(OS.WINDOWS)
class MpvPlayerAdapterIntegrationTest {

    private static final int MAX_METADATA_PROBE_ATTEMPTS = 3;

    @Test
    void controlsRealMpvThroughThePlayerAdapterWithoutAnExternalDisplay() throws Exception {
        Path executable = findMpvExecutable();
        assumeTrue(Files.isRegularFile(executable), "locally provisioned mpv runtime is required");
        Path media = Files.createTempFile("screenpilot-adapter-", ".wav");
        writeSilentWav(media, 20);

        try (MpvPlayerAdapter adapter = new MpvPlayerAdapter(
                executable,
                new MpvProcessLauncher(),
                ProcessContainment.disabled(),
                MpvIpcClient::connect,
                (path, sessionId, softwareDecode) -> MpvLaunchProfile.forHeadlessTest(path, sessionId))) {
            adapter.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
            var info = adapter.load(media, Duration.ZERO).toCompletableFuture().get(15, TimeUnit.SECONDS);
            adapter.pause().toCompletableFuture().get(5, TimeUnit.SECONDS);
            adapter.seek(Duration.ofSeconds(1)).toCompletableFuture().get(5, TimeUnit.SECONDS);
            adapter.play().toCompletableFuture().get(5, TimeUnit.SECONDS);
            adapter.stop().toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertThat(info.source()).isEqualTo(media.toAbsolutePath().normalize());
            assertThat(info.container()).contains("wav");
            assertThat(adapter.state().name()).isEqualTo("IDLE");
        } finally {
            deleteAfterMpvRelease(media);
        }
    }

    @Test
    void readsMetadataInASeparateHeadlessProbeWithoutAnExternalDisplay() throws Exception {
        Path executable = findMpvExecutable();
        assumeTrue(Files.isRegularFile(executable), "locally provisioned mpv runtime is required");
        Path media = Files.createTempFile("screenpilot-probe-", ".wav");
        writeSilentWav(media, 20);

        try {
            var info = probeMetadataWithTransientStartupRetry(executable, media);

            assertThat(info.source()).isEqualTo(media.toAbsolutePath().normalize());
            assertThat(info.container()).contains("wav");
            assertThat(info.duration()).contains(Duration.ofSeconds(20));
        } finally {
            deleteAfterMpvRelease(media);
        }
    }

    /**
     * A freshly started mpv can briefly create then close its IPC pipe on a busy Windows VM before
     * its second start succeeds. This is a real-process test, not a skipped assertion: only that
     * narrowly identified startup transport failure is retried and the final attempt still fails
     * with the original error if mpv cannot serve the metadata request.
     */
    private static MediaInfo probeMetadataWithTransientStartupRetry(
            Path executable,
            Path media
    ) throws Exception {
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= MAX_METADATA_PROBE_ATTEMPTS; attempt++) {
            try (MpvMediaProbe probe = new MpvMediaProbe(executable)) {
                return probe.probe(media).toCompletableFuture().get(15, TimeUnit.SECONDS);
            } catch (Exception exception) {
                lastFailure = exception;
                if (!isTransientPipeStartupFailure(exception) || attempt == MAX_METADATA_PROBE_ATTEMPTS) {
                    throw exception;
                }
                TimeUnit.MILLISECONDS.sleep(250L * attempt);
            }
        }
        throw lastFailure;
    }

    private static boolean isTransientPipeStartupFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof IOException && current.getMessage() != null
                    && (current.getMessage().contains("mpv IPC pipe disconnected")
                    || current.getMessage().contains("mpv IPC pipe did not become available")
                    || current.getMessage().contains("mpv IPC pipe closed before a message"))) {
                return true;
            }
        }
        return false;
    }

    private static Path findMpvExecutable() {
        for (Path current = Path.of("").toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            Path candidate = current.resolve(Path.of("vendor", "mpv", "runtime", "mpv.exe"));
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return Path.of("vendor", "mpv", "runtime", "mpv.exe").toAbsolutePath().normalize();
    }

    private static void writeSilentWav(Path target, int seconds) throws IOException {
        int sampleRate = 8_000;
        int channels = 1;
        int bitsPerSample = 16;
        int bytesPerSample = bitsPerSample / 8;
        int dataLength = sampleRate * seconds * channels * bytesPerSample;
        int byteRate = sampleRate * channels * bytesPerSample;
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(target))) {
            output.writeBytes("RIFF");
            writeLittleEndianInt(output, 36 + dataLength);
            output.writeBytes("WAVEfmt ");
            writeLittleEndianInt(output, 16);
            writeLittleEndianShort(output, 1);
            writeLittleEndianShort(output, channels);
            writeLittleEndianInt(output, sampleRate);
            writeLittleEndianInt(output, byteRate);
            writeLittleEndianShort(output, channels * bytesPerSample);
            writeLittleEndianShort(output, bitsPerSample);
            output.writeBytes("data");
            writeLittleEndianInt(output, dataLength);
            output.write(new byte[dataLength]);
        }
    }

    private static void deleteAfterMpvRelease(Path media) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                Files.deleteIfExists(media);
                return;
            } catch (IOException exception) {
                lastFailure = exception;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw exception;
                }
            }
        }
        throw lastFailure;
    }

    private static void writeLittleEndianInt(DataOutputStream output, int value) throws IOException {
        output.writeByte(value & 0xFF);
        output.writeByte((value >>> 8) & 0xFF);
        output.writeByte((value >>> 16) & 0xFF);
        output.writeByte((value >>> 24) & 0xFF);
    }

    private static void writeLittleEndianShort(DataOutputStream output, int value) throws IOException {
        output.writeByte(value & 0xFF);
        output.writeByte((value >>> 8) & 0xFF);
    }
}
