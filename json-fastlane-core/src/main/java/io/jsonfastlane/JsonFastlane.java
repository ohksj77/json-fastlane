package io.jsonfastlane;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

public final class JsonFastlane {
    private final JsonFastlaneOptions options;
    private final Map<String, EndpointProfile> profiles = new ConcurrentHashMap<>();
    private final ReentrantLock profileRegistrationLock = new ReentrantLock();
    private final LongAdder droppedEndpointObservations = new LongAdder();

    public JsonFastlane() {
        this(JsonFastlaneOptions.defaults());
    }

    public JsonFastlane(JsonFastlaneOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    public void record(String endpoint, byte[] utf8Json) {
        EndpointProfile profile = profiles.get(endpoint);
        if (profile == null) {
            JsonShapeObservation observation = JsonShapeScanner.scan(utf8Json, options);
            EndpointProfile created = profileForNewEndpoint(endpoint);
            if (created == null) {
                droppedEndpointObservations.increment();
                return;
            }
            created.record(observation);
            return;
        }

        JsonShapeObservation observation = JsonShapeScanner.scan(utf8Json, profile.fieldNameResolver(), options);
        profile.record(observation);
    }

    public void record(String endpoint, String json) {
        record(endpoint, json.getBytes(StandardCharsets.UTF_8));
    }

    public void registerExpectedShape(String endpoint, byte[] utf8Json) {
        JsonShapeObservation observation = JsonShapeScanner.scan(utf8Json, options);
        profileForExpectedShape(endpoint).prime(observation);
    }

    public void registerExpectedShape(String endpoint, String json) {
        registerExpectedShape(endpoint, json.getBytes(StandardCharsets.UTF_8));
    }

    public void registerExpectedShape(String endpoint, ExpectedJsonShape shape) {
        Objects.requireNonNull(shape, "shape");
        profileForExpectedShape(endpoint).prime(shape.observation());
    }

    public void registerExpectedShape(String endpoint, JsonFastlaneGeneratedWriter<?> writer) {
        Objects.requireNonNull(writer, "writer");
        registerExpectedShape(endpoint, writer.expectedShape());
    }

    public Collection<EndpointProfileSnapshot> snapshots() {
        return profiles.values().stream()
            .map(EndpointProfile::snapshot)
            .sorted((left, right) -> left.endpoint().compareTo(right.endpoint()))
            .toList();
    }

    public long droppedEndpointObservations() {
        return droppedEndpointObservations.sum();
    }

    private EndpointProfile profileForExpectedShape(String endpoint) {
        EndpointProfile profile = profileForNewEndpoint(endpoint);
        if (profile == null) {
            throw new IllegalStateException("Endpoint profile limit reached");
        }
        return profile;
    }

    private EndpointProfile profileForNewEndpoint(String endpoint) {
        EndpointProfile existing = profiles.get(endpoint);
        if (existing != null) {
            return existing;
        }

        profileRegistrationLock.lock();
        try {
            EndpointProfile lockedExisting = profiles.get(endpoint);
            if (lockedExisting != null) {
                return lockedExisting;
            }
            if (profiles.size() >= options.maxEndpoints()) {
                return null;
            }

            EndpointProfile created = newProfile(endpoint);
            profiles.put(endpoint, created);
            return created;
        } finally {
            profileRegistrationLock.unlock();
        }
    }

    private EndpointProfile newProfile(String endpoint) {
        return new EndpointProfile(endpoint, options);
    }
}
