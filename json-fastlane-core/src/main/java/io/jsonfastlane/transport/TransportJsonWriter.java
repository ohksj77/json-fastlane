package io.jsonfastlane.transport;

public interface TransportJsonWriter<T> {
    void write(T value, JsonSink sink);
}
