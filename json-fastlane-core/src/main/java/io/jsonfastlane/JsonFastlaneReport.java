package io.jsonfastlane;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

public final class JsonFastlaneReport {
    private static final long MIN_RECOMMENDED_SAMPLES = 100;
    private static final double MIN_RECOMMENDED_HOT_ORDER_RATIO = 0.90;

    private JsonFastlaneReport() {
    }

    public static List<JsonFastlaneCandidate> candidates(Collection<EndpointProfileSnapshot> snapshots) {
        return snapshots.stream()
            .map(JsonFastlaneReport::candidate)
            .sorted(Comparator.comparingInt(JsonFastlaneCandidate::score).reversed()
                .thenComparing(JsonFastlaneCandidate::endpoint))
            .toList();
    }

    public static String text(Collection<EndpointProfileSnapshot> snapshots) {
        StringBuilder report = new StringBuilder();
        report.append("json-fastlane report\n");
        List<JsonFastlaneCandidate> candidates = candidates(snapshots);
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
        if (!candidates.isEmpty()) {
            report.append("fast-path candidates\n");
            for (JsonFastlaneCandidate candidate : candidates) {
                report
                    .append("- ")
                    .append(candidate.endpoint())
                    .append(": score=")
                    .append(candidate.score())
                    .append(", hotOrderRatio=")
                    .append(percent(candidate.hotFieldOrderRatio()))
                    .append(", samples=")
                    .append(candidate.samples())
                    .append(", recommendation=")
                    .append(candidate.recommendation())
                    .append('\n');
            }
        }
        return report.toString();
    }

