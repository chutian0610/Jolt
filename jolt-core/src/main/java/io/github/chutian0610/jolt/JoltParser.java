package io.github.chutian0610.jolt;

import java.nio.charset.StandardCharsets;

/**
 * Streaming token-pull parser over a UTF-8 byte[]. Tokens record spans of the input; values are
 * materialized lazily by the value readers (lazy materialization). Instances are mutable, reusable
 * for one document at a time, and not thread-safe.
 */
public final class JoltParser {

    private final byte[] json;
    private int pos;

    // Span of the most recent value-bearing token; decoding happens in the readers.
    private int valueStart;
    private int valueEnd;

    // True while an object expects its next field name (minimal tracer-bullet state).
    private boolean pendingFieldName;

    JoltParser(byte[] json) {
        this.json = json;
        this.pos = 0;
    }

    /** Advances to and returns the next token; {@link Token#EOF} once the document is exhausted. */
    public Token nextToken() {
        while (true) {
            skipWhitespace();
            if (pos >= json.length) {
                return Token.EOF;
            }
            byte b = json[pos];
            pos++;
            switch (b) {
                case '{' -> {
                    pendingFieldName = true;
                    return Token.START_OBJECT;
                }
                case '}' -> {
                    pendingFieldName = false;
                    return Token.END_OBJECT;
                }
                case ':' -> {
                    // Field name consumed; the value follows.
                    continue;
                }
                case ',' -> {
                    pendingFieldName = true;
                    continue;
                }
                case '"' -> {
                    scanStringSpan();
                    if (pendingFieldName) {
                        pendingFieldName = false;
                        return Token.FIELD_NAME;
                    }
                    throw new JsonParseException("Unexpected string token at offset " + (pos - 1));
                }
                default -> {
                    if (isNumberStart(b)) {
                        scanNumberSpan();
                        return Token.VALUE_NUMBER_INT;
                    }
                    throw new JsonParseException("Unexpected character at offset " + (pos - 1));
                }
            }
        }
    }

    /** The string content of the most recent {@link Token#FIELD_NAME}. */
    public String textValue() {
        return new String(json, valueStart, valueEnd - valueStart, StandardCharsets.UTF_8);
    }

    /** The integer value of the most recent {@link Token#VALUE_NUMBER_INT}. */
    public long longValue() {
        return Long.parseLong(textValue());
    }

    private void scanStringSpan() {
        valueStart = pos;
        while (pos < json.length && json[pos] != '"') {
            pos++;
        }
        if (pos >= json.length) {
            throw new JsonParseException("Unterminated string at offset " + valueStart);
        }
        valueEnd = pos;
        pos++; // closing quote
    }

    private void scanNumberSpan() {
        valueStart = pos - 1;
        while (pos < json.length && isNumberByte(json[pos])) {
            pos++;
        }
        valueEnd = pos;
    }

    private static boolean isNumberStart(byte b) {
        return (b >= '0' && b <= '9') || b == '-';
    }

    private static boolean isNumberByte(byte b) {
        return (b >= '0' && b <= '9') || b == '-' || b == '+' || b == '.' || b == 'e' || b == 'E';
    }

    private void skipWhitespace() {
        while (pos < json.length) {
            byte b = json[pos];
            if (b == ' ' || b == '\t' || b == '\n' || b == '\r') {
                pos++;
            } else {
                return;
            }
        }
    }
}
