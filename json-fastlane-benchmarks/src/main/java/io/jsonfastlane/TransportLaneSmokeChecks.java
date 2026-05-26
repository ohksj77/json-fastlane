package io.jsonfastlane;

import io.jsonfastlane.netty.NettyJsonSink;
import io.jsonfastlane.transport.JsonSegment;
import io.jsonfastlane.transport.JsonSink;
import io.jsonfastlane.transport.JsonTransportPlan;
import io.jsonfastlane.transport.OptionalMask;
import io.jsonfastlane.transport.OutputStreamJsonSink;
import io.jsonfastlane.transport.TransportJsonWriter;
import io.jsonfastlane.transport.Utf8JsonSink;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class TransportLaneSmokeChecks {
    private TransportLaneSmokeChecks() {
    }

    public static void main(String[] args) {
        InvoiceLane lane = new InvoiceLane();
        Invoice invoice = new Invoice(7L, "PAID", "hello", List.of(new Line("coffee", 2)));

        Utf8JsonBuffer buffer = new Utf8JsonBuffer();
        lane.write(invoice, new Utf8JsonSink(buffer));
        String utf8Json = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        require(utf8Json.equals("{\"id\":7,\"status\":\"PAID\",\"memo\":\"hello\",\"lines\":[{\"sku\":\"coffee\",\"quantity\":2}]}"),
            "transport lane UTF-8 sink");

        ByteBuf byteBuf = Unpooled.buffer();
        try {
            lane.write(invoice, new NettyJsonSink(byteBuf));
            require(byteBuf.toString(StandardCharsets.UTF_8).equals(utf8Json), "transport lane Netty sink");
        } finally {
            byteBuf.release();
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        OutputStreamJsonSink outputStreamSink = new OutputStreamJsonSink(outputStream);
        lane.write(invoice, outputStreamSink);
        require(outputStream.toString(StandardCharsets.UTF_8).equals(utf8Json), "transport lane OutputStream sink");
        require(outputStreamSink.bytesWritten() == outputStream.size(), "transport lane counted bytes");

        Invoice withoutMemo = new Invoice(8L, "PENDING", null, List.of());
        Utf8JsonBuffer withoutMemoBuffer = new Utf8JsonBuffer();
        lane.write(withoutMemo, new Utf8JsonSink(withoutMemoBuffer));
        require(new String(withoutMemoBuffer.toByteArray(), StandardCharsets.UTF_8)
            .equals("{\"id\":8,\"status\":\"PENDING\",\"lines\":[]}"), "optional mask skeleton");
        require(lane.transportPlan().staticBytes() > 0, "transport plan static bytes");

        System.out.println("Transport lane smoke checks passed.");
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError("Failed: " + label);
        }
    }

    private record Invoice(long id, String status, String memo, List<Line> lines) {
    }

    private record Line(String sku, int quantity) {
    }

    private static final class InvoiceLane implements TransportJsonWriter<Invoice> {
        private static final JsonSegment ID = JsonSegment.ascii("{\"id\":");
        private static final JsonSegment STATUS = JsonSegment.ascii(",\"status\":");
        private static final JsonSegment MEMO = JsonSegment.ascii(",\"memo\":");
        private static final JsonSegment LINES = JsonSegment.ascii(",\"lines\":[");
        private static final JsonSegment LINE_SKU = JsonSegment.ascii("{\"sku\":");
        private static final JsonSegment LINE_QUANTITY = JsonSegment.ascii(",\"quantity\":");
        private static final JsonSegment END_ARRAY_OBJECT = JsonSegment.ascii("]}");
        private static final JsonSegment PLAN_END = JsonSegment.ascii("}");
        private static final JsonTransportPlan PLAN = new JsonTransportPlan(List.of(
            ID, STATUS, MEMO, LINES, LINE_SKU, LINE_QUANTITY, END_ARRAY_OBJECT, PLAN_END
        ));

        @Override
        public void write(Invoice value, JsonSink sink) {
            int mask = OptionalMask.setIfPresent(0, 0, value.memo());
            sink.writeSegment(ID).writeLong(value.id());
            sink.writeSegment(STATUS).writeString(value.status());
            if (OptionalMask.has(mask, 0)) {
                sink.writeSegment(MEMO).writeString(value.memo());
            }
            sink.writeSegment(LINES);
            for (int i = 0; i < value.lines().size(); i++) {
                if (i > 0) {
                    sink.writeByte(',');
                }
                writeLine(value.lines().get(i), sink);
            }
            sink.writeSegment(END_ARRAY_OBJECT);
        }

        private static void writeLine(Line value, JsonSink sink) {
            sink.writeSegment(LINE_SKU).writeString(value.sku());
            sink.writeSegment(LINE_QUANTITY).writeInt(value.quantity());
            sink.writeByte('}');
        }

        private JsonTransportPlan transportPlan() {
            return PLAN;
        }
    }
}
