package ru.pavelkuzmin.screenpilot.domain.display;

import java.util.Objects;

/** A positive refresh or frame rate represented without losing 1000/1001 precision. */
public record RefreshRate(long numerator, long denominator) implements Comparable<RefreshRate> {

    public RefreshRate {
        if (numerator <= 0 || denominator <= 0) {
            throw new IllegalArgumentException("Refresh rate values must be positive");
        }
        long gcd = greatestCommonDivisor(numerator, denominator);
        numerator /= gcd;
        denominator /= gcd;
    }

    public static RefreshRate of(long numerator, long denominator) {
        return new RefreshRate(numerator, denominator);
    }

    public double hertz() {
        return (double) numerator / denominator;
    }

    public boolean isWithin(RefreshRate other, double toleranceHertz) {
        Objects.requireNonNull(other, "other");
        if (toleranceHertz < 0) {
            throw new IllegalArgumentException("toleranceHertz must not be negative");
        }
        return Math.abs(hertz() - other.hertz()) <= toleranceHertz;
    }

    public boolean isIntegerMultipleOf(RefreshRate other, int minMultiplier, int maxMultiplier, double tolerancePercent) {
        Objects.requireNonNull(other, "other");
        if (minMultiplier < 1 || maxMultiplier < minMultiplier || tolerancePercent < 0) {
            throw new IllegalArgumentException("Invalid multiple matching bounds");
        }
        double ratio = hertz() / other.hertz();
        for (int multiplier = minMultiplier; multiplier <= maxMultiplier; multiplier++) {
            if (Math.abs(ratio - multiplier) / multiplier <= tolerancePercent) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int compareTo(RefreshRate other) {
        return Double.compare(hertz(), other.hertz());
    }

    private static long greatestCommonDivisor(long left, long right) {
        while (right != 0) {
            long remainder = left % right;
            left = right;
            right = remainder;
        }
        return left;
    }
}
