package io.jsonfastlane;

public interface JsonFastlaneMetricsSink {
    void endpointSampleCount(String endpoint, long samples);

    void endpointAveragePayloadBytes(String endpoint, long averagePayloadBytes);

    void endpointDroppedFieldOrders(String endpoint, long droppedFieldOrders);
}
