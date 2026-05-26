package io.jsonfastlane;

public record JsonFastlaneOptions(
    int maxRetainedFieldOrders,
    int maxFieldNameReuseCandidates,
    int maxTopLevelFields,
    int maxNestingDepth,
    int maxEndpoints
) {
    private static final int DEFAULT_MAX_RETAINED_FIELD_ORDERS = 256;
    private static final int DEFAULT_MAX_FIELD_NAME_REUSE_CANDIDATES = 64;
    private static final int DEFAULT_MAX_TOP_LEVEL_FIELDS = 256;
    private static final int DEFAULT_MAX_NESTING_DEPTH = 128;
    private static final int DEFAULT_MAX_ENDPOINTS = 1024;

    public JsonFastlaneOptions(int maxRetainedFieldOrders, int maxFieldNameReuseCandidates) {
        this(
            maxRetainedFieldOrders,
            maxFieldNameReuseCandidates,
            DEFAULT_MAX_TOP_LEVEL_FIELDS,
            DEFAULT_MAX_NESTING_DEPTH,
            DEFAULT_MAX_ENDPOINTS
        );
    }

    public JsonFastlaneOptions(
        int maxRetainedFieldOrders,
        int maxFieldNameReuseCandidates,
        int maxTopLevelFields,
        int maxNestingDepth
    ) {
        this(
            maxRetainedFieldOrders,
            maxFieldNameReuseCandidates,
            maxTopLevelFields,
            maxNestingDepth,
            DEFAULT_MAX_ENDPOINTS
        );
    }

    public JsonFastlaneOptions {
        if (maxRetainedFieldOrders < 1) {
            throw new IllegalArgumentException("maxRetainedFieldOrders must be greater than zero");
        }
        if (maxFieldNameReuseCandidates < 0) {
            throw new IllegalArgumentException("maxFieldNameReuseCandidates must be zero or greater");
        }
        if (maxTopLevelFields < 1) {
            throw new IllegalArgumentException("maxTopLevelFields must be greater than zero");
        }
        if (maxNestingDepth < 1) {
            throw new IllegalArgumentException("maxNestingDepth must be greater than zero");
        }
        if (maxEndpoints < 1) {
            throw new IllegalArgumentException("maxEndpoints must be greater than zero");
        }
    }

    public static JsonFastlaneOptions defaults() {
        return new JsonFastlaneOptions(
            DEFAULT_MAX_RETAINED_FIELD_ORDERS,
            DEFAULT_MAX_FIELD_NAME_REUSE_CANDIDATES,
            DEFAULT_MAX_TOP_LEVEL_FIELDS,
            DEFAULT_MAX_NESTING_DEPTH,
            DEFAULT_MAX_ENDPOINTS
        );
    }
}
