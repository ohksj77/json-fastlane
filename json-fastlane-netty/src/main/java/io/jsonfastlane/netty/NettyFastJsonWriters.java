package io.jsonfastlane.netty;

import io.netty.buffer.ByteBuf;

public final class NettyFastJsonWriters {
    private NettyFastJsonWriters() {
    }

    public static boolean writeIfRegistered(
        Object value,
        ByteBuf out,
        FastJsonByteBufWriterRegistry registry
    ) {
        if (value == null) {
            return false;
        }

        FastJsonByteBufWriter<Object> writer = findWriter(value.getClass(), registry);
        if (writer == null) {
            return false;
        }
        writer.write(value, out);
        return true;
    }

    @SuppressWarnings("unchecked")
    private static FastJsonByteBufWriter<Object> findWriter(
        Class<?> type,
        FastJsonByteBufWriterRegistry registry
    ) {
        return (FastJsonByteBufWriter<Object>) registry.find(type);
    }
}
