package io.jsonfastlane;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record ExpectedJsonShape(JsonValueKind rootKind, List<ExpectedJsonField> fields) {
    public ExpectedJsonShape {
        Objects.requireNonNull(rootKind, "rootKind");
        Objects.requireNonNull(fields, "fields");
        fields = List.copyOf(fields);
        if (rootKind != JsonValueKind.OBJECT && !fields.isEmpty()) {
            throw new IllegalArgumentException("Only object shapes can define expected fields");
        }
    }

    public static ExpectedJsonShape object(ExpectedJsonField... fields) {
        return new ExpectedJsonShape(JsonValueKind.OBJECT, List.of(fields));
    }

    public static ExpectedJsonShape array() {
        return new ExpectedJsonShape(JsonValueKind.ARRAY, List.of());
    }

    public JsonShapeMatcher compileMatcher() {
        return new CompiledJsonShapeMatcher(this, JsonFastlaneOptions.defaults());
    }

    public JsonShapeMatcher compileMatcher(JsonFastlaneOptions options) {
        return new CompiledJsonShapeMatcher(this, options);
    }

    JsonShapeObservation observation() {
        if (rootKind != JsonValueKind.OBJECT) {
            return new JsonShapeObservation(0, rootKind, List.of());
        }

        List<FieldObservation> observations = new ArrayList<>(fields.size());
        for (int i = 0; i < fields.size(); i++) {
            ExpectedJsonField field = fields.get(i);
            observations.add(new FieldObservation(field.name(), field.kind(), i));
        }
        return new JsonShapeObservation(0, rootKind, observations);
    }
}
