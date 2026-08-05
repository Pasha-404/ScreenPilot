package ru.pavelkuzmin.screenpilot.domain.display;

import java.util.List;
import java.util.Objects;

/** Immutable notification emitted when the significant Windows display topology changes. */
public record DisplayTopologyChanged(String fingerprint, List<DisplayInfo> displays) {
    public DisplayTopologyChanged {
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("fingerprint must not be blank");
        }
        displays = List.copyOf(Objects.requireNonNull(displays, "displays"));
    }
}
