package io.jsonfastlane;

import java.util.Objects;

public record ExpectedJsonField(String name, JsonValueKind kind) {
    public ExpectedJsonField {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
    }

    public static ExpectedJsonField field(String name, JsonValueKind kind) {
        return new ExpectedJsonField(name, kind);
    }
}
