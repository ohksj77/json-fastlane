package io.jsonfastlane;

import java.util.Objects;

public final class JsonShapeHashMatcher implements JsonShapeMatcher {
    private final JsonShapeFingerprint expected;
    private final JsonShapeFingerprintPlan plan;
    private final JsonFastlaneOptions options;
    private final boolean skeleton;

    public JsonShapeHashMatcher(JsonShapeFingerprint expected) {
        this(expected, JsonFastlaneOptions.defaults(), false);
    }

    public JsonShapeHashMatcher(JsonShapeFingerprint expected, JsonFastlaneOptions options) {
        this(expected, options, false);
    }

    private JsonShapeHashMatcher(JsonShapeFingerprint expected, JsonFastlaneOptions options, boolean skeleton) {
        this.expected = Objects.requireNonNull(expected, "expected");
        this.plan = null;
        this.options = Objects.requireNonNull(options, "options");
        this.skeleton = skeleton;
    }

    public JsonShapeHashMatcher(JsonShapeFingerprintPlan plan) {
        this(plan, JsonFastlaneOptions.defaults());
    }

    public JsonShapeHashMatcher(JsonShapeFingerprintPlan plan, JsonFastlaneOptions options) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.expected = plan.fingerprint();
        this.options = Objects.requireNonNull(options, "options");
        this.skeleton = false;
    }

    public static JsonShapeHashMatcher fromSample(byte[] utf8Json) {
        return new JsonShapeHashMatcher(JsonShapeFingerprinter.plan(utf8Json));
    }

    public static JsonShapeHashMatcher fromSample(String json) {
        return new JsonShapeHashMatcher(JsonShapeFingerprinter.plan(json));
    }

    public static JsonShapeHashMatcher skeletonFromSample(byte[] utf8Json) {
        return new JsonShapeHashMatcher(JsonShapeFingerprinter.skeletonFingerprint(utf8Json), JsonFastlaneOptions.defaults(), true);
    }

    public static JsonShapeHashMatcher skeletonFromSample(String json) {
        return new JsonShapeHashMatcher(JsonShapeFingerprinter.skeletonFingerprint(json), JsonFastlaneOptions.defaults(), true);
    }

    @Override
    public boolean matches(byte[] utf8Json) {
        return matchesHash(utf8Json);
    }

    public boolean matchesHash(byte[] utf8Json) {
        try {
            if (plan != null) {
                return JsonShapeFingerprinter.matches(utf8Json, plan, options);
            }
            JsonShapeFingerprint actual = skeleton
                ? JsonShapeFingerprinter.skeletonFingerprint(utf8Json, options)
                : JsonShapeFingerprinter.fingerprint(utf8Json, options);
            return expected.sameHash(actual);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public JsonShapeMatcher verifiedBy(JsonShapeMatcher exactMatcher) {
        return new VerifiedHashMatcher(this, exactMatcher);
    }

    public JsonShapeFingerprint expected() {
        return expected;
    }

    public int checkpointCount() {
        return plan == null ? 0 : plan.checkpoints().size();
    }

    private record VerifiedHashMatcher(
        JsonShapeHashMatcher hashMatcher,
        JsonShapeMatcher exactMatcher
    ) implements JsonShapeMatcher {
        private VerifiedHashMatcher {
            Objects.requireNonNull(hashMatcher, "hashMatcher");
            Objects.requireNonNull(exactMatcher, "exactMatcher");
        }

        @Override
        public boolean matches(byte[] utf8Json) {
            return hashMatcher.matchesHash(utf8Json) && exactMatcher.matches(utf8Json);
        }
    }
}
