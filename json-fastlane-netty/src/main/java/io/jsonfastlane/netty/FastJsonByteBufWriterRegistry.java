package io.jsonfastlane.netty;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FastJsonByteBufWriterRegistry {
    private static final FastJsonByteBufWriter<Object> NO_WRITER = (value, out) -> {
        throw new IllegalStateException("No generated writer registered");
    };

    private final Map<Class<?>, FastJsonByteBufWriter<?>> writers = new ConcurrentHashMap<>();
    private final Map<Class<?>, FastJsonByteBufWriter<?>> lookupCache = new ConcurrentHashMap<>();

    public <T> void register(Class<T> type, FastJsonByteBufWriter<? super T> writer) {
        writers.put(type, writer);
        lookupCache.clear();
    }

    @SuppressWarnings("unchecked")
    public <T> FastJsonByteBufWriter<T> find(Class<T> type) {
        FastJsonByteBufWriter<?> cached = lookupCache.get(type);
        if (cached != null) {
            if (cached == NO_WRITER) {
                return null;
            }
            return (FastJsonByteBufWriter<T>) cached;
        }

        FastJsonByteBufWriter<?> exact = writers.get(type);
        if (exact != null) {
            lookupCache.put(type, exact);
            return (FastJsonByteBufWriter<T>) exact;
        }

        for (Map.Entry<Class<?>, FastJsonByteBufWriter<?>> entry : writers.entrySet()) {
            if (entry.getKey().isAssignableFrom(type)) {
                FastJsonByteBufWriter<?> writer = entry.getValue();
                lookupCache.put(type, writer);
                return (FastJsonByteBufWriter<T>) writer;
            }
        }
        lookupCache.put(type, NO_WRITER);
        return null;
    }
}
