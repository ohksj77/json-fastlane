package io.jsonfastlane;

import io.jsonfastlane.examples.CreateOrderRequest;
import io.jsonfastlane.examples.CreateOrderRequestWriter;
import io.jsonfastlane.examples.OrderItemRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class SmokeChecks {
    private SmokeChecks() {
    }

    public static void main(String[] args) {
        recordsEndpointShapes();
        recordsEscapedFieldNames();
        ignoresMalformedJson();
        appliesProfileOptions();
        enforcesShapeScanLimits();
        primesExpectedShapes();
        matchesCompiledShapes();
        hashesJsonShapes();
        hashesHomogeneousArraySkeletons();
        keepsEndpointProfilesBounded();
        exportsReportsAndMetrics();
        ranksFastPathCandidates();
        comparesCandidateReports();
        recordsCandidateReportSamplesFromFiles();
        appliesCandidateReportConfig();
        writesWithGeneratedCodecContract();
        writesGeneratedStyleJson();
        System.out.println("Smoke checks passed.");
    }

    private static void recordsEndpointShapes() {
        JsonFastlane fastlane = new JsonFastlane();
        fastlane.record("/orders", "{\"userId\":1,\"items\":[],\"couponCode\":null}");
        fastlane.record("/orders", "{\"userId\":2,\"items\":[],\"couponCode\":\"HELLO\"}");

        EndpointProfileSnapshot snapshot = fastlane.snapshots().iterator().next();
        require(snapshot.samples() == 2, "sample count");
        require(snapshot.fields().size() == 3, "field count");
        require(snapshot.fieldOrders().get(0).signature().equals("userId,items,couponCode"), "field order");

        FieldProfileSnapshot coupon = snapshot.fields().stream()
            .filter(field -> field.name().equals("couponCode"))
            .findFirst()
            .orElseThrow();
        require(coupon.valueKinds().get(JsonValueKind.NULL) == 1, "null coupon count");
        require(coupon.valueKinds().get(JsonValueKind.STRING) == 1, "string coupon count");
    }

    private static void recordsEscapedFieldNames() {
        JsonFastlane fastlane = new JsonFastlane();
        fastlane.record("/orders", "{\"user\\u0049d\":1}");
        fastlane.record("/orders", "{\"userId\":2}");

        EndpointProfileSnapshot snapshot = fastlane.snapshots().iterator().next();
        require(snapshot.fields().size() == 1, "escaped field canonicalization");
        require(snapshot.fields().get(0).name().equals("userId"), "escaped field name");
        require(snapshot.fields().get(0).valueKinds().get(JsonValueKind.NUMBER) == 2, "escaped field count");
    }

    private static void ignoresMalformedJson() {
        JsonFastlane fastlane = new JsonFastlane();
        try {
            fastlane.record("/bad", "{\"userId\":");
            throw new AssertionError("Failed: malformed JSON exception");
        } catch (IllegalArgumentException expected) {
            require(fastlane.snapshots().isEmpty(), "malformed JSON profile");
        }
    }

    private static void appliesProfileOptions() {
        JsonFastlane fastlane = new JsonFastlane(new JsonFastlaneOptions(1, 0));
        fastlane.record("/orders", "{\"userId\":1,\"items\":[]}");
        fastlane.record("/orders", "{\"items\":[],\"userId\":2}");

        EndpointProfileSnapshot snapshot = fastlane.snapshots().iterator().next();
        require(snapshot.fieldOrders().size() == 1, "retained field order limit");
        require(snapshot.droppedFieldOrders() == 1, "dropped field order count");
        require(snapshot.fields().size() == 2, "field profile retention");
    }

    private static void enforcesShapeScanLimits() {
        JsonFastlane fastlane = new JsonFastlane(new JsonFastlaneOptions(8, 8, 1, 2));
        try {
            fastlane.record("/wide", "{\"a\":1,\"b\":2}");
            throw new AssertionError("Failed: top-level field limit exception");
        } catch (IllegalArgumentException expected) {
            require(fastlane.snapshots().isEmpty(), "wide JSON profile");
        }

        try {
            fastlane.record("/deep", "{\"a\":{\"b\":{\"c\":1}}}");
            throw new AssertionError("Failed: nesting depth limit exception");
        } catch (IllegalArgumentException expected) {
            require(fastlane.snapshots().isEmpty(), "deep JSON profile");
        }
    }

    private static void primesExpectedShapes() {
        JsonFastlane fastlane = new JsonFastlane();
        fastlane.registerExpectedShape("/orders", "{\"userId\":0,\"items\":[],\"couponCode\":null}");
        fastlane.registerExpectedShape("/orders", "{\"userId\":0,\"items\":[],\"gift\":false}");
        fastlane.registerExpectedShape("/orders", ExpectedJsonShape.object(
            ExpectedJsonField.field("status", JsonValueKind.STRING),
            ExpectedJsonField.field("message", JsonValueKind.STRING)
        ));

        EndpointProfileSnapshot primed = fastlane.snapshots().iterator().next();
        require(primed.samples() == 0, "primed shape sample count");
        require(primed.fieldOrders().isEmpty(), "primed shape order samples");
        require(primed.fields().size() == 6, "primed field dictionary");

        fastlane.record("/orders", "{\"userId\":1,\"items\":[],\"gift\":true}");
        EndpointProfileSnapshot recorded = fastlane.snapshots().iterator().next();
        require(recorded.samples() == 1, "recorded sample after priming");
        require(recorded.fieldOrders().get(0).signature().equals("userId,items,gift"), "primed hot path order");
    }

    private static void matchesCompiledShapes() {
        JsonShapeMatcher matcher = ExpectedJsonShape.object(
            ExpectedJsonField.field("userId", JsonValueKind.NUMBER),
            ExpectedJsonField.field("items", JsonValueKind.ARRAY),
            ExpectedJsonField.field("couponCode", JsonValueKind.NULL)
        ).compileMatcher();

        require(matcher.matches("{\"userId\":1,\"items\":[],\"couponCode\":null}"), "compiled shape match");
        require(!matcher.matches("{\"items\":[],\"userId\":1,\"couponCode\":null}"), "compiled shape order mismatch");
        require(!matcher.matches("{\"userId\":1,\"items\":[],\"couponCode\":\"A\"}"), "compiled shape kind mismatch");
    }

    private static void hashesJsonShapes() {
        JsonShapeFingerprint left = JsonShapeFingerprinter.fingerprint(
            "{\"userId\":1,\"items\":[{\"sku\":\"A\",\"quantity\":2}],\"shipping\":{\"city\":\"Seoul\"}}"
        );
        JsonShapeFingerprint sameShape = JsonShapeFingerprinter.fingerprint(
            "{\"userId\":99,\"items\":[{\"sku\":\"B\",\"quantity\":10}],\"shipping\":{\"city\":\"Busan\"}}"
        );
        JsonShapeFingerprint differentOrder = JsonShapeFingerprinter.fingerprint(
            "{\"items\":[{\"sku\":\"A\",\"quantity\":2}],\"userId\":1,\"shipping\":{\"city\":\"Seoul\"}}"
        );
        JsonShapeFingerprint differentDepth = JsonShapeFingerprinter.fingerprint(
            "{\"userId\":1,\"items\":[{\"sku\":\"A\",\"quantity\":2}],\"shipping\":{\"address\":{\"city\":\"Seoul\"}}}"
        );
        JsonShapeFingerprint escaped = JsonShapeFingerprinter.fingerprint(
            "{\"user\\u0049d\":1,\"items\":[{\"sku\":\"A\",\"quantity\":2}],\"shipping\":{\"city\":\"Seoul\"}}"
        );

        require(left.sameHash(sameShape), "shape hash ignores scalar values");
        require(!left.sameHash(differentOrder), "shape hash detects order");
        require(!left.sameHash(differentDepth), "shape hash detects depth");
        require(left.sameHash(escaped), "shape hash canonicalizes escaped field names");
        require(left.fieldCount() == 6, "shape hash field count");
        require(left.maxKeyDepth() == 2, "shape hash max key depth");

        JsonShapeFingerprintPlan plan = JsonShapeFingerprinter.plan(
            "{\"userId\":1,\"items\":[{\"sku\":\"A\",\"quantity\":2}],\"shipping\":{\"city\":\"Seoul\"}}"
        );
        require(plan.checkpoints().size() == 3, "shape hash checkpoints");

        JsonShapeHashMatcher matcher = plan.matcher();
        require(matcher.checkpointCount() == 3, "shape hash matcher checkpoints");
        require(JsonShapeHashMatcher.fromSample(
            "{\"userId\":1,\"items\":[{\"sku\":\"A\",\"quantity\":2}],\"shipping\":{\"city\":\"Seoul\"}}"
        ).checkpointCount() == 3, "sample hash matcher checkpoints");
        require(matcher.matches(
            "{\"userId\":2,\"items\":[{\"sku\":\"B\",\"quantity\":3}],\"shipping\":{\"city\":\"Incheon\"}}"
        ), "shape hash matcher hit");
        require(!matcher.matches(
            "{\"userId\":2,\"items\":[{\"sku\":\"B\",\"count\":3}],\"shipping\":{\"city\":\"Incheon\"}}"
        ), "shape hash matcher miss");

        JsonShapeMatcher exactTopLevel = ExpectedJsonShape.object(
            ExpectedJsonField.field("userId", JsonValueKind.NUMBER),
            ExpectedJsonField.field("items", JsonValueKind.ARRAY),
            ExpectedJsonField.field("shipping", JsonValueKind.OBJECT)
        ).compileMatcher();
        JsonShapeMatcher verified = matcher.verifiedBy(exactTopLevel);
        require(verified.matches(
            "{\"userId\":2,\"items\":[{\"sku\":\"B\",\"quantity\":3}],\"shipping\":{\"city\":\"Incheon\"}}"
        ), "verified shape hash matcher hit");
    }

    private static void hashesHomogeneousArraySkeletons() {
        String oneItem = "{\"items\":[{\"sku\":\"A\",\"quantity\":1}]}";
        String threeItems = "{\"items\":[{\"sku\":\"A\",\"quantity\":1},{\"sku\":\"B\",\"quantity\":2},{\"sku\":\"C\",\"quantity\":3}]}";
        String differentElement = "{\"items\":[{\"sku\":\"A\",\"count\":1}]}";

        require(!JsonShapeFingerprinter.fingerprint(oneItem).sameHash(JsonShapeFingerprinter.fingerprint(threeItems)),
            "strict shape hash includes array length");
        require(JsonShapeFingerprinter.skeletonFingerprint(oneItem)
            .sameHash(JsonShapeFingerprinter.skeletonFingerprint(threeItems)),
            "skeleton shape hash ignores homogeneous array length");
        require(!JsonShapeFingerprinter.skeletonFingerprint(oneItem)
            .sameHash(JsonShapeFingerprinter.skeletonFingerprint(differentElement)),
            "skeleton shape hash detects element shape drift");

        JsonShapeHashMatcher matcher = JsonShapeHashMatcher.skeletonFromSample(oneItem);
        require(matcher.matches(threeItems), "skeleton matcher hit");
        require(!matcher.matches(differentElement), "skeleton matcher miss");
    }

    private static void keepsEndpointProfilesBounded() {
        JsonFastlane fastlane = new JsonFastlane(new JsonFastlaneOptions(8, 8, 8, 8, 1));
        fastlane.record("/one", "{\"a\":1}");
        fastlane.record("/two", "{\"a\":1}");

        require(fastlane.snapshots().size() == 1, "bounded endpoint profiles");
        require(fastlane.droppedEndpointObservations() == 1, "dropped endpoint observations");
    }

    private static void exportsReportsAndMetrics() {
        JsonFastlane fastlane = new JsonFastlane();
        fastlane.record("/orders", "{\"userId\":1,\"items\":[]}");

        String report = JsonFastlaneReport.text(fastlane.snapshots());
        require(report.contains("/orders"), "text report endpoint");
        require(report.contains("hotOrder=userId,items"), "text report hot order");

        CountingMetricsSink sink = new CountingMetricsSink();
        JsonFastlaneReport.emitMetrics(fastlane.snapshots(), sink);
        require(sink.metrics == 3, "metric export count");

        String json = JsonFastlaneReport.json(fastlane.snapshots());
        require(json.contains("\"endpoints\""), "json report endpoints");
        require(json.contains("\"candidates\""), "json report candidates");
        require(json.contains("\"endpoint\":\"/orders\""), "json report endpoint");
        require(json.contains("\"hotFieldOrder\":\"userId,items\""), "json report hot order");
    }

    private static void ranksFastPathCandidates() {
        JsonFastlane fastlane = new JsonFastlane();
        for (int i = 0; i < 100; i++) {
            fastlane.record("/stable", "{\"userId\":" + i + ",\"items\":[],\"couponCode\":null}");
        }
        fastlane.record("/variable", "{\"userId\":1,\"items\":[]}");
        fastlane.record("/variable", "{\"items\":[],\"userId\":2}");

        List<JsonFastlaneCandidate> candidates = JsonFastlaneReport.candidates(fastlane.snapshots());
        JsonFastlaneCandidate stable = candidates.get(0);
        require(stable.endpoint().equals("/stable"), "candidate ranking");
        require(stable.recommended(), "candidate recommended");
        require(stable.recommendation().equals("generate-fast-path"), "candidate recommendation");
        require(stable.hotFieldOrder().equals("userId,items,couponCode"), "candidate hot order");

        String report = JsonFastlaneReport.text(fastlane.snapshots());
        require(report.contains("fast-path candidates"), "candidate report section");
        require(report.contains("recommendation=generate-fast-path"), "candidate report recommendation");
    }

    private static void comparesCandidateReports() {
        String baseline = candidateReportJson(90, 1.0, 0);
        String currentPass = candidateReportJson(75, 0.85, 0);
        String currentFail = candidateReportJson(50, 0.60, 1);

        FastPathReportComparator.Comparison pass = FastPathReportComparator.compare(
            baseline, currentPass, 20, 0.20);
        require(pass.passed(), "candidate comparison pass");
        require(pass.report().contains("PASS /orders"), "candidate comparison pass report");

        FastPathReportComparator.Comparison fail = FastPathReportComparator.compare(
            baseline, currentFail, 20, 0.20);
        require(!fail.passed(), "candidate comparison fail");
        require(fail.report().contains("FAIL /orders"), "candidate comparison fail report");
    }

    private static String candidateReportJson(int score, double hotOrderRatio, long droppedFieldOrders) {
        return "{\"endpoints\":[],\"candidates\":[{"
            + "\"endpoint\":\"/orders\","
            + "\"samples\":100,"
            + "\"averagePayloadBytes\":64,"
            + "\"hotFieldOrder\":\"userId,items\","
            + "\"hotFieldOrderSamples\":100,"
            + "\"hotFieldOrderRatio\":" + hotOrderRatio + ","
            + "\"fieldCount\":2,"
            + "\"droppedFieldOrders\":" + droppedFieldOrders + ","
            + "\"score\":" + score + ","
            + "\"recommended\":true,"
            + "\"recommendation\":\"generate-fast-path\""
            + "}]}";
    }

    private static void recordsCandidateReportSamplesFromFiles() {
        try {
            Path directory = Files.createTempDirectory("json-fastlane-samples");
            Path lines = directory.resolve("samples.tsv");
            Files.writeString(lines,
                "/orders\t{\"userId\":1,\"items\":[]}\n"
                    + "/orders\t{\"userId\":2,\"items\":[]}\n",
                StandardCharsets.UTF_8);

            JsonFastlane fromLines = new JsonFastlane();
            FastPathCandidateReport.recordCapturedSamples(fromLines, lines);
            EndpointProfileSnapshot lineSnapshot = fromLines.snapshots().iterator().next();
            require(lineSnapshot.endpoint().equals("/orders"), "line sample endpoint");
            require(lineSnapshot.samples() == 2, "line sample count");

            Path apiDirectory = directory.resolve("api");
            Files.createDirectories(apiDirectory.resolve("orders"));
            Files.writeString(apiDirectory.resolve("orders/create-1.json"),
                "{\"userId\":1,\"items\":[]}", StandardCharsets.UTF_8);
            Files.writeString(apiDirectory.resolve("orders/create-2.json"),
                "{\"userId\":2,\"items\":[]}", StandardCharsets.UTF_8);

            JsonFastlane fromDirectory = new JsonFastlane();
            FastPathCandidateReport.recordCapturedSamples(fromDirectory, apiDirectory);
            EndpointProfileSnapshot directorySnapshot = fromDirectory.snapshots().iterator().next();
            require(directorySnapshot.endpoint().equals("/orders/create"), "directory sample endpoint");
            require(directorySnapshot.samples() == 2, "directory sample count");
            require(FastPathCandidateReport.endpointFromPath(apiDirectory, apiDirectory.resolve("orders/create-12.json"))
                .equals("/orders/create"), "directory endpoint normalization");
        } catch (Exception exception) {
            throw new AssertionError("Failed: candidate report sample files", exception);
        }
    }

    private static void appliesCandidateReportConfig() {
        try {
            Path directory = Files.createTempDirectory("json-fastlane-configured-samples");
            Path lines = directory.resolve("samples.tsv");
            Files.writeString(lines,
                "/orders/1\t{\"userId\":1,\"email\":\"a@example.com\",\"items\":[]}\n"
                    + "/orders/2\t{\"userId\":2,\"email\":\"b@example.com\",\"items\":[]}\n",
                StandardCharsets.UTF_8);
            Path configFile = directory.resolve("candidate.properties");
            Files.writeString(configFile,
                "endpoint./orders/=/orders/{id}\n"
                    + "redactFields=email\n",
                StandardCharsets.UTF_8);

            JsonFastlane fastlane = new JsonFastlane();
            FastPathCandidateReport.ReportConfig config = FastPathCandidateReport.ReportConfig.load(configFile.toString());
            FastPathCandidateReport.recordCapturedSamples(fastlane, lines, config);

            EndpointProfileSnapshot snapshot = fastlane.snapshots().iterator().next();
            require(snapshot.endpoint().equals("/orders/{id}"), "configured endpoint mapping");
            require(snapshot.samples() == 2, "configured sample count");
            FieldProfileSnapshot email = snapshot.fields().stream()
                .filter(field -> field.name().equals("email"))
                .findFirst()
                .orElseThrow();
            require(email.valueKinds().get(JsonValueKind.NULL) == 2, "configured redaction kind");
        } catch (Exception exception) {
            throw new AssertionError("Failed: candidate report config", exception);
        }
    }

    private static void writesWithGeneratedCodecContract() {
        OrderIdCodec codec = new OrderIdCodec();
        byte[] json = codec.writeToBytes(7L);

        require(new String(json, StandardCharsets.UTF_8).equals("{\"orderId\":7}"), "codec write bytes");
        require(codec.read(json) == 7L, "codec read bytes");
    }

    private static void writesGeneratedStyleJson() {
        CreateOrderRequestWriter writer = new CreateOrderRequestWriter();
        byte[] json = writer.write(new CreateOrderRequest(
            10,
            List.of(new OrderItemRequest("cup\nlarge", 2)),
            null
        ));

        String encoded = new String(json, StandardCharsets.UTF_8);
        require(encoded.equals("{\"userId\":10,\"items\":[{\"sku\":\"cup\\nlarge\",\"quantity\":2}],\"couponCode\":null}"),
            "encoded JSON");
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError("Failed: " + label);
        }
    }

    private static final class CountingMetricsSink implements JsonFastlaneMetricsSink {
        private int metrics;

        @Override
        public void endpointSampleCount(String endpoint, long samples) {
            metrics++;
        }

        @Override
        public void endpointAveragePayloadBytes(String endpoint, long averagePayloadBytes) {
            metrics++;
        }

        @Override
        public void endpointDroppedFieldOrders(String endpoint, long droppedFieldOrders) {
            metrics++;
        }
    }

    private static final class OrderIdCodec implements FastJsonCodec<Long> {
        @Override
        public Long read(byte[] utf8Json) {
            String json = new String(utf8Json, StandardCharsets.UTF_8);
            return Long.parseLong(json.substring("{\"orderId\":".length(), json.length() - 1));
        }

        @Override
        public void write(Long value, Utf8JsonBuffer out) {
            out.writeAscii("{\"orderId\":").writeLong(value).writeByte('}');
        }
    }
}
