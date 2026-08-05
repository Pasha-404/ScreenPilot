package ru.pavelkuzmin.screenpilot.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.jna.Platform;
import ru.pavelkuzmin.screenpilot.platform.windows.WindowsJobObject;
import ru.pavelkuzmin.screenpilot.player.mpv.MpvIpcClient;
import ru.pavelkuzmin.screenpilot.player.mpv.MpvLaunchProfile;
import ru.pavelkuzmin.screenpilot.player.mpv.MpvProcessLauncher;

import java.io.IOException;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Manually-run Stage 1 acceptance probe. It starts a real mpv.exe, verifies JSON IPC and
 * reads the actual audio-device list. It does not make any display or audio configuration changes.
 */
public final class MpvSpikeMain {

    private static final Duration IPC_TIMEOUT = Duration.ofSeconds(12);

    private MpvSpikeMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (!Platform.isWindows()) {
            System.out.println("[FAIL] mpv spike supports Windows only.");
            return 1;
        }

        Path mpvExecutable = resolveMpvExecutable(args);
        if (!Files.isRegularFile(mpvExecutable)) {
            System.out.println("[NOT TESTED] mpv executable was not found: " + mpvExecutable);
            System.out.println("Set SCREENPILOT_MPV_PATH or pass the full path as the first argument.");
            return 2;
        }

        Integer targetScreen = screenArgument(args);
        Duration holdDuration = holdDurationArgument(args);
        int repetitions = repetitionsArgument(args);
        List<Path> mediaFiles = mediaArguments(args);
        Path embeddedSubtitleMedia = embeddedSubtitleMediaArgument(args);
        if (targetScreen != null) {
            System.out.println("[INFO] mpv will request fullscreen on screen index " + targetScreen + ".");
        }
        boolean withoutJobObject = Arrays.asList(args).contains("--without-job-object");
        boolean crashAfterJobObject = Arrays.asList(args).contains("--crash-after-job-object");
        if (withoutJobObject && crashAfterJobObject) {
            System.out.println("[FAIL] --crash-after-job-object requires the normal Job Object path.");
            return 1;
        }
        if (withoutJobObject) {
            System.out.println("[NOT TESTED] Windows Job Object containment was explicitly skipped for this diagnostic run.");
        }

