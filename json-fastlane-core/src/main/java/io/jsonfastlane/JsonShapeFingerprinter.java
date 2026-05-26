package io.jsonfastlane;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class JsonShapeFingerprinter {
    private JsonShapeFingerprinter() {
    }

    public static JsonShapeFingerprint fingerprint(byte[] utf8Json) {
        return fingerprint(utf8Json, JsonFastlaneOptions.defaults());
    }

    public static JsonShapeFingerprint fingerprint(byte[] utf8Json, JsonFastlaneOptions options) {
        Parser parser = new Parser(utf8Json, options, CheckpointObserver.NONE);
        return parser.fingerprint();
    }

    public static JsonShapeFingerprint fingerprint(String json) {
        return fingerprint(json.getBytes(StandardCharsets.UTF_8));
    }

    public static JsonShapeFingerprint fingerprint(String json, JsonFastlaneOptions options) {
        return fingerprint(json.getBytes(StandardCharsets.UTF_8), options);
    }

    public static JsonShapeFingerprintPlan plan(byte[] utf8Json) {
        return plan(utf8Json, JsonFastlaneOptions.defaults());
    }

    public static JsonShapeFingerprintPlan plan(byte[] utf8Json, JsonFastlaneOptions options) {
        CollectingCheckpointObserver observer = new CollectingCheckpointObserver();
        Parser parser = new Parser(utf8Json, options, observer);
        JsonShapeFingerprint fingerprint = parser.fingerprint();
        return new JsonShapeFingerprintPlan(fingerprint, observer.checkpoints());
    }

    public static JsonShapeFingerprintPlan plan(String json) {
        return plan(json.getBytes(StandardCharsets.UTF_8));
    }

    public static JsonShapeFingerprintPlan plan(String json, JsonFastlaneOptions options) {
        return plan(json.getBytes(StandardCharsets.UTF_8), options);
    }

    public static boolean matches(byte[] utf8Json, JsonShapeFingerprintPlan plan) {
        return matches(utf8Json, plan, JsonFastlaneOptions.defaults());
    }

    public static boolean matches(byte[] utf8Json, JsonShapeFingerprintPlan plan, JsonFastlaneOptions options) {
        Objects.requireNonNull(plan, "plan");
        try {
            Parser parser = new Parser(utf8Json, options, new VerifyingCheckpointObserver(plan.checkpoints()));
            return plan.fingerprint().sameHash(parser.fingerprint());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static final class Parser {
        private final byte[] bytes;
        private final JsonFastlaneOptions options;
        private final CheckpointObserver checkpointObserver;
        private final ShapeHash128 hash = new ShapeHash128();
        private int index;
        private int fieldCount;
        private int keyEventCount;
        private int maxKeyDepth;

        private Parser(byte[] bytes, JsonFastlaneOptions options, CheckpointObserver checkpointObserver) {
            this.bytes = Objects.requireNonNull(bytes, "bytes");
            this.options = Objects.requireNonNull(options, "options");
            this.checkpointObserver = Objects.requireNonNull(checkpointObserver, "checkpointObserver");
        }

        private JsonShapeFingerprint fingerprint() {
            skipWhitespace();
            if (isAtEnd()) {
                hash.addRoot(JsonValueKind.UNKNOWN);
                return new JsonShapeFingerprint(hash.high(), hash.low(), JsonValueKind.UNKNOWN, 0, 0);
            }

            JsonValueKind rootKind = peekValueKind();
            hash.addRoot(rootKind);
            skipValue(0);
            skipWhitespace();
            if (!isAtEnd()) {
                throw error("Trailing content");
            }
            hash.finish(fieldCount, maxKeyDepth);
            return new JsonShapeFingerprint(hash.high(), hash.low(), rootKind, fieldCount, maxKeyDepth);
        }

        private JsonValueKind skipValue(int valueDepth) {
            checkDepth(valueDepth + 1);
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
                    skipObject(valueDepth);
                    yield JsonValueKind.OBJECT;
                }
                case '[' -> {
                    skipArray(valueDepth);
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

        private void skipObject(int valueDepth) {
            checkDepth(valueDepth + 1);
            hash.addObjectStart(valueDepth);
            expect('{');
            skipWhitespace();

            if (peek('}')) {
                index++;
                hash.addObjectEnd(valueDepth, 0);
                return;
            }

            int fieldPosition = 0;
            int keyDepth = valueDepth + 1;
            while (!isAtEnd()) {
                skipWhitespace();
                NameHash nameHash = readFieldNameHash();
                skipWhitespace();
                expect(':');
                skipWhitespace();

                if (fieldPosition >= options.maxTopLevelFields()) {
                    throw error("Too many object fields");
                }

                JsonValueKind valueKind = peekValueKind();
                fieldCount++;
                if (keyDepth > maxKeyDepth) {
                    maxKeyDepth = keyDepth;
                }
                hash.addField(keyDepth, fieldPosition, valueKind, nameHash);
                keyEventCount++;
                if (!checkpointObserver.afterKey(keyEventCount, hash.high(), hash.low())) {
                    throw error("Shape checkpoint mismatch");
                }
                skipValue(keyDepth);
                fieldPosition++;
                skipWhitespace();

                if (peek(',')) {
                    index++;
                    continue;
                }
                if (peek('}')) {
                    index++;
                    hash.addObjectEnd(valueDepth, fieldPosition);
                    return;
                }

                throw error("Expected ',' or '}'");
            }

            throw error("Unterminated object");
        }

        private void skipArray(int valueDepth) {
            checkDepth(valueDepth + 1);
            hash.addArrayStart(valueDepth);
            expect('[');
            skipWhitespace();

            if (peek(']')) {
                index++;
                hash.addArrayEnd(valueDepth, 0);
                return;
            }

            int itemCount = 0;
            while (!isAtEnd()) {
                hash.addArrayItem(valueDepth, itemCount, peekValueKind());
                skipValue(valueDepth);
                itemCount++;
                skipWhitespace();

                if (peek(',')) {
                    index++;
                    continue;
                }
                if (peek(']')) {
                    index++;
                    hash.addArrayEnd(valueDepth, itemCount);
                    return;
                }

                throw error("Expected ',' or ']'");
            }

            throw error("Unterminated array");
        }

        private JsonValueKind peekValueKind() {
            skipWhitespace();
            if (isAtEnd()) {
                return JsonValueKind.UNKNOWN;
            }

            int current = bytes[index] & 0xff;
            return switch (current) {
                case '"' -> JsonValueKind.STRING;
                case '{' -> JsonValueKind.OBJECT;
                case '[' -> JsonValueKind.ARRAY;
                case 't', 'f' -> JsonValueKind.BOOLEAN;
                case 'n' -> JsonValueKind.NULL;
                default -> current == '-' || isDigit(current) ? JsonValueKind.NUMBER : JsonValueKind.UNKNOWN;
            };
        }

        private NameHash readFieldNameHash() {
            expect('"');
            NameHash hash = new NameHash();
            while (!isAtEnd()) {
                int current = bytes[index++] & 0xff;
                if (current == '"') {
                    return hash.finish();
                }
                if (current == '\\') {
                    appendEscapedCharacter(hash);
                } else {
                    hash.addByte(current);
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

        private void appendEscapedCharacter(NameHash hash) {
            if (isAtEnd()) {
                throw error("Unterminated escape sequence");
            }

            int escaped = bytes[index++] & 0xff;
            switch (escaped) {
                case '"', '\\', '/' -> hash.addByte(escaped);
                case 'b' -> hash.addByte('\b');
                case 'f' -> hash.addByte('\f');
                case 'n' -> hash.addByte('\n');
                case 'r' -> hash.addByte('\r');
                case 't' -> hash.addByte('\t');
                case 'u' -> hash.addCodePoint(readUnicodeCodePoint());
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

        private int readUnicodeCodePoint() {
            char high = readUnicodeEscape();
            if (!Character.isHighSurrogate(high)) {
                return high;
            }

            if (index + 6 <= bytes.length && bytes[index] == '\\' && bytes[index + 1] == 'u') {
                index += 2;
                char low = readUnicodeEscape();
                if (Character.isLowSurrogate(low)) {
                    return Character.toCodePoint(high, low);
                }
            }
            return high;
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

    private static final class ShapeHash128 {
        private long high = 0x243f6a8885a308d3L;
        private long low = 0x13198a2e03707344L;
        private long count;

        private void addRoot(JsonValueKind kind) {
            addTagged(0x01, kind.ordinal());
        }

        private void addObjectStart(int depth) {
            addTagged(0x02, depth);
        }

        private void addObjectEnd(int depth, int fields) {
            addTagged(0x03, ((long) depth << 32) ^ fields);
        }

        private void addArrayStart(int depth) {
            addTagged(0x04, depth);
        }

        private void addArrayItem(int depth, int position, JsonValueKind kind) {
            addTagged(0x05, ((long) depth << 48) ^ ((long) position << 8) ^ kind.ordinal());
        }

        private void addArrayEnd(int depth, int items) {
            addTagged(0x06, ((long) depth << 32) ^ items);
        }

        private void addField(int depth, int position, JsonValueKind kind, NameHash name) {
            addTagged(0x07, ((long) depth << 48) ^ ((long) position << 8) ^ kind.ordinal());
            add(name.high);
            add(name.low);
            add(name.length);
        }

        private void finish(int fields, int depth) {
            addTagged(0x08, ((long) fields << 32) ^ depth);
            add(count);
        }

        private void addTagged(int tag, long value) {
            add(((long) tag << 56) ^ value);
        }

        private void add(long value) {
            long mixed = mix64(value + 0x9e3779b97f4a7c15L + count++);
            high ^= mixed;
            high = Long.rotateLeft(high, 27) * 0x3c79ac492ba7b653L + 0x1c69b3f74ac4ae35L;
            low ^= mix64(mixed + high + 0x632be59bd9b4e019L);
            low = Long.rotateLeft(low, 31) * 0x1c69b3f74ac4ae35L + 0x9e3779b97f4a7c15L;
        }

        private long high() {
            return mix64(high ^ count);
        }

        private long low() {
            return mix64(low + Long.rotateLeft(count, 17));
        }
    }

    private interface CheckpointObserver {
        CheckpointObserver NONE = (keyCount, high, low) -> true;

        boolean afterKey(int keyCount, long high, long low);
    }

    private static final class CollectingCheckpointObserver implements CheckpointObserver {
        private final List<JsonShapeCheckpoint> checkpoints = new ArrayList<>();
        private int nextKeyCount = 1;

        @Override
        public boolean afterKey(int keyCount, long high, long low) {
            if (keyCount == nextKeyCount) {
                checkpoints.add(new JsonShapeCheckpoint(keyCount, high, low));
                if (nextKeyCount < 1 << 30) {
                    nextKeyCount <<= 1;
                }
            }
            return true;
        }

        private List<JsonShapeCheckpoint> checkpoints() {
            return checkpoints;
        }
    }

    private static final class VerifyingCheckpointObserver implements CheckpointObserver {
        private final List<JsonShapeCheckpoint> checkpoints;
        private int index;

        private VerifyingCheckpointObserver(List<JsonShapeCheckpoint> checkpoints) {
            this.checkpoints = checkpoints;
        }

        @Override
        public boolean afterKey(int keyCount, long high, long low) {
            if (index >= checkpoints.size()) {
                return true;
            }

            JsonShapeCheckpoint checkpoint = checkpoints.get(index);
            if (keyCount < checkpoint.keyCount()) {
                return true;
            }
            if (keyCount > checkpoint.keyCount()) {
                return false;
            }

            index++;
            return checkpoint.high() == high && checkpoint.low() == low;
        }
    }

    private static final class NameHash {
        private long high = 0xcbf29ce484222325L;
        private long low = 0x84222325cbf29ce4L;
        private int length;

        private void addByte(int value) {
            int normalized = value & 0xff;
            high ^= normalized;
            high *= 0x100000001b3L;
            low += normalized + 0x9e3779b97f4a7c15L;
            low = Long.rotateLeft(low, 23) * 0x9ddfea08eb382d69L;
            length++;
        }

        private void addCodePoint(int codePoint) {
            if (codePoint <= 0x7f) {
                addByte(codePoint);
            } else if (codePoint <= 0x7ff) {
                addByte(0xc0 | (codePoint >> 6));
                addByte(0x80 | (codePoint & 0x3f));
            } else if (codePoint <= 0xffff) {
                addByte(0xe0 | (codePoint >> 12));
                addByte(0x80 | ((codePoint >> 6) & 0x3f));
                addByte(0x80 | (codePoint & 0x3f));
            } else {
                addByte(0xf0 | (codePoint >> 18));
                addByte(0x80 | ((codePoint >> 12) & 0x3f));
                addByte(0x80 | ((codePoint >> 6) & 0x3f));
                addByte(0x80 | (codePoint & 0x3f));
            }
        }

        private NameHash finish() {
            high = mix64(high ^ length);
            low = mix64(low + length);
            return this;
        }
    }

    private static long mix64(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }
}
