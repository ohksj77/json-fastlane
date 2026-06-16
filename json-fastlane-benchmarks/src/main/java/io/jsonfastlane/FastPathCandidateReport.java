package io.jsonfastlane;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

public final class FastPathCandidateReport {
    private FastPathCandidateReport() {
    }

    public static void main(String[] args) throws IOException {
        JsonFastlane fastlane = new JsonFastlane();
        String samplePath = stringProperty("jsonfastlane.report.samples", "");
        ReportConfig config = ReportConfig.load(stringProperty("jsonfastlane.report.config", ""));
        if (samplePath.isBlank()) {
            recordSyntheticSamples(fastlane);
        } else {
            recordCapturedSamples(fastlane, Path.of(samplePath), config);
        }

        if (stringProperty("jsonfastlane.report.output", "text").equalsIgnoreCase("json")) {
            System.out.print(JsonFastlaneReport.json(fastlane.snapshots()));
        } else {
            System.out.print(JsonFastlaneReport.text(fastlane.snapshots()));
        }
    }

    private static void recordSyntheticSamples(JsonFastlane fastlane) {
        int stableSamples = integerProperty("jsonfastlane.report.stableSamples", 250);
        int driftingSamples = integerProperty("jsonfastlane.report.driftingSamples", 60);

        for (int i = 0; i < stableSamples; i++) {
            fastlane.record("/checkout", checkoutJson(i));
        }
        for (int i = 0; i < driftingSamples; i++) {
            fastlane.record("/search", i % 2 == 0 ? searchJson(i) : shuffledSearchJson(i));
        }
        fastlane.record("/health", "true");
    }

    static void recordCapturedSamples(JsonFastlane fastlane, Path path) throws IOException {
        recordCapturedSamples(fastlane, path, ReportConfig.empty());
    }

    static void recordCapturedSamples(JsonFastlane fastlane, Path path, ReportConfig config) throws IOException {
        if (Files.isDirectory(path)) {
            recordDirectorySamples(fastlane, path, config);
            return;
        }
        recordLineSamples(fastlane, path, config);
    }

