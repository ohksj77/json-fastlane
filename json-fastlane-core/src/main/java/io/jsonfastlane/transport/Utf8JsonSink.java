package io.jsonfastlane.transport;

import io.jsonfastlane.Utf8JsonBuffer;

import java.util.Objects;

public final class Utf8JsonSink implements JsonSink {
    private final Utf8JsonBuffer out;

    public Utf8JsonSink(Utf8JsonBuffer out) {
        this.out = Objects.requireNonNull(out, "out");
    }

    @Override
    public JsonSink writeByte(char value) {
        out.writeByte(value);
        return this;
    }

    @Override
    public JsonSink writeSegment(JsonSegment segment) {
        out.writeRaw(segment.bytes());
        return this;
    }

    @Override
    public JsonSink writeLong(long value) {
        out.writeLong(value);
        return this;
    }

    @Override
    public JsonSink writeInt(int value) {
        out.writeInt(value);
        return this;
    }

    @Override
    public JsonSink writeBoolean(boolean value) {
        out.writeBoolean(value);
        return this;
    }

    @Override
    public JsonSink writeNull() {
        out.writeNull();
        return this;
    }

    @Override
    public JsonSink writeString(String value) {
        out.writeString(value);
        return this;
    }

    public Utf8JsonBuffer buffer() {
        return out;
    }
}