        for (int attempt = 1; attempt <= repetitions; attempt++) {
            MpvLaunchProfile profile = MpvLaunchProfile.forSpike(mpvExecutable, UUID.randomUUID(), targetScreen);
            System.out.println("[INFO] mpv launch " + attempt + "/" + repetitions + ".");
            int result = withoutJobObject
                    ? runMpvProbe(profile, null, mediaFiles, embeddedSubtitleMedia, holdDuration)
                    : runWithJobObject(profile, mediaFiles, embeddedSubtitleMedia, holdDuration, crashAfterJobObject);
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    private static int runWithJobObject(
            MpvLaunchProfile profile,
            List<Path> mediaFiles,
            Path embeddedSubtitleMedia,
            Duration holdDuration,
            boolean crashAfterJobObject
    ) {
        Process mpv = null;
        try (WindowsJobObject job = WindowsJobObject.create()) {
            mpv = new MpvProcessLauncher().launch(profile);
            job.assign(mpv);
            if (crashAfterJobObject) {
                System.out.println("[INFO] Job Object assigned. Halting the Java process to test KILL_ON_JOB_CLOSE.");
                System.out.flush();
                Runtime.getRuntime().halt(86);
            }
            return runMpvProbe(profile, mpv, mediaFiles, embeddedSubtitleMedia, holdDuration);
        } catch (Exception exception) {
            System.out.println("[FAIL] mpv spike: " + exception.getMessage());
            return 1;
        } finally {
            if (mpv != null && mpv.isAlive()) {
                mpv.destroyForcibly();
            }
        }
    }

    private static int runMpvProbe(
            MpvLaunchProfile profile,
            Process alreadyLaunched,
            List<Path> mediaFiles,
            Path embeddedSubtitleMedia,
            Duration holdDuration
    ) {
        Process mpv = alreadyLaunched;
        try {
            if (mpv == null) {
                mpv = new MpvProcessLauncher().launch(profile);
            }
            try (ProbeFixture fixture = ProbeFixture.create();
                 MpvIpcClient ipc = MpvIpcClient.connect(profile.ipcPipe(), IPC_TIMEOUT)) {
                JsonNode version = command(ipc, "mpv-version", List.of("get_property", "mpv-version"));
                JsonNode audioDevices = command(ipc, "audio-device-list", List.of("get_property", "audio-device-list"));
                command(ipc, "loadfile", List.of("loadfile", fixture.audio().toString(), "replace"));
                Thread.sleep(300);
                JsonNode duration = command(ipc, "duration", List.of("get_property", "duration"));
                command(ipc, "pause", List.of("set_property", "pause", true));
                JsonNode paused = command(ipc, "pause state", List.of("get_property", "pause"));
                command(ipc, "seek", List.of("seek", 1.0, "absolute"));
                JsonNode timePosition = command(ipc, "time-pos", List.of("get_property", "time-pos"));
                command(ipc, "sub-add", List.of("sub-add", fixture.subtitle().toString(), "select"));

                if (!paused.path("data").asBoolean(false)) {
                    throw new IOException("mpv did not enter paused state");
                }
                if (!timePosition.path("data").isNumber()) {
                    throw new IOException("mpv did not return a numeric time-pos: " + timePosition);
                }

                System.out.println("[PASS] mpv started and JSON IPC responded. Version: "
                        + version.path("data").asText("unknown"));
                System.out.println("[PASS] loadfile, pause, seek and time-pos succeeded. Duration: " + duration.path("data"));
                System.out.println("[PASS] external SRT subtitle was accepted by mpv.");
                System.out.println("[PASS] mpv returned audio-device-list: " + audioDevices.path("data"));
                probeAudioDeviceSelection(ipc, audioDevices);
                for (Path media : mediaFiles) {
                    probeVideoPlayback(ipc, media);
                }
                if (embeddedSubtitleMedia != null) {
                    probeEmbeddedSubtitle(ipc, embeddedSubtitleMedia);
                }
                if (!holdDuration.isZero()) {
                    System.out.println("[INFO] keeping mpv visible for " + holdDuration.toMillis() + " ms.");
                    Thread.sleep(holdDuration);
                }
                if (profile.arguments().stream().anyMatch(argument -> argument.startsWith("--screen="))) {
                    System.out.println("[INFO] Confirm manually that the full-screen mpv window appeared only on the selected external screen.");
                } else {
                    System.out.println("[NOT TESTED] HDMI targeting and exclusive full-screen require a connected display.");
                }
                System.out.println("[NOT TESTED] HDR and physical audio output require compatible test equipment.");
            }
            return 0;
        } catch (Exception exception) {
            System.out.println("[FAIL] mpv IPC diagnostic: " + exception.getMessage());
            return 1;
        } finally {
            if (mpv != null && mpv.isAlive()) {
                mpv.destroyForcibly();
            }
        }
    }

    private static Path resolveMpvExecutable(String[] args) {
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                return Path.of(arg).toAbsolutePath().normalize();
            }
        }
        String environmentPath = System.getenv("SCREENPILOT_MPV_PATH");
        if (environmentPath != null && !environmentPath.isBlank()) {
            return Path.of(environmentPath).toAbsolutePath().normalize();
        }

        Path currentDirectory = Path.of("").toAbsolutePath().normalize();
        for (Path candidateRoot = currentDirectory; candidateRoot != null; candidateRoot = candidateRoot.getParent()) {
            Path candidate = candidateRoot.resolve(Path.of("vendor", "mpv", "runtime", "mpv.exe"));
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return currentDirectory.resolve(Path.of("vendor", "mpv", "runtime", "mpv.exe"));
    }

