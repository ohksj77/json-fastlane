package io.jsonfastlane;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

public final class CompiledJsonShapeMatcher implements JsonShapeMatcher {
    private final JsonValueKind rootKind;
    private final ExpectedField[] fields;
    private final JsonFastlaneOptions options;

    public CompiledJsonShapeMatcher(ExpectedJsonShape shape) {
        this(shape, JsonFastlaneOptions.defaults());
    }

    public CompiledJsonShapeMatcher(ExpectedJsonShape shape, JsonFastlaneOptions options) {
        Objects.requireNonNull(shape, "shape");
        this.options = Objects.requireNonNull(options, "options");
        this.rootKind = shape.rootKind();
        this.fields = compile(shape.fields());
        if (fields.length > options.maxTopLevelFields()) {
            throw new IllegalArgumentException("expected fields exceed maxTopLevelFields");
        }
    }

    @Override
    public boolean matches(byte[] utf8Json) {
        try {
            Parser parser = new Parser(utf8Json);
            return parser.matches();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static ExpectedField[] compile(List<ExpectedJsonField> fields) {
        ExpectedField[] compiled = new ExpectedField[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            ExpectedJsonField field = fields.get(i);
            compiled[i] = new ExpectedField(
                field.name().getBytes(StandardCharsets.UTF_8),
                field.kind()
            );
        }
        return compiled;
    }

    private record ExpectedField(byte[] utf8Name, JsonValueKind kind) {
    }

    private final class Parser {
        private final byte[] bytes;
        private int index;

        private Parser(byte[] bytes) {
            this.bytes = Objects.requireNonNull(bytes, "bytes");
        }

        private boolean matches() {
            skipWhitespace();
            boolean matched = switch (rootKind) {
                case OBJECT -> matchesRootObject();
                case ARRAY -> {
                    if (!peek('[')) {
                        yield false;
                    }
                    skipArray(1);
                    yield true;
                }
                case STRING, NUMBER, BOOLEAN, NULL -> skipValue(1) == rootKind;
                case UNKNOWN -> false;
            };
            skipWhitespace();
            return matched && isAtEnd();
        }

        private boolean matchesRootObject() {
            if (!peek('{')) {
                return false;
            }
            index++;
            skipWhitespace();

            if (fields.length == 0) {
                if (!peek('}')) {
                    return false;
                }
                index++;
                return true;
            }

            for (int i = 0; i < fields.length; i++) {
                skipWhitespace();
                if (!fieldNameMatches(fields[i].utf8Name())) {
                    return false;
                }
                skipWhitespace();
                expect(':');
                skipWhitespace();
                if (skipValue(1) != fields[i].kind()) {
                    return false;
                }
                skipWhitespace();

                if (i + 1 < fields.length) {
                    if (!peek(',')) {
                        return false;
                    }
                    index++;
                } else {
                    if (!peek('}')) {
                        return false;
                    }
                    index++;
                }
            }
            return true;
        }

        private boolean fieldNameMatches(byte[] expected) {
            expect('"');
            int matched = 0;
            boolean exact = true;
            while (!isAtEnd()) {
                int current = bytes[index++] & 0xff;
                if (current == '"') {
                    return exact && matched == expected.length;
                }
                if (current == '\\') {
                    skipEscapedCharacter();
                    exact = false;
                    continue;
                }
                if (matched >= expected.length || bytes[index - 1] != expected[matched]) {
                    exact = false;
                }
                matched++;
            }
            throw error("Unterminated string");
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
                skipString();
                skipWhitespace();
                expect(':');
                skipValue(depth + 1);
                skipWhitespace();
                if (peek(',')) {
                    index++;
                    skipWhitespace();
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
                    skipWhitespace();
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
            if (tryConsumeAscii("true") || tryConsumeAscii("false")) {
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

        private boolean isDigit(int value) {
            return value >= '0' && value <= '9';
        }

        private int hexDigit(int value) {
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
