package io.jsonfastlane;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class JsonShapeScanner {
    private JsonShapeScanner() {
    }

    static JsonShapeObservation scan(byte[] utf8Json) {
        return scan(utf8Json, JsonFastlaneOptions.defaults());
    }

    static JsonShapeObservation scan(byte[] utf8Json, JsonFastlaneOptions options) {
        return scan(utf8Json, FieldNameResolver.DEFAULT, options);
    }

    static JsonShapeObservation scan(byte[] utf8Json, FieldNameResolver fieldNameResolver) {
        return scan(utf8Json, fieldNameResolver, JsonFastlaneOptions.defaults());
    }

    static JsonShapeObservation scan(
        byte[] utf8Json,
        FieldNameResolver fieldNameResolver,
        JsonFastlaneOptions options
    ) {
        Parser parser = new Parser(utf8Json, fieldNameResolver, options);
        return parser.scan();
    }

    interface FieldNameResolver {
        FieldNameResolver DEFAULT = new FieldNameResolver() {
        };

        default String resolveUnescapedFieldName(byte[] utf8Json, int start, int end) {
            return new String(utf8Json, start, end - start, StandardCharsets.UTF_8);
        }

        default String canonicalFieldName(String name) {
            return name;
        }
    }

    private static final class Parser {
        private final byte[] bytes;
        private final FieldNameResolver fieldNameResolver;
        private final JsonFastlaneOptions options;
        private int index;

        private Parser(byte[] bytes, FieldNameResolver fieldNameResolver, JsonFastlaneOptions options) {
            this.bytes = bytes;
            this.fieldNameResolver = fieldNameResolver;
            this.options = options;
        }

        private JsonShapeObservation scan() {
            skipWhitespace();
            if (isAtEnd()) {
                return new JsonShapeObservation(bytes.length, JsonValueKind.UNKNOWN, List.of());
            }

            int current = bytes[index] & 0xff;
            if (current == '{') {
                List<FieldObservation> fields = scanRootObject();
                return new JsonShapeObservation(bytes.length, JsonValueKind.OBJECT, fields);
            }
            if (current == '[') {
                skipArray(1);
                return new JsonShapeObservation(bytes.length, JsonValueKind.ARRAY, List.of());
            }

            JsonValueKind rootKind = skipValue(1);
            return new JsonShapeObservation(bytes.length, rootKind, List.of());
        }

        private List<FieldObservation> scanRootObject() {
            List<FieldObservation> fields = new ArrayList<>();
            expect('{');
            skipWhitespace();
            int position = 0;

            if (peek('}')) {
                index++;
                return fields;
            }

            while (!isAtEnd()) {
                skipWhitespace();
                String name = readFieldName();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                if (position >= options.maxTopLevelFields()) {
                    throw error("Too many top-level fields");
                }
                JsonValueKind kind = skipValue(1);
                fields.add(new FieldObservation(name, kind, position++));
                skipWhitespace();

                if (peek(',')) {
                    index++;
                    continue;
                }
                if (peek('}')) {
                    index++;
                    return fields;
                }

                throw error("Expected ',' or '}'");
            }

            throw error("Unterminated object");
        }

        private JsonValueKind skipValue(int depth) {
            checkDepth(depth);
            skipWhitespace();
            if (isAtEnd()) {
                return JsonValueKind.UNKNOWN;
            }

            int current = bytes[index] & 0xff;
            return switch (current) {
                case '"' -> {
                    skipString();
                    yield JsonValueKind.STRING;
                }
                case '{' -> {
                    skipObject(depth);
                    yield JsonValueKind.OBJECT;
                }
                case '[' -> {
                    skipArray(depth);
                    yield JsonValueKind.ARRAY;
                }
                case 't', 'f' -> {
                    skipBoolean();
                    yield JsonValueKind.BOOLEAN;
                }
                case 'n' -> {
                    skipNull();
                    yield JsonValueKind.NULL;
                }
                default -> {
                    if (current == '-' || isDigit(current)) {
                        skipNumber();
                        yield JsonValueKind.NUMBER;
                    }
                    throw error("Unexpected value");
                }
            };
        }

        private void skipObject(int depth) {
            checkDepth(depth);
            expect('{');
            skipWhitespace();

            if (peek('}')) {
                index++;
                return;
            }

            while (!isAtEnd()) {
                skipWhitespace();
                skipString();
                skipWhitespace();
                expect(':');
                skipValue(depth + 1);
                skipWhitespace();

                if (peek(',')) {
                    index++;
                    continue;
                }
                if (peek('}')) {
                    index++;
                    return;
                }

                throw error("Expected ',' or '}'");
            }

            throw error("Unterminated object");
        }

        private void skipArray(int depth) {
            checkDepth(depth);
            expect('[');
            skipWhitespace();

            if (peek(']')) {
                index++;
                return;
            }

            while (!isAtEnd()) {
                skipValue(depth + 1);
                skipWhitespace();

                if (peek(',')) {
                    index++;
                    continue;
                }
                if (peek(']')) {
                    index++;
                    return;
                }

                throw error("Expected ',' or ']'");
            }

            throw error("Unterminated array");
        }

        private String readFieldName() {
            expect('"');
            StringBuilder builder = null;
            int start = index;

            while (!isAtEnd()) {
                int current = bytes[index++] & 0xff;
                if (current == '"') {
                    int end = index - 1;
                    if (builder == null) {
                        return fieldNameResolver.resolveUnescapedFieldName(bytes, start, end);
                    }
                    appendUtf8(builder, start, end);
                    return fieldNameResolver.canonicalFieldName(builder.toString());
                }

                if (current == '\\') {
                    if (builder == null) {
                        builder = new StringBuilder();
                    }
                    appendUtf8(builder, start, index - 1);
                    appendEscapedCharacter(builder);
                    start = index;
                }
            }

            throw error("Unterminated string");
        }

        private void skipString() {
            expect('"');
            while (!isAtEnd()) {
                int current = bytes[index++] & 0xff;
                if (current == '"') {
                    return;
                }
                if (current == '\\') {
                    skipEscapedCharacter();
                }
            }

            throw error("Unterminated string");
        }

        private void appendEscapedCharacter(StringBuilder builder) {
            if (isAtEnd()) {
                throw error("Unterminated escape sequence");
            }

            int escaped = bytes[index++] & 0xff;
            switch (escaped) {
                case '"', '\\', '/' -> builder.append((char) escaped);
                case 'b' -> builder.append('\b');
                case 'f' -> builder.append('\f');
                case 'n' -> builder.append('\n');
                case 'r' -> builder.append('\r');
                case 't' -> builder.append('\t');
                case 'u' -> builder.append(readUnicodeEscape());
                default -> throw error("Invalid escape sequence");
            }
        }

        private void skipEscapedCharacter() {
            if (isAtEnd()) {
                throw error("Unterminated escape sequence");
            }

            int escaped = bytes[index++] & 0xff;
            switch (escaped) {
                case '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> {
                }
                case 'u' -> skipUnicodeEscape();
                default -> throw error("Invalid escape sequence");
            }
        }

        private char readUnicodeEscape() {
            if (index + 4 > bytes.length) {
                throw error("Invalid unicode escape");
            }

            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = hexDigit(bytes[index++] & 0xff);
                if (digit < 0) {
                    throw error("Invalid unicode escape");
                }
                value = (value << 4) + digit;
            }
            return (char) value;
        }

        private void skipUnicodeEscape() {
            if (index + 4 > bytes.length) {
                throw error("Invalid unicode escape");
            }

            for (int i = 0; i < 4; i++) {
                if (hexDigit(bytes[index++] & 0xff) < 0) {
                    throw error("Invalid unicode escape");
                }
            }
        }

        private void skipBoolean() {
            if (tryConsumeAscii("true")) {
                return;
            }
            if (tryConsumeAscii("false")) {
                return;
            }
            throw error("Invalid boolean");
        }

        private void skipNull() {
            if (!tryConsumeAscii("null")) {
                throw error("Invalid null");
            }
        }

        private void skipNumber() {
            if (peek('-')) {
                index++;
            }
            readDigits();
            if (peek('.')) {
                index++;
                readDigits();
            }
            if (peek('e') || peek('E')) {
                index++;
                if (peek('+') || peek('-')) {
                    index++;
                }
                readDigits();
            }
        }

        private void readDigits() {
            int start = index;
            while (!isAtEnd() && isDigit(bytes[index] & 0xff)) {
                index++;
            }
            if (start == index) {
                throw error("Expected digit");
            }
        }

        private void skipWhitespace() {
            while (!isAtEnd()) {
                int current = bytes[index] & 0xff;
                if (current == ' ' || current == '\n' || current == '\r' || current == '\t') {
                    index++;
                    continue;
                }
                return;
            }
        }

        private void expect(char expected) {
            if (isAtEnd() || (bytes[index] & 0xff) != expected) {
                throw error("Expected '" + expected + "'");
            }
            index++;
        }

        private boolean peek(char expected) {
            return !isAtEnd() && (bytes[index] & 0xff) == expected;
        }

        private boolean tryConsumeAscii(String value) {
            if (index + value.length() > bytes.length) {
                return false;
            }
            for (int i = 0; i < value.length(); i++) {
                if ((bytes[index + i] & 0xff) != value.charAt(i)) {
                    return false;
                }
            }
            index += value.length();
            return true;
        }

        private String decodeUtf8(int start, int end) {
            return new String(bytes, start, end - start, StandardCharsets.UTF_8);
        }

        private void appendUtf8(StringBuilder builder, int start, int end) {
            if (end > start) {
                builder.append(decodeUtf8(start, end));
            }
        }

        private boolean isAtEnd() {
            return index >= bytes.length;
        }

        private void checkDepth(int depth) {
            if (depth > options.maxNestingDepth()) {
                throw error("JSON nesting depth exceeded");
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at offset " + index);
        }

        private static boolean isDigit(int value) {
            return value >= '0' && value <= '9';
        }

        private static int hexDigit(int value) {
            if (value >= '0' && value <= '9') {
                return value - '0';
            }
            if (value >= 'a' && value <= 'f') {
                return value - 'a' + 10;
            }
            if (value >= 'A' && value <= 'F') {
                return value - 'A' + 10;
            }
            return -1;
        }
    }
}
