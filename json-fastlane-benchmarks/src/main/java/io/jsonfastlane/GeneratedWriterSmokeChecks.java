package io.jsonfastlane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonfastlane.transport.Utf8JsonSink;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class GeneratedWriterSmokeChecks {
    private GeneratedWriterSmokeChecks() {
    }

    public static void main(String[] args) throws Exception {
        GeneratedInvoice value = new GeneratedInvoice(
            301L,
            "kim@example.com",
            true,
            new GeneratedAddress("Seoul", "Teheran-ro 427"),
            List.of(
                new GeneratedLine("coffee\nbean", 2, 18_900),
                new GeneratedLine("filter", 1, 8_400)
            )
        );

        Utf8JsonBuffer out = new Utf8JsonBuffer();
        GeneratedInvoiceJsonFastlaneWriter writer = new GeneratedInvoiceJsonFastlaneWriter();
        writer.write(value, out);
        Utf8JsonBuffer sinkOut = new Utf8JsonBuffer();
        writer.write(value, new Utf8JsonSink(sinkOut));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode generated = mapper.readTree(out.toByteArray());
        JsonNode generatedSink = mapper.readTree(sinkOut.toByteArray());
        JsonNode jackson = mapper.readTree(mapper.writeValueAsBytes(value));

        require(generated.equals(jackson), "generated writer JSON equivalence");
        require(generatedSink.equals(jackson), "generated transport writer JSON equivalence");
        require(new String(out.toByteArray(), StandardCharsets.UTF_8).contains("coffee\\nbean"),
            "generated writer escaping");
        require(writer.expectedShape().fields().size() == 5, "generated writer expected shape");
        require(writer.shapeMatcher().matches(out.toByteArray()), "generated writer shape matcher");
        JsonFastlane fastlane = new JsonFastlane();
        fastlane.registerExpectedShape("/generated-invoices", writer);
        require(fastlane.snapshots().iterator().next().fields().size() == 5, "generated writer shape priming");

        System.out.println("Generated writer smoke checks passed.");
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError("Failed: " + label);
        }
    }

    @JsonFastlaneGenerateWriter
    public record GeneratedInvoice(
        long id,
        String email,
        boolean paid,
        GeneratedAddress address,
        List<GeneratedLine> lines
    ) {
    }

    public record GeneratedAddress(String city, String line1) {
    }

    public record GeneratedLine(String sku, int quantity, long unitPriceCents) {
    }
}
