package io.jsonfastlane;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

import java.util.Collection;

public final class JsonFastlaneJfr {
    private JsonFastlaneJfr() {
    }

    public static void emit(Collection<EndpointProfileSnapshot> snapshots) {
        for (EndpointProfileSnapshot snapshot : snapshots) {
            EndpointSnapshotEvent event = new EndpointSnapshotEvent();
            event.endpoint = snapshot.endpoint();
            event.samples = snapshot.samples();
            event.averagePayloadBytes = snapshot.averagePayloadBytes();
            event.fieldCount = snapshot.fields().size();
            event.retainedFieldOrders = snapshot.fieldOrders().size();
            event.droppedFieldOrders = snapshot.droppedFieldOrders();
            event.commit();
        }
    }

    @Name("io.jsonfastlane.EndpointSnapshot")
    @Label("JSON Fastlane Endpoint Snapshot")
    @Category({"JSON Fastlane", "Profiler"})
    static final class EndpointSnapshotEvent extends Event {
        @Label("Endpoint")
        String endpoint;

        @Label("Samples")
        long samples;

        @Label("Average Payload Bytes")
        long averagePayloadBytes;

        @Label("Field Count")
        int fieldCount;

        @Label("Retained Field Orders")
        int retainedFieldOrders;

        @Label("Dropped Field Orders")
        long droppedFieldOrders;
    }
}
