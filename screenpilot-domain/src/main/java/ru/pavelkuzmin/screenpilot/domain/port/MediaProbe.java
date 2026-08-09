package ru.pavelkuzmin.screenpilot.domain.port;

import ru.pavelkuzmin.screenpilot.domain.media.MediaInfo;

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/** Best-effort, background-only inspection of a local media file. */
public interface MediaProbe extends AutoCloseable {
    CompletionStage<MediaInfo> probe(Path file);

    /** Stops queued or currently running probes so playback can take priority. */
    void cancelAll();

    @Override
    void close();
}