    private static List<Path> mediaArguments(String[] args) {
        return Arrays.stream(args)
                .filter(argument -> argument.startsWith("--media="))
                .map(argument -> Path.of(argument.substring("--media=".length())).toAbsolutePath().normalize())
                .toList();
    }

    private static Path embeddedSubtitleMediaArgument(String[] args) {
        return Arrays.stream(args)
                .filter(argument -> argument.startsWith("--embedded-subtitle-media="))
                .findFirst()
                .map(argument -> Path.of(argument.substring("--embedded-subtitle-media=".length()))
                        .toAbsolutePath()
                        .normalize())
                .orElse(null);
    }

    private static Integer screenArgument(String[] args) {
        return Arrays.stream(args)
                .filter(argument -> argument.startsWith("--screen="))
                .findFirst()
                .map(argument -> parseNonNegativeInt(argument, "--screen="))
                .orElse(null);
    }

    private static Duration holdDurationArgument(String[] args) {
        long milliseconds = Arrays.stream(args)
                .filter(argument -> argument.startsWith("--hold-ms="))
                .findFirst()
                .map(argument -> parseNonNegativeInt(argument, "--hold-ms="))
                .orElse(0);
        if (milliseconds > 30_000) {
            throw new IllegalArgumentException("--hold-ms must not exceed 30000");
        }
        return Duration.ofMillis(milliseconds);
    }

    private static int repetitionsArgument(String[] args) {
        int repetitions = Arrays.stream(args)
                .filter(argument -> argument.startsWith("--repeat="))
                .findFirst()
                .map(argument -> parseNonNegativeInt(argument, "--repeat="))
                .orElse(1);
        if (repetitions == 0 || repetitions > 10) {
            throw new IllegalArgumentException("--repeat must be between 1 and 10");
        }
        return repetitions;
    }

