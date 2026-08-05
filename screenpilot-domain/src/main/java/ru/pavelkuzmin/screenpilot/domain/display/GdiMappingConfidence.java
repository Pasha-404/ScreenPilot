package ru.pavelkuzmin.screenpilot.domain.display;

/** How reliably a Windows display target was associated with its GDI device name. */
public enum GdiMappingConfidence {
    AUTHORITATIVE,
    FALLBACK,
    UNAVAILABLE
}
