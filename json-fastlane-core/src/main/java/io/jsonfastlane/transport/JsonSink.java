package io.jsonfastlane.transport;

public interface JsonSink {
    JsonSink writeByte(char value);

    JsonSink writeSegment(JsonSegment segment);

    JsonSink writeLong(long value);

    JsonSink writeInt(int value);

    JsonSink writeBoolean(boolean value);

    JsonSink writeNull();

    JsonSink writeString(String value);
}
