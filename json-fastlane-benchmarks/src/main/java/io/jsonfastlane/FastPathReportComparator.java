package io.jsonfastlane;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class FastPathReportComparator {
    private FastPathReportComparator() {
    }

    public static void main(String[] args) throws IOException {
        Path baseline = requiredPath("jsonfastlane.compare.baseline");
        Path current = requiredPath("jsonfastlane.compare.current");
        int maxScoreDrop = integerProperty("jsonfastlane.compare.maxScoreDrop", 20);
        double maxHotOrderRatioDrop = doubleProperty("jsonfastlane.compare.maxHotOrderRatioDrop", 0.20);

        Comparison comparison = compare(
            Files.readString(baseline, StandardCharsets.UTF_8),
            Files.readString(current, StandardCharsets.UTF_8),
            maxScoreDrop,
            maxHotOrderRatioDrop
        );

        System.out.print(comparison.report());
        if (!comparison.passed()) {
            throw new IllegalStateException("json-fastlane candidate report regression detected");
        }
    }

    static Comparison compare(
        String baselineJson,
        String currentJson,
        int maxScoreDrop,
        double maxHotOrderRatioDrop
    ) {
        Map<String, Candidate> baseline = parseCandidates(baselineJson);
        Map<String, Candidate> current = parseCandidates(currentJson);
        StringBuilder report = new StringBuilder("json-fastlane report comparison\n");
        boolean passed = true;

        for (Candidate before : baseline.values()) {
            Candidate after = current.get(before.endpoint());
            if (after == null) {
                report.append("FAIL ").append(before.endpoint()).append(" missing-current-candidate\n");
                passed = false;
                continue;
            }

            int scoreDrop = before.score() - after.score();
            double ratioDrop = before.hotFieldOrderRatio() - after.hotFieldOrderRatio();
            long droppedOrderIncrease = after.droppedFieldOrders() - before.droppedFieldOrders();
            boolean endpointPassed = scoreDrop <= maxScoreDrop
                && ratioDrop <= maxHotOrderRatioDrop
                && droppedOrderIncrease <= 0;
            if (!endpointPassed) {
                passed = false;
            }
            report.append(endpointPassed ? "PASS " : "FAIL ")
                .append(before.endpoint())
                .append(" score ").append(before.score()).append(" -> ").append(after.score())
                .append(", hotOrderRatio ").append(round(before.hotFieldOrderRatio()))
                .append(" -> ").append(round(after.hotFieldOrderRatio()))
                .append(", droppedFieldOrders ").append(before.droppedFieldOrders())
                .append(" -> ").append(after.droppedFieldOrders())
                .append('\n');
        }

        for (Candidate after : current.values()) {
            if (!baseline.containsKey(after.endpoint())) {
                report.append("INFO ").append(after.endpoint()).append(" new-current-candidate score=")
                    .append(after.score()).append('\n');
            }
        }

        return new Comparison(passed, report.toString());
    }

    private static Map<String, Candidate> parseCandidates(String json) {
        int key = json.indexOf("\"candidates\"");
        if (key < 0) {
            return Map.of();
        }
        int arrayStart = json.indexOf('[', key);
        int arrayEnd = matchingBracket(json, arrayStart);
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        int index = arrayStart + 1;
        while (index < arrayEnd) {
            int objectStart = json.indexOf('{', index);
            if (objectStart < 0 || objectStart >= arrayEnd) {
                break;
            }
            int objectEnd = matchingBrace(json, objectStart);
            Candidate candidate = parseCandidate(json.substring(objectStart, objectEnd + 1));
            candidates.put(candidate.endpoint(), candidate);
            index = objectEnd + 1;
        }
        return candidates;
    }

    private static Candidate parseCandidate(String object) {
        return new Candidate(
            stringValue(object, "endpoint"),
            intValue(object, "score"),
            doubleValue(object, "hotFieldOrderRatio"),
            longValue(object, "droppedFieldOrders")
        );
    }

    private static String stringValue(String object, String name) {
        int key = object.indexOf("\"" + name + "\"");
        int colon = object.indexOf(':', key);
        int start = object.indexOf('"', colon + 1) + 1;
        int end = start;
        while (end < object.length()) {
            char current = object.charAt(end);
            if (current == '\\') {
                end += 2;
                continue;
            }
            if (current == '"') {
                break;
            }
            end++;
        }
        return object.substring(start, end);
    }

    private static int intValue(String object, String name) {
        return Integer.parseInt(rawValue(object, name));
    }

    private static long longValue(String object, String name) {
        return Long.parseLong(rawValue(object, name));
    }

    private static double doubleValue(String object, String name) {
        return Double.parseDouble(rawValue(object, name));
    }

    private static String rawValue(String object, String name) {
        int key = object.indexOf("\"" + name + "\"");
        int colon = object.indexOf(':', key);
        int start = colon + 1;
        while (start < object.length() && Character.isWhitespace(object.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < object.length()) {
            char current = object.charAt(end);
            if (current == ',' || current == '}') {
                break;
            }
            end++;
        }
        return object.substring(start, end).trim();
    }

    private static int matchingBracket(String json, int offset) {
        return matching(json, offset, '[', ']');
    }

    private static int matchingBrace(String json, int offset) {
        return matching(json, offset, '{', '}');
    }

    private static int matching(String json, int offset, char open, char close) {
        int depth = 0;
        for (int index = offset; index < json.length(); index++) {
            char current = json.charAt(index);
            if (current == '"') {
                index = stringEnd(json, index) - 1;
                continue;
            }
            if (current == open) {
                depth++;
            } else if (current == close) {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        throw new IllegalArgumentException("Unterminated JSON container");
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

    private static Path requiredPath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required property: " + property);
        }
        return Path.of(value);
    }

    private static int integerProperty(String name, int fallback) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
    }

    private static double doubleProperty(String name, double fallback) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? fallback : Double.parseDouble(value);
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    record Comparison(boolean passed, String report) {
    }

    private record Candidate(String endpoint, int score, double hotFieldOrderRatio, long droppedFieldOrders) {
    }
}
