package io.jsonfastlane;

import java.util.List;
import java.util.Objects;

public record JsonShapeFingerprintPlan(
    JsonShapeFingerprint fingerprint,
    List<JsonShapeCheckpoint> checkpoints
) {
    public JsonShapeFingerprintPlan {
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(checkpoints, "checkpoints");
        checkpoints = List.copyOf(checkpoints);
    }

    public JsonShapeHashMatcher matcher() {
        return new JsonShapeHashMatcher(this);
    }
}
