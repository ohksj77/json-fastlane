package io.jsonfastlane.netty;

import io.jsonfastlane.transport.JsonSegment;
import io.jsonfastlane.transport.JsonSink;
import io.netty.buffer.ByteBuf;

import java.util.Objects;

public final class NettyJsonSink implements JsonSink {
    private final NettyJsonBuffer out;

    public NettyJsonSink(ByteBuf out) {
        this.out = new NettyJsonBuffer(Objects.requireNonNull(out, "out"));
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
}
