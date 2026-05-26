package io.jsonfastlane;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

final class FieldProfile {
    private final String name;
    private final LongAdder occurrences = new LongAdder();
    private final LongAdder totalPosition = new LongAdder();
    private final LongAdder[] kindCounts = new LongAdder[JsonValueKind.values().length];

    FieldProfile(String name) {
        this.name = name;
        for (int i = 0; i < kindCounts.length; i++) {
            kindCounts[i] = new LongAdder();
        }
    }

    void record(JsonValueKind kind, int position) {
        occurrences.increment();
        totalPosition.add(position);
        kindCounts[kind.ordinal()].increment();
    }

    String name() {
        return name;
    }

    FieldProfileSnapshot snapshot() {
        long count = occurrences.sum();
        Map<JsonValueKind, Long> kinds = new EnumMap<>(JsonValueKind.class);
        JsonValueKind[] values = JsonValueKind.values();
        for (int i = 0; i < kindCounts.length; i++) {
            long value = kindCounts[i].sum();
            if (value > 0) {
                kinds.put(values[i], value);
            }
        }

        return new FieldProfileSnapshot(
            name,
            count,
            count == 0 ? 0 : (double) totalPosition.sum() / count,
            kinds
        );
    }
}
