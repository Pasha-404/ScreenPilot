package ru.pavelkuzmin.screenpilot.domain.media;

/** State of the best-effort background metadata inspection for a playlist item. */
public enum MediaProbeStatus {
    PENDING,
    READY,
    FAILED
}