    private static void recordDirectorySamples(JsonFastlane fastlane, Path directory, ReportConfig config)
        throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path sample : paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".json"))
                .sorted(Comparator.comparing(Path::toString))
                .toList()) {
                String endpoint = config.mapEndpoint(endpointFromPath(directory, sample));
                fastlane.record(endpoint, config.redact(Files.readString(sample, StandardCharsets.UTF_8)));
            }
        }
    }

    private static void recordLineSamples(JsonFastlane fastlane, Path file, ReportConfig config) throws IOException {
        int lineNumber = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lineNumber++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('\t');
            if (separator < 0) {
                separator = line.indexOf(' ');
            }
            if (separator <= 0 || separator + 1 >= line.length()) {
                throw new IllegalArgumentException("Invalid sample line " + lineNumber
                    + ": expected '<endpoint><tab><json>'");
            }
            fastlane.record(
                config.mapEndpoint(line.substring(0, separator)),
                config.redact(line.substring(separator + 1).trim())
            );
        }
    }

    static String endpointFromPath(Path root, Path sample) {
        Path relative = root.relativize(sample);
        String value = relative.toString().replace('\\', '/');
        if (value.endsWith(".json")) {
            value = value.substring(0, value.length() - ".json".length());
        }
        int lastDash = value.lastIndexOf('-');
        if (lastDash > 0 && numericSuffix(value, lastDash + 1)) {
            value = value.substring(0, lastDash);
        }
        return "/" + value;
    }

    private static boolean numericSuffix(String value, int offset) {
        if (offset >= value.length()) {
            return false;
        }
        for (int i = offset; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String checkoutJson(int index) {
        return "{\"userId\":" + index
            + ",\"items\":[{\"sku\":\"cup\",\"quantity\":2}]"
            + ",\"couponCode\":null}";
    }

    private static String searchJson(int index) {
        return "{\"query\":\"json\",\"page\":" + index + ",\"filters\":[]}";
    }

    private static String shuffledSearchJson(int index) {
        return "{\"filters\":[],\"query\":\"json\",\"page\":" + index + "}";
    }

    private static int integerProperty(String name, int fallback) {
        String value = stringProperty(name, "");
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(value);
    }

    private static String stringProperty(String name, String fallback) {
        String value = System.getProperty(name);
        return value == null ? fallback : value;
    }

    static final class ReportConfig {
        private final List<EndpointMapping> endpointMappings;
        private final Set<String> redactFields;

        private ReportConfig(List<EndpointMapping> endpointMappings, Set<String> redactFields) {
            this.endpointMappings = endpointMappings;
            this.redactFields = redactFields;
        }

        static ReportConfig empty() {
            return new ReportConfig(List.of(), Set.of());
        }

        static ReportConfig load(String configPath) throws IOException {
            if (configPath == null || configPath.isBlank()) {
                return empty();
            }

            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(Path.of(configPath), StandardCharsets.UTF_8)) {
                properties.load(reader);
            }

            List<EndpointMapping> mappings = new ArrayList<>();
            for (Map.Entry<Object, Object> entry : properties.entrySet()) {
                String key = entry.getKey().toString();
                if (key.startsWith("endpoint.")) {
                    mappings.add(new EndpointMapping(key.substring("endpoint.".length()), entry.getValue().toString()));
                }
            }
            mappings.sort(Comparator.comparingInt((EndpointMapping mapping) -> mapping.prefix().length()).reversed());

            String redactFields = properties.getProperty("redactFields", "");
            Set<String> fields = redactFields.isBlank()
                ? Set.of()
                : Set.of(redactFields.replace(" ", "").split(","));
            return new ReportConfig(mappings, fields);
        }

        String mapEndpoint(String endpoint) {
            for (EndpointMapping mapping : endpointMappings) {
                if (endpoint.startsWith(mapping.prefix())) {
                    return mapping.target();
                }
            }
            return endpoint;
        }

        String redact(String json) {
            String redacted = json;
            for (String field : redactFields) {
                if (!field.isBlank()) {
                    redacted = redactTopLevelField(redacted, field);
                }
            }
            return redacted;
        }

        private static String redactTopLevelField(String json, String field) {
            String needle = "\"" + field + "\"";
            int nameStart = json.indexOf(needle);
            if (nameStart < 0) {
                return json;
            }
            int colon = json.indexOf(':', nameStart + needle.length());
            if (colon < 0) {
                return json;
            }
            int valueStart = colon + 1;
            while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
                valueStart++;
            }
            int valueEnd = valueEnd(json, valueStart);
            if (valueEnd <= valueStart) {
                return json;
            }
            return json.substring(0, valueStart) + "null" + json.substring(valueEnd);
        }

        private static int valueEnd(String json, int offset) {
            if (offset >= json.length()) {
                return offset;
            }
            char first = json.charAt(offset);
            if (first == '"') {
                return stringEnd(json, offset);
            }
            if (first == '{' || first == '[') {
                return containerEnd(json, offset, first, first == '{' ? '}' : ']');
            }
            int index = offset;
            while (index < json.length()) {
                char current = json.charAt(index);
                if (current == ',' || current == '}') {
                    break;
                }
                index++;
            }
            return index;
        }

        private static int stringEnd(String json, int offset) {
            int index = offset + 1;
            while (index < json.length()) {
                char current = json.charAt(index++);
                if (current == '\\') {
                    index++;
                } else if (current == '"') {
                    return index;
                }
            }
            return json.length();
        }

        private static int containerEnd(String json, int offset, char open, char close) {
            int depth = 0;
            int index = offset;
            while (index < json.length()) {
                char current = json.charAt(index);
                if (current == '"') {
                    index = stringEnd(json, index);
                    continue;
                }
                if (current == open) {
                    depth++;
                } else if (current == close) {
                    depth--;
                    if (depth == 0) {
                        return index + 1;
                    }
                }
                index++;
            }
            return json.length();
        }
    }

    private record EndpointMapping(String prefix, String target) {
    }
}
