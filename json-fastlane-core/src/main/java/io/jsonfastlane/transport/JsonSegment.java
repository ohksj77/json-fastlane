package io.jsonfastlane.transport;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

public final class JsonSegment {
    private final byte[] bytes;

    private JsonSegment(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes, "bytes");
    }

    public static JsonSegment ascii(String value) {
        Objects.requireNonNull(value, "value");
        byte[] bytes = new byte[value.length()];
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current > 0x7f) {
                throw new IllegalArgumentException("JSON static segment must be ASCII");
            }
            bytes[i] = (byte) current;
        }
        return new JsonSegment(bytes);
    }

    public static JsonSegment bytes(byte[] bytes) {
        return new JsonSegment(Arrays.copyOf(bytes, bytes.length));
    }

    public static JsonSegment owned(byte[] bytes) {
        return new JsonSegment(Objects.requireNonNull(bytes, "bytes"));
    }

    public static JsonSegment utf8(String value) {
        return new JsonSegment(value.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] bytes() {
        return bytes;
    }

    public int length() {
        return bytes.length;
    }
}
