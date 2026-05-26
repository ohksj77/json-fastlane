package io.jsonfastlane.transport;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Objects;

public final class OutputStreamJsonSink implements JsonSink {
    private static final byte[] TRUE = {'t', 'r', 'u', 'e'};
    private static final byte[] FALSE = {'f', 'a', 'l', 's', 'e'};
    private static final byte[] NULL = {'n', 'u', 'l', 'l'};
    private static final byte[] ESCAPED_QUOTE = {'\\', '"'};
    private static final byte[] ESCAPED_BACKSLASH = {'\\', '\\'};
    private static final byte[] ESCAPED_BACKSPACE = {'\\', 'b'};
    private static final byte[] ESCAPED_FORM_FEED = {'\\', 'f'};
    private static final byte[] ESCAPED_NEWLINE = {'\\', 'n'};
    private static final byte[] ESCAPED_CARRIAGE_RETURN = {'\\', 'r'};
    private static final byte[] ESCAPED_TAB = {'\\', 't'};
    private static final byte[] MIN_LONG = {
        '-', '9', '2', '2', '3', '3', '7', '2', '0', '3',
        '6', '8', '5', '4', '7', '7', '5', '8', '0', '8'
    };
    private static final byte[] MIN_INT = {
        '-', '2', '1', '4', '7', '4', '8', '3', '6', '4', '8'
    };
    private static final byte[] HEX = {
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };

    private final OutputStream out;
    private final byte[] number = new byte[20];
    private long bytesWritten;

    public OutputStreamJsonSink(OutputStream out) {
        this.out = Objects.requireNonNull(out, "out");
    }

    @Override
    public JsonSink writeByte(char value) {
        writeOne(value);
        return this;
    }

    @Override
    public JsonSink writeSegment(JsonSegment segment) {
        writeBytes(segment.bytes());
        return this;
    }

    @Override
    public JsonSink writeLong(long value) {
        if (value == Long.MIN_VALUE) {
            writeBytes(MIN_LONG);
            return this;
        }
        if (value == 0) {
            writeOne('0');
            return this;
        }
        if (value < 0) {
            writeOne('-');
            value = -value;
        }

        int index = number.length;
        while (value > 0) {
            long next = value / 10;
            number[--index] = (byte) ('0' + (value - (next * 10)));
            value = next;
        }
        writeBytes(number, index, number.length - index);
        return this;
    }

    @Override
    public JsonSink writeInt(int value) {
        if (value == Integer.MIN_VALUE) {
            writeBytes(MIN_INT);
            return this;
        }
        if (value == 0) {
            writeOne('0');
            return this;
        }
        if (value < 0) {
            writeOne('-');
            value = -value;
        }

        int index = number.length;
        while (value > 0) {
            int next = value / 10;
            number[--index] = (byte) ('0' + (value - (next * 10)));
            value = next;
        }
        writeBytes(number, index, number.length - index);
        return this;
    }

    @Override
    public JsonSink writeBoolean(boolean value) {
        writeBytes(value ? TRUE : FALSE);
        return this;
    }

    @Override
    public JsonSink writeNull() {
        writeBytes(NULL);
        return this;
    }

    @Override
    public JsonSink writeString(String value) {
        if (value == null) {
            return writeNull();
        }

        writeOne('"');
        int length = value.length();
        for (int i = 0; i < length; i++) {
            char current = value.charAt(i);
            if (current >= 0x20 && current != '"' && current != '\\' && current <= 0x7f) {
                writeOne(current);
            } else {
                i = writeEscapedOrUtf8(value, i, current);
            }
        }
        writeOne('"');
        return this;
    }

    public long bytesWritten() {
        return bytesWritten;
    }

    private int writeEscapedOrUtf8(String value, int index, char current) {
        switch (current) {
            case '"' -> writeBytes(ESCAPED_QUOTE);
            case '\\' -> writeBytes(ESCAPED_BACKSLASH);
            case '\b' -> writeBytes(ESCAPED_BACKSPACE);
            case '\f' -> writeBytes(ESCAPED_FORM_FEED);
            case '\n' -> writeBytes(ESCAPED_NEWLINE);
            case '\r' -> writeBytes(ESCAPED_CARRIAGE_RETURN);
            case '\t' -> writeBytes(ESCAPED_TAB);
            default -> {
                if (current < 0x20) {
                    writeUnicodeEscape(current);
                } else if (current <= 0x7ff) {
                    writeOne(0xc0 | (current >> 6));
                    writeOne(0x80 | (current & 0x3f));
                } else if (Character.isHighSurrogate(current) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                    int codePoint = Character.toCodePoint(current, value.charAt(index + 1));
                    writeOne(0xf0 | (codePoint >> 18));
                    writeOne(0x80 | ((codePoint >> 12) & 0x3f));
                    writeOne(0x80 | ((codePoint >> 6) & 0x3f));
                    writeOne(0x80 | (codePoint & 0x3f));
                    return index + 1;
                } else {
                    writeOne(0xe0 | (current >> 12));
                    writeOne(0x80 | ((current >> 6) & 0x3f));
                    writeOne(0x80 | (current & 0x3f));
                }
            }
        }
        return index;
    }

    private void writeUnicodeEscape(char value) {
        writeOne('\\');
        writeOne('u');
        writeOne('0');
        writeOne('0');
        writeOne(HEX[(value >> 4) & 0xf]);
        writeOne(HEX[value & 0xf]);
    }

    private void writeOne(int value) {
        try {
            out.write(value);
            bytesWritten++;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void writeBytes(byte[] value) {
        writeBytes(value, 0, value.length);
    }

    private void writeBytes(byte[] value, int offset, int length) {
        try {
            out.write(value, offset, length);
            bytesWritten += length;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
