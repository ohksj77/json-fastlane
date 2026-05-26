package io.jsonfastlane;

public record JsonShapeFingerprint(
    long high,
    long low,
    JsonValueKind rootKind,
    int fieldCount,
    int maxKeyDepth
) {
    public boolean sameHash(JsonShapeFingerprint other) {
        return other != null && high == other.high && low == other.low;
    }

    public String hex() {
        return "%016x%016x".formatted(high, low);
    }
}
