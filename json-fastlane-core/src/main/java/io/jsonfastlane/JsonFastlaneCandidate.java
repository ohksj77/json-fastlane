package io.jsonfastlane;

public record JsonFastlaneCandidate(
    String endpoint,
    long samples,
    long averagePayloadBytes,
    String hotFieldOrder,
    long hotFieldOrderSamples,
    double hotFieldOrderRatio,
    int fieldCount,
    long droppedFieldOrders,
    int score,
    String recommendation
) {
    public boolean recommended() {
        return score >= 70;
    }
}
