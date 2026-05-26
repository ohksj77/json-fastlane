package io.jsonfastlane.spring;

import io.jsonfastlane.Utf8JsonBuffer;

@FunctionalInterface
public interface JsonBufferFactory {
    Utf8JsonBuffer create();

    static JsonBufferFactory defaultFactory() {
        return () -> new Utf8JsonBuffer(2048);
    }
}
