package io.jsonfastlane;

import io.jsonfastlane.netty.FastJsonByteBufWriterRegistry;
import io.jsonfastlane.netty.NettyFastJsonWriters;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;

public final class NettySmokeChecks {
    private NettySmokeChecks() {
    }

    public static void main(String[] args) {
        FastJsonByteBufWriterRegistry registry = new FastJsonByteBufWriterRegistry();
        registry.register(Long.class, (value, out) -> out.writeCharSequence(
            "{\"orderId\":" + value + "}",
            StandardCharsets.UTF_8
        ));

        ByteBuf out = Unpooled.buffer();
        try {
            boolean written = NettyFastJsonWriters.writeIfRegistered(42L, out, registry);
            require(written, "registered Netty writer");
            require(out.toString(StandardCharsets.UTF_8).equals("{\"orderId\":42}"), "Netty writer output");
            require(!NettyFastJsonWriters.writeIfRegistered("fallback", out, registry), "Netty fallback signal");
        } finally {
            out.release();
        }

        System.out.println("Netty smoke checks passed.");
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError("Failed: " + label);
        }
    }
}
