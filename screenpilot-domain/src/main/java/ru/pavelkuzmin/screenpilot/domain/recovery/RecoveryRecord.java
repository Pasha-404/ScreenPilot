package ru.pavelkuzmin.screenpilot.domain.recovery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Persistent marker for a display mutation that has not yet been confirmed as restored. */
public record RecoveryRecord(
        int schemaVersion,
        UUID sessionId,
        Instant startedAt,
        String topologyPayload,
        String checksum
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public RecoveryRecord {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        if (topologyPayload == null || topologyPayload.isBlank()) {
            throw new IllegalArgumentException("topologyPayload must not be blank");
        }
        checksum = Objects.requireNonNull(checksum, "checksum");
    }

    public static RecoveryRecord begin(UUID sessionId, Instant startedAt, String topologyPayload) {
        return new RecoveryRecord(CURRENT_SCHEMA_VERSION, sessionId, startedAt, topologyPayload, checksumFor(topologyPayload));
    }

    public boolean hasValidChecksum() {
        return checksumFor(topologyPayload).equalsIgnoreCase(checksum);
    }

    private static String checksumFor(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK SHA-256 implementation is unavailable", exception);
        }
    }
}