    public static String json(Collection<EndpointProfileSnapshot> snapshots) {
        StringBuilder report = new StringBuilder();
        List<EndpointProfileSnapshot> orderedSnapshots = snapshots.stream()
            .sorted(Comparator.comparing(EndpointProfileSnapshot::endpoint))
            .toList();
        List<JsonFastlaneCandidate> candidates = candidates(snapshots);

        report.append("{\n");
        report.append("  \"endpoints\": [\n");
        for (int i = 0; i < orderedSnapshots.size(); i++) {
            appendEndpointJson(report, orderedSnapshots.get(i));
            if (i + 1 < orderedSnapshots.size()) {
                report.append(',');
            }
            report.append('\n');
        }
        report.append("  ],\n");
        report.append("  \"candidates\": [\n");
        for (int i = 0; i < candidates.size(); i++) {
            appendCandidateJson(report, candidates.get(i));
            if (i + 1 < candidates.size()) {
                report.append(',');
            }
            report.append('\n');
        }
        report.append("  ]\n");
        report.append("}\n");
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

    private static JsonFastlaneCandidate candidate(EndpointProfileSnapshot snapshot) {
        FieldOrderSnapshot hotOrder = snapshot.fieldOrders().isEmpty()
            ? new FieldOrderSnapshot("", 0)
            : snapshot.fieldOrders().get(0);
        double hotOrderRatio = snapshot.samples() == 0 ? 0.0 : (double) hotOrder.samples() / snapshot.samples();
        int score = score(snapshot, hotOrderRatio);
        return new JsonFastlaneCandidate(
            snapshot.endpoint(),
            snapshot.samples(),
            snapshot.averagePayloadBytes(),
            hotOrder.signature(),
            hotOrder.samples(),
            hotOrderRatio,
            snapshot.fields().size(),
            snapshot.droppedFieldOrders(),
            score,
            recommendation(snapshot, hotOrderRatio, score)
        );
    }

    private static int score(EndpointProfileSnapshot snapshot, double hotOrderRatio) {
        int score = 0;
        score += Math.min(30, (int) (snapshot.samples() * 30 / MIN_RECOMMENDED_SAMPLES));
        score += (int) Math.round(hotOrderRatio * 40);
        score += Math.min(20, (int) (snapshot.averagePayloadBytes() / 64));
        if (snapshot.fields().isEmpty()) {
            score -= 20;
        }
        if (snapshot.droppedFieldOrders() > 0) {
            score -= 20;
        }
        if (snapshot.rootObjects() < snapshot.samples()) {
            score -= 10;
        }
        return Math.max(0, Math.min(100, score));
    }

    private static String recommendation(EndpointProfileSnapshot snapshot, double hotOrderRatio, int score) {
        if (snapshot.samples() < MIN_RECOMMENDED_SAMPLES) {
            return "observe-more-samples";
        }
        if (snapshot.fields().isEmpty()) {
            return "not-object-shape";
        }
        if (snapshot.droppedFieldOrders() > 0) {
            return "increase-retained-orders-or-stabilize-shape";
        }
        if (hotOrderRatio < MIN_RECOMMENDED_HOT_ORDER_RATIO) {
            return "shape-too-variable";
        }
        if (score >= 70) {
            return "generate-fast-path";
        }
        return "low-estimated-impact";
    }

    private static String percent(double value) {
        return Math.round(value * 1000.0) / 10.0 + "%";
    }

    private static void appendEndpointJson(StringBuilder report, EndpointProfileSnapshot snapshot) {
        report.append("    {")
            .append("\"endpoint\":\"").append(escapeJson(snapshot.endpoint())).append("\",")
            .append("\"samples\":").append(snapshot.samples()).append(',')
            .append("\"averagePayloadBytes\":").append(snapshot.averagePayloadBytes()).append(',')
            .append("\"rootObjects\":").append(snapshot.rootObjects()).append(',')
            .append("\"rootArrays\":").append(snapshot.rootArrays()).append(',')
            .append("\"droppedFieldOrders\":").append(snapshot.droppedFieldOrders()).append(',')
            .append("\"fieldOrders\":[");
        for (int i = 0; i < snapshot.fieldOrders().size(); i++) {
            FieldOrderSnapshot order = snapshot.fieldOrders().get(i);
            report.append('{')
                .append("\"signature\":\"").append(escapeJson(order.signature())).append("\",")
                .append("\"samples\":").append(order.samples())
                .append('}');
            if (i + 1 < snapshot.fieldOrders().size()) {
                report.append(',');
            }
        }
        report.append("],\"fields\":[");
        for (int i = 0; i < snapshot.fields().size(); i++) {
            FieldProfileSnapshot field = snapshot.fields().get(i);
            report.append('{')
                .append("\"name\":\"").append(escapeJson(field.name())).append("\",")
                .append("\"occurrences\":").append(field.occurrences()).append(',')
                .append("\"averagePosition\":").append(field.averagePosition()).append(',')
                .append("\"valueKinds\":{");
            int kindIndex = 0;
            for (var entry : field.valueKinds().entrySet()) {
                report.append("\"").append(entry.getKey()).append("\":").append(entry.getValue());
                if (++kindIndex < field.valueKinds().size()) {
                    report.append(',');
                }
            }
            report.append("}}");
            if (i + 1 < snapshot.fields().size()) {
                report.append(',');
            }
        }
        report.append("]}");
    }

    private static void appendCandidateJson(StringBuilder report, JsonFastlaneCandidate candidate) {
        report.append("    {")
            .append("\"endpoint\":\"").append(escapeJson(candidate.endpoint())).append("\",")
            .append("\"samples\":").append(candidate.samples()).append(',')
            .append("\"averagePayloadBytes\":").append(candidate.averagePayloadBytes()).append(',')
            .append("\"hotFieldOrder\":\"").append(escapeJson(candidate.hotFieldOrder())).append("\",")
            .append("\"hotFieldOrderSamples\":").append(candidate.hotFieldOrderSamples()).append(',')
            .append("\"hotFieldOrderRatio\":").append(candidate.hotFieldOrderRatio()).append(',')
            .append("\"fieldCount\":").append(candidate.fieldCount()).append(',')
            .append("\"droppedFieldOrders\":").append(candidate.droppedFieldOrders()).append(',')
            .append("\"score\":").append(candidate.score()).append(',')
            .append("\"recommended\":").append(candidate.recommended()).append(',')
            .append("\"recommendation\":\"").append(escapeJson(candidate.recommendation())).append("\"")
            .append('}');
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (current < 0x20) {
                        escaped.append("\\u00");
                        escaped.append(Character.forDigit((current >> 4) & 0xf, 16));
                        escaped.append(Character.forDigit(current & 0xf, 16));
                    } else {
                        escaped.append(current);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
