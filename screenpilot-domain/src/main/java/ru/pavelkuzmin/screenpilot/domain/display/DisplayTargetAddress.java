package ru.pavelkuzmin.screenpilot.domain.display;

/** Native Windows adapter/target address retained for follow-up display API calls. */
public record DisplayTargetAddress(long adapterLuidLowPart, int adapterLuidHighPart, int targetId) {
    public DisplayTargetAddress {
        if (adapterLuidLowPart < 0 || adapterLuidLowPart > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("adapterLuidLowPart must be an unsigned 32-bit value");
        }
        if (targetId < 0) {
            throw new IllegalArgumentException("targetId must not be negative");
        }
    }
}
