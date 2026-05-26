package io.jsonfastlane;

import java.nio.charset.StandardCharsets;

public interface JsonShapeMatcher {
    boolean matches(byte[] utf8Json);

    default boolean matches(String json) {
        return matches(json.getBytes(StandardCharsets.UTF_8));
    }
}
