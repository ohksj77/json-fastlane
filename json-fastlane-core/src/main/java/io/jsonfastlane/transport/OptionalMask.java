package io.jsonfastlane.transport;

public final class OptionalMask {
    private OptionalMask() {
    }

    public static int setIfPresent(int mask, int bit, Object value) {
        return value == null ? mask : mask | (1 << bit);
    }

    public static boolean has(int mask, int bit) {
        return (mask & (1 << bit)) != 0;
    }
}
