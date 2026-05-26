package io.jsonfastlane.transport;

import java.util.List;
import java.util.Objects;

public final class JsonTransportPlan {
    private final List<JsonSegment> staticSegments;

    public JsonTransportPlan(List<JsonSegment> staticSegments) {
        this.staticSegments = List.copyOf(Objects.requireNonNull(staticSegments, "staticSegments"));
    }

    public int staticBytes() {
        int bytes = 0;
        for (JsonSegment segment : staticSegments) {
            bytes += segment.length();
        }
        return bytes;
    }

    public int segmentCount() {
        return staticSegments.size();
    }
}
