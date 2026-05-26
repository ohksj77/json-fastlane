package io.jsonfastlane;

import io.jsonfastlane.examples.CreateOrderRequest;
import io.jsonfastlane.examples.OrderItemRequest;
import io.jsonfastlane.spring.ConversionDirection;
import io.jsonfastlane.spring.ConversionProfileSnapshot;
import io.jsonfastlane.spring.EndpointResolver;
import io.jsonfastlane.spring.FastJsonTransportHttpMessageConverter;
import io.jsonfastlane.spring.FastJsonWriterRegistry;
import io.jsonfastlane.spring.JsonBufferFactory;
import io.jsonfastlane.spring.JsonConversionProfiler;
import io.jsonfastlane.spring.ProfilingJackson2HttpMessageConverter;
import io.jsonfastlane.transport.JsonSegment;
import io.jsonfastlane.transport.JsonSink;
import io.jsonfastlane.transport.TransportJsonWriter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class SpringAdapterSmokeChecks {
    private SpringAdapterSmokeChecks() {
    }

    public static void main(String[] args) throws Exception {
        JsonConversionProfiler profiler = new JsonConversionProfiler();
        ProfilingJackson2HttpMessageConverter converter = new ProfilingJackson2HttpMessageConverter(
            profiler,
            new FastJsonWriterRegistry(),
            JsonBufferFactory.defaultFactory(),
            EndpointResolver.fixed("/orders")
        );

        CreateOrderRequest decoded = (CreateOrderRequest) converter.read(
            CreateOrderRequest.class,
            new SimpleInputMessage("{\"userId\":99,\"items\":[{\"sku\":\"tea\",\"quantity\":2}],\"couponCode\":null}")
        );
        require(decoded.userId() == 99, "decoded user id");
        require(decoded.items().get(0).sku().equals("tea"), "decoded nested sku");

        SimpleOutputMessage output = new SimpleOutputMessage();
        converter.write(
            new CreateOrderRequest(100, List.of(new OrderItemRequest("mug", 1)), "SPRING"),
            MediaType.APPLICATION_JSON,
            output
        );
        String encoded = output.body();
        require(encoded.contains("\"userId\":100"), "encoded user id");
        require(encoded.contains("\"couponCode\":\"SPRING\""), "encoded coupon");

        List<ConversionProfileSnapshot> snapshots = profiler.conversionSnapshots().stream().toList();
        require(snapshots.size() == 2, "conversion snapshots");
        require(snapshots.stream().anyMatch(snapshot -> snapshot.direction() == ConversionDirection.READ), "read snapshot");
        require(snapshots.stream().anyMatch(snapshot -> snapshot.direction() == ConversionDirection.WRITE), "write snapshot");

        EndpointProfileSnapshot shape = profiler.shapeProfiler().snapshots().iterator().next();
        require(shape.endpoint().equals("/orders"), "shape endpoint");
        require(shape.samples() == 2, "shape samples");

        JsonConversionProfiler transportProfiler = new JsonConversionProfiler();
        FastJsonWriterRegistry transportRegistry = new FastJsonWriterRegistry();
        transportRegistry.register(SpringTransportOrder.class, new SpringTransportOrderWriter());
        FastJsonTransportHttpMessageConverter transportConverter =
            new FastJsonTransportHttpMessageConverter(
                transportProfiler,
                transportRegistry,
                EndpointResolver.fixed("/transport-orders")
            );

        SimpleOutputMessage transportOutput = new SimpleOutputMessage();
        transportConverter.write(
            new SpringTransportOrder(77, "READY"),
            MediaType.APPLICATION_JSON,
            transportOutput
        );
        require(transportOutput.body().equals("{\"id\":77,\"status\":\"READY\"}"), "transport converter body");
        require(transportOutput.getHeaders().getContentLength() < 0, "transport converter streams without content length");
        ConversionProfileSnapshot transportSnapshot =
            transportProfiler.conversionSnapshots().stream().findFirst().orElseThrow();
        require(transportSnapshot.endpoint().equals("/transport-orders"), "transport endpoint");
        require(transportSnapshot.averagePayloadBytes() == transportOutput.body().getBytes(StandardCharsets.UTF_8).length,
            "transport bytes");
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError("Failed: " + label);
        }
    }

    private static final class SimpleInputMessage implements HttpInputMessage {
        private final byte[] body;
        private final HttpHeaders headers = new HttpHeaders();

        private SimpleInputMessage(String body) {
            this.body = body.getBytes(StandardCharsets.UTF_8);
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setContentLength(this.body.length);
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }
    }

    private static final class SimpleOutputMessage implements HttpOutputMessage {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final HttpHeaders headers = new HttpHeaders();

        @Override
        public OutputStream getBody() {
            return body;
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        private String body() {
            return body.toString(StandardCharsets.UTF_8);
        }
    }

    private record SpringTransportOrder(long id, String status) {
    }

    private static final class SpringTransportOrderWriter
        implements FastJsonBufferWriter<SpringTransportOrder>, TransportJsonWriter<SpringTransportOrder> {
        private static final JsonSegment ID = JsonSegment.ascii("{\"id\":");
        private static final JsonSegment STATUS = JsonSegment.ascii(",\"status\":");

        @Override
        public void write(SpringTransportOrder value, Utf8JsonBuffer out) {
            out.writeSegment(ID).writeLong(value.id());
            out.writeSegment(STATUS).writeString(value.status());
            out.writeByte('}');
        }

        @Override
        public void write(SpringTransportOrder value, JsonSink sink) {
            sink.writeSegment(ID).writeLong(value.id());
            sink.writeSegment(STATUS).writeString(value.status());
            sink.writeByte('}');
        }
    }
}
