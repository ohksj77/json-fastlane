package io.jsonfastlane;

import java.util.Collection;

public final class JsonFastlaneReport {
    private JsonFastlaneReport() {
    }

    public static String text(Collection<EndpointProfileSnapshot> snapshots) {
        StringBuilder report = new StringBuilder();
        report.append("json-fastlane report\n");
        for (EndpointProfileSnapshot snapshot : snapshots) {
            report
                .append("- ")
                .append(snapshot.endpoint())
                .append(": samples=")
                .append(snapshot.samples())
                .append(", avgBytes=")
                .append(snapshot.averagePayloadBytes())
                .append(", fields=")
                .append(snapshot.fields().size())
                .append(", retainedOrders=")
                .append(snapshot.fieldOrders().size())
                .append(", droppedOrders=")
                .append(snapshot.droppedFieldOrders())
                .append('\n');
            if (!snapshot.fieldOrders().isEmpty()) {
                FieldOrderSnapshot order = snapshot.fieldOrders().get(0);
                report
                    .append("  hotOrder=")
                    .append(order.signature())
                    .append(" hits=")
                    .append(order.samples())
                    .append('\n');
            }
        }
        return report.toString();
    }

    public static void emitMetrics(
        Collection<EndpointProfileSnapshot> snapshots,
        JsonFastlaneMetricsSink sink
    ) {
        for (EndpointProfileSnapshot snapshot : snapshots) {
            sink.endpointSampleCount(snapshot.endpoint(), snapshot.samples());
            sink.endpointAveragePayloadBytes(snapshot.endpoint(), snapshot.averagePayloadBytes());
            sink.endpointDroppedFieldOrders(snapshot.endpoint(), snapshot.droppedFieldOrders());
        }
    }
}