    private static int parseNonNegativeInt(String argument, String prefix) {
        try {
            int value = Integer.parseInt(argument.substring(prefix.length()));
            if (value < 0) {
                throw new IllegalArgumentException(prefix + " value must be zero or greater");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid value for " + prefix + argument.substring(prefix.length()), exception);
        }
    }

    private static void assertMpvSuccess(String operation, JsonNode response) throws IOException {
        String error = response.path("error").asText("missing error field");
        if (!"success".equals(error)) {
            throw new IOException("mpv command " + operation + " failed: " + response);
        }
    }

    private static JsonNode command(MpvIpcClient ipc, String operation, List<?> command) throws IOException {
        JsonNode response = ipc.command(command);
        assertMpvSuccess(operation, response);
        return response;
    }

    private static void probeVideoPlayback(MpvIpcClient ipc, Path media) throws IOException, InterruptedException {
        if (!Files.isRegularFile(media)) {
            throw new IOException("Media fixture was not found: " + media);
        }
        command(ipc, "loadfile " + media.getFileName(), List.of("loadfile", media.toString(), "replace"));
        command(ipc, "unpause", List.of("set_property", "pause", false));
        Thread.sleep(750);
        JsonNode videoParameters = command(ipc, "video-params", List.of("get_property", "video-params"));
        JsonNode hardwareDecoder = command(ipc, "hwdec-current", List.of("get_property", "hwdec-current"));
        command(ipc, "pause after media probe", List.of("set_property", "pause", true));

        if (!videoParameters.path("data").isObject()) {
            throw new IOException("mpv did not expose video parameters for " + media.getFileName() + ": " + videoParameters);
        }
        System.out.println("[PASS] video loaded and decoded: " + media.getFileName()
                + "; video-params=" + videoParameters.path("data")
                + "; hwdec-current=" + hardwareDecoder.path("data"));
    }

    private static void probeAudioDeviceSelection(MpvIpcClient ipc, JsonNode audioDevices) throws IOException {
        JsonNode deviceName = audioDevices.path("data")
                .findValues("name")
                .stream()
                .map(JsonNode::asText)
                .filter(name -> name.startsWith("wasapi/"))
                .findFirst()
                .map(name -> com.fasterxml.jackson.databind.node.TextNode.valueOf(name))
                .orElse(null);
        if (deviceName == null) {
            System.out.println("[NOT TESTED] no explicit WASAPI audio device is currently available for mpv-only switching.");
            return;
        }

        String selectedName = deviceName.asText();
        command(ipc, "set audio-device", List.of("set_property", "audio-device", selectedName));
        JsonNode selected = command(ipc, "get audio-device", List.of("get_property", "audio-device"));
        if (!selectedName.equals(selected.path("data").asText())) {
            throw new IOException("mpv did not retain the requested audio device: " + selected);
        }
        System.out.println("[PASS] mpv switched its own audio-device to " + selectedName + "; Windows default audio was not changed.");
    }

    private static void probeEmbeddedSubtitle(MpvIpcClient ipc, Path media) throws IOException, InterruptedException {
        if (!Files.isRegularFile(media)) {
            throw new IOException("Embedded subtitle fixture was not found: " + media);
        }
        command(ipc, "loadfile " + media.getFileName(), List.of("loadfile", media.toString(), "replace"));
        Thread.sleep(300);
        JsonNode trackList = command(ipc, "track-list", List.of("get_property", "track-list"));
        JsonNode subtitleTrack = null;
        for (JsonNode track : trackList.path("data")) {
            if ("sub".equals(track.path("type").asText())) {
                subtitleTrack = track;
                break;
            }
        }
        if (subtitleTrack == null || !subtitleTrack.path("id").canConvertToInt()) {
            throw new IOException("mpv did not expose an embedded subtitle track: " + trackList);
        }

        int trackId = subtitleTrack.path("id").asInt();
        command(ipc, "set sid", List.of("set_property", "sid", trackId));
        JsonNode selectedId = command(ipc, "sid", List.of("get_property", "sid"));
        if (!selectedId.path("data").canConvertToInt() || selectedId.path("data").asInt() != trackId) {
            throw new IOException("mpv did not retain the selected embedded subtitle: " + selectedId);
        }
        System.out.println("[PASS] embedded subtitle track was exposed and selected: id=" + trackId
                + "; track=" + subtitleTrack);
    }

    private record ProbeFixture(Path audio, Path subtitle) implements AutoCloseable {

        private static final int SAMPLE_RATE = 8_000;
        private static final int CHANNELS = 1;
        private static final int BITS_PER_SAMPLE = 16;
        private static final int DURATION_SECONDS = 3;

        static ProbeFixture create() throws IOException {
            Path audio = Files.createTempFile("screenpilot-mpv-spike-", ".wav");
            Path subtitle = Files.createTempFile("screenpilot-mpv-spike-", ".srt");
            writeSilentWav(audio);
            Files.writeString(subtitle, "1\n00:00:00,000 --> 00:00:02,000\nScreenPilot IPC probe\n");
            return new ProbeFixture(audio, subtitle);
        }

        @Override
        public void close() throws IOException {
            Files.deleteIfExists(audio);
            Files.deleteIfExists(subtitle);
        }

        private static void writeSilentWav(Path target) throws IOException {
            int bytesPerSample = BITS_PER_SAMPLE / 8;
            int dataLength = SAMPLE_RATE * DURATION_SECONDS * CHANNELS * bytesPerSample;
            int byteRate = SAMPLE_RATE * CHANNELS * bytesPerSample;
            try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(target))) {
                output.writeBytes("RIFF");
                writeLittleEndianInt(output, 36 + dataLength);
                output.writeBytes("WAVEfmt ");
                writeLittleEndianInt(output, 16);
                writeLittleEndianShort(output, 1);
                writeLittleEndianShort(output, CHANNELS);
                writeLittleEndianInt(output, SAMPLE_RATE);
                writeLittleEndianInt(output, byteRate);
                writeLittleEndianShort(output, CHANNELS * bytesPerSample);
                writeLittleEndianShort(output, BITS_PER_SAMPLE);
                output.writeBytes("data");
                writeLittleEndianInt(output, dataLength);
                output.write(new byte[dataLength]);
            }
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
}
