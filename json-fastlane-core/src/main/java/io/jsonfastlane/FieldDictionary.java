package io.jsonfastlane;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

final class FieldDictionary {
    private final JsonFastlaneOptions options;
    private final ReentrantLock registrationLock = new ReentrantLock();
    private final Map<String, Registration> fields = new ConcurrentHashMap<>();
    private final JsonShapeScanner.FieldNameResolver fieldNameResolver = new JsonShapeScanner.FieldNameResolver() {
        @Override
        public String resolveUnescapedFieldName(byte[] utf8Json, int start, int end) {
            return resolveFieldName(utf8Json, start, end);
        }

        @Override
        public String canonicalFieldName(String name) {
            return canonicalizeFieldName(name);
        }
    };
    private volatile Registration[] fieldsById = new Registration[16];
    private volatile int fieldCount;

    FieldDictionary(JsonFastlaneOptions options) {
        this.options = options;
    }

    Registration registration(String name) {
        Registration existing = fields.get(name);
        if (existing != null) {
            return existing;
        }

        registrationLock.lock();
        try {
            Registration lockedExisting = fields.get(name);
            if (lockedExisting != null) {
                return lockedExisting;
            }

            int id = fieldCount;
            ensureFieldCapacity(id + 1);
            Registration created = new Registration(
                id,
                new FieldProfile(name),
                name.getBytes(StandardCharsets.UTF_8)
            );
            fieldsById[id] = created;
            fields.put(name, created);
            fieldCount = id + 1;
            return created;
        } finally {
            registrationLock.unlock();
        }
    }

    JsonShapeScanner.FieldNameResolver fieldNameResolver() {
        return fieldNameResolver;
    }

    List<FieldProfileSnapshot> profileSnapshots() {
        return fields.values().stream()
            .map(Registration::profile)
            .map(FieldProfile::snapshot)
            .sorted(Comparator.comparing(FieldProfileSnapshot::name))
            .toList();
    }

    String fieldName(int id) {
        Registration registration = fieldsById[id];
        return registration.profile().name();
    }

    private void ensureFieldCapacity(int capacity) {
        Registration[] current = fieldsById;
        if (capacity <= current.length) {
            return;
        }

        int nextLength = current.length;
        while (nextLength < capacity) {
            nextLength *= 2;
        }
        fieldsById = Arrays.copyOf(current, nextLength);
    }

    private String resolveFieldName(byte[] utf8Json, int start, int end) {
        Registration[] registrations = fieldsById;
        int count = fieldCount;
        int candidates = Math.min(count, options.maxFieldNameReuseCandidates());
        int hash = hash(utf8Json, start, end);
        for (int i = 0; i < candidates; i++) {
            Registration registration = registrations[i];
            if (registration != null && matches(utf8Json, start, end, hash, registration)) {
                return registration.profile().name();
            }
        }
        return new String(utf8Json, start, end - start, StandardCharsets.UTF_8);
    }

    private String canonicalizeFieldName(String name) {
        Registration registration = fields.get(name);
        return registration == null ? name : registration.profile().name();
    }

    private static boolean matches(byte[] bytes, int start, int end, int hash, Registration expected) {
        byte[] expectedBytes = expected.utf8Name();
        int length = end - start;
        if (length != expectedBytes.length || hash != expected.utf8Hash()) {
            return false;
        }

        for (int i = 0; i < length; i++) {
            if (bytes[start + i] != expectedBytes[i]) {
                return false;
            }
        }
        return true;
    }

    private static int hash(byte[] bytes, int start, int end) {
        int hash = 1;
        for (int i = start; i < end; i++) {
            hash = 31 * hash + bytes[i];
        }
        return hash;
    }

    record Registration(int id, FieldProfile profile, byte[] utf8Name, int utf8Hash) {
        Registration(int id, FieldProfile profile, byte[] utf8Name) {
            this(id, profile, utf8Name, hash(utf8Name, 0, utf8Name.length));
        }
    }
}
