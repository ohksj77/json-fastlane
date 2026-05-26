package io.jsonfastlane;

public interface FastJsonCodec<T> extends FastJsonReader<T>, FastJsonBufferWriter<T> {
    default byte[] writeToBytes(T value) {
        Utf8JsonBuffer out = new Utf8JsonBuffer();
        write(value, out);
        return out.toByteArray();
    }
}
