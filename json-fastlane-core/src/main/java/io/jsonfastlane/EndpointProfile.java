package io.jsonfastlane;

import java.util.List;
import java.util.concurrent.atomic.LongAdder;

public final class EndpointProfile {
    private final String endpoint;
    private final LongAdder samples = new LongAdder();
    private final LongAdder totalBytes = new LongAdder();
    private final LongAdder rootObjects = new LongAdder();
    private final LongAdder rootArrays = new LongAdder();
    private final FieldDictionary fieldDictionary;
    private final FieldOrderTracker fieldOrderTracker;

    EndpointProfile(String endpoint, JsonFastlaneOptions options) {
        this.endpoint = endpoint;
        this.fieldDictionary = new FieldDictionary(options);
        this.fieldOrderTracker = new FieldOrderTracker(options);
    }

    void record(JsonShapeObservation observation) {
        samples.increment();
        totalBytes.add(observation.payloadBytes());

        if (observation.rootKind() == JsonValueKind.OBJECT) {
            rootObjects.increment();
        } else if (observation.rootKind() == JsonValueKind.ARRAY) {
            rootArrays.increment();
        }

        recordFields(observation, true);
    }

    void prime(JsonShapeObservation observation) {
        recordFields(observation, false);
    }

    public EndpointProfileSnapshot snapshot() {
        long sampleCount = samples.sum();
        long byteCount = totalBytes.sum();
        return new EndpointProfileSnapshot(
            endpoint,
            sampleCount,
            sampleCount == 0 ? 0 : byteCount / sampleCount,
            rootObjects.sum(),
            rootArrays.sum(),
            fieldDictionary.profileSnapshots(),
            fieldOrderTracker.snapshots(fieldDictionary),
            fieldOrderTracker.droppedFieldOrders()
        );
    }

    JsonShapeScanner.FieldNameResolver fieldNameResolver() {
        return fieldDictionary.fieldNameResolver();
    }

    private void recordFields(JsonShapeObservation observation, boolean countSamples) {
        List<FieldObservation> observedFields = observation.fields();
        int fieldCount = observedFields.size();
        if (fieldCount == 0) {
            return;
        }

        int[] order = FieldOrderTracker.canPack(fieldCount) ? null : new int[fieldCount];
        long packedOrder = order == null ? FieldOrderTracker.initialPackedOrder(fieldCount) : -1;

        for (int i = 0; i < fieldCount; i++) {
            FieldObservation field = observedFields.get(i);
            FieldDictionary.Registration registration = fieldDictionary.registration(field.name());
            int fieldId = registration.id();
            if (order == null) {
                long nextPackedOrder = FieldOrderTracker.appendPackedId(packedOrder, i, fieldId);
                if (nextPackedOrder >= 0) {
                    packedOrder = nextPackedOrder;
                } else {
                    order = new int[fieldCount];
                    FieldOrderTracker.copyPackedIds(packedOrder, order);
                    order[i] = fieldId;
                }
            } else {
                order[i] = fieldId;
            }
            if (countSamples) {
                registration.profile().record(field.kind(), field.position());
            }
        }

        if (countSamples) {
            if (order == null) {
                fieldOrderTracker.recordPacked(packedOrder);
            } else {
                fieldOrderTracker.record(order);
            }
        } else {
            if (order == null) {
                fieldOrderTracker.primePacked(packedOrder);
            } else {
                fieldOrderTracker.prime(order);
            }
        }
    }
}
