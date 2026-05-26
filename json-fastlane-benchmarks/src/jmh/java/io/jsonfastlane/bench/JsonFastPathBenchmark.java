package io.jsonfastlane.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonfastlane.Utf8JsonBuffer;
import io.jsonfastlane.netty.NettyJsonBuffer;
import io.jsonfastlane.netty.NettyJsonSink;
import io.jsonfastlane.transport.JsonSegment;
import io.jsonfastlane.transport.JsonSink;
import io.jsonfastlane.transport.TransportJsonWriter;
import io.jsonfastlane.transport.Utf8JsonSink;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Fork(1)
@State(Scope.Thread)
public class JsonFastPathBenchmark {
    private static final byte[] ORDER_ID_BYTES = "{\"orderId\":".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STATUS_BYTES = ",\"status\":".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EMAIL_BYTES = ",\"email\":".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TOTAL_CENTS_BYTES = ",\"totalCents\":".getBytes(StandardCharsets.US_ASCII);
    private static final JsonSegment ORDER_ID_SEGMENT = JsonSegment.ascii("{\"orderId\":");
    private static final JsonSegment STATUS_SEGMENT = JsonSegment.ascii(",\"status\":");
    private static final JsonSegment EMAIL_SEGMENT = JsonSegment.ascii(",\"email\":");
    private static final JsonSegment TOTAL_CENTS_SEGMENT = JsonSegment.ascii(",\"totalCents\":");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Utf8JsonBuffer buffer = new Utf8JsonBuffer(256);
    private final Utf8JsonSink utf8Sink = new Utf8JsonSink(buffer);
    private final ByteBuf byteBuf = Unpooled.buffer(256);
    private final NettyJsonBuffer nettyBuffer = new NettyJsonBuffer(byteBuf);
    private final NettyJsonSink nettySink = new NettyJsonSink(byteBuf);
    private final SampleTransportWriter transportWriter = new SampleTransportWriter();
    private SampleResponse response;

    @Setup(Level.Trial)
    public void setup() {
        response = new SampleResponse(42, "PAID", "kim@example.com", 173200);
    }

    @Benchmark
    public byte[] jacksonWriteValueAsBytes() throws Exception {
        return objectMapper.writeValueAsBytes(response);
    }

    @Benchmark
    public byte[] fastlaneWriteByteArray() {
        buffer.reset();
        writeResponse(buffer, response);
        return buffer.toByteArray();
    }

    @Benchmark
    public Utf8JsonBuffer fastlaneWriteReusableBuffer() {
        buffer.reset();
        writeResponse(buffer, response);
        return buffer;
    }

    @Benchmark
    public Utf8JsonBuffer fastlaneWriteUtf8JsonSink() {
        buffer.reset();
        transportWriter.write(response, utf8Sink);
        return buffer;
    }

    @Benchmark
    public ByteBuf fastlaneWriteNettyByteBuf() {
        byteBuf.clear();
        writeResponse(nettyBuffer, response);
        return byteBuf;
    }

    @Benchmark
    public ByteBuf fastlaneWriteNettyJsonSink() {
        byteBuf.clear();
        transportWriter.write(response, nettySink);
        return byteBuf;
    }

    private static void writeResponse(Utf8JsonBuffer out, SampleResponse response) {
        out.writeRaw(ORDER_ID_BYTES)
            .writeLong(response.orderId())
            .writeRaw(STATUS_BYTES)
            .writeString(response.status())
            .writeRaw(EMAIL_BYTES)
            .writeString(response.email())
            .writeRaw(TOTAL_CENTS_BYTES)
            .writeLong(response.totalCents())
            .writeByte('}');
    }

    private static void writeResponse(NettyJsonBuffer out, SampleResponse response) {
        out.writeRaw(ORDER_ID_BYTES)
            .writeLong(response.orderId())
            .writeRaw(STATUS_BYTES)
            .writeString(response.status())
            .writeRaw(EMAIL_BYTES)
            .writeString(response.email())
            .writeRaw(TOTAL_CENTS_BYTES)
            .writeLong(response.totalCents())
            .writeByte('}');
    }

    private static final class SampleTransportWriter implements TransportJsonWriter<SampleResponse> {
        @Override
        public void write(SampleResponse response, JsonSink sink) {
            sink.writeSegment(ORDER_ID_SEGMENT)
                .writeLong(response.orderId())
                .writeSegment(STATUS_SEGMENT)
                .writeString(response.status())
                .writeSegment(EMAIL_SEGMENT)
                .writeString(response.email())
                .writeSegment(TOTAL_CENTS_SEGMENT)
                .writeLong(response.totalCents())
                .writeByte('}');
        }
    }

    public record SampleResponse(long orderId, String status, String email, long totalCents) {
    }
}
