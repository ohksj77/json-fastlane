package io.jsonfastlane.netty;

import io.jsonfastlane.transport.JsonSegment;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

public final class NettyJsonBuffer {
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

    private final ByteBuf out;

    public NettyJsonBuffer(ByteBuf out) {
        this.out = out;
    }

    public NettyJsonBuffer writeByte(char value) {
        out.writeByte((byte) value);
        return this;
    }

    public NettyJsonBuffer writeRaw(byte[] value) {
        out.writeBytes(value);
        return this;
    }

    public NettyJsonBuffer writeSegment(JsonSegment segment) {
        return writeRaw(segment.bytes());
    }

    public NettyJsonBuffer writeBoolean(boolean value) {
        return writeRaw(value ? TRUE : FALSE);
    }

    public NettyJsonBuffer writeNull() {
        return writeRaw(NULL);
    }

    public NettyJsonBuffer writeLong(long value) {
        if (value == Long.MIN_VALUE) {
            return writeRaw(MIN_LONG);
        }
        if (value == 0) {
            return writeByte('0');
        }
        if (value < 0) {
            out.writeByte('-');
            value = -value;
        }

        int start = out.writerIndex();
        while (value > 0) {
            long next = value / 10;
            out.writeByte((byte) ('0' + (value - (next * 10))));
            value = next;
        }
        reverse(start, out.writerIndex() - 1);
        return this;
    }

    public NettyJsonBuffer writeInt(int value) {
        if (value == Integer.MIN_VALUE) {
            return writeRaw(MIN_INT);
        }
        if (value == 0) {
            return writeByte('0');
        }
        if (value < 0) {
            out.writeByte('-');
            value = -value;
        }

        int start = out.writerIndex();
        while (value > 0) {
            int next = value / 10;
            out.writeByte((byte) ('0' + (value - (next * 10))));
            value = next;
        }
        reverse(start, out.writerIndex() - 1);
        return this;
    }

    public NettyJsonBuffer writeString(String value) {
        if (value == null) {
            return writeNull();
        }

        out.writeByte('"');
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current >= 0x20 && current != '"' && current != '\\' && current <= 0x7f) {
                out.writeByte((byte) current);
            } else {
                writeSlowStringSuffix(value, i);
                break;
            }
        }
        out.writeByte('"');
        return this;
    }

    private void writeSlowStringSuffix(String value, int start) {
        for (int i = start; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case '"' -> out.writeBytes(ESCAPED_QUOTE);
                case '\\' -> out.writeBytes(ESCAPED_BACKSLASH);
                case '\b' -> out.writeBytes(ESCAPED_BACKSPACE);
                case '\f' -> out.writeBytes(ESCAPED_FORM_FEED);
                case '\n' -> out.writeBytes(ESCAPED_NEWLINE);
                case '\r' -> out.writeBytes(ESCAPED_CARRIAGE_RETURN);
                case '\t' -> out.writeBytes(ESCAPED_TAB);
                default -> {
                    if (current < 0x20) {
                        writeUnicodeEscape(current);
                    } else {
                        out.writeCharSequence(String.valueOf(current), StandardCharsets.UTF_8);
                    }
                }
            }
        }
    }

    private void writeUnicodeEscape(char value) {
        out.writeByte('\\');
        out.writeByte('u');
        out.writeByte('0');
        out.writeByte('0');
        out.writeByte(HEX[(value >> 4) & 0xf]);
        out.writeByte(HEX[value & 0xf]);
    }

    private void reverse(int left, int right) {
        while (left < right) {
            byte swap = out.getByte(left);
            out.setByte(left++, out.getByte(right));
            out.setByte(right--, swap);
        }
    }
}
