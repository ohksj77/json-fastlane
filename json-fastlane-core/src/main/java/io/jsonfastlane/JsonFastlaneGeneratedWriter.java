package io.jsonfastlane;

import io.jsonfastlane.transport.JsonSegment;
import io.jsonfastlane.transport.JsonSink;
import io.jsonfastlane.transport.TransportJsonWriter;

public interface JsonFastlaneGeneratedWriter<T> extends FastJsonBufferWriter<T>, TransportJsonWriter<T> {
    ExpectedJsonShape expectedShape();

    default JsonShapeMatcher shapeMatcher() {
        return expectedShape().compileMatcher();
    }

    default void write(T value, JsonSink sink) {
        Utf8JsonBuffer out = new Utf8JsonBuffer();
        write(value, out);
        sink.writeSegment(JsonSegment.owned(out.toByteArray()));
    }
}
