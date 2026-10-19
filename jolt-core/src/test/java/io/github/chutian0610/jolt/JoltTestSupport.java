package io.github.chutian0610.jolt;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Shared helpers for jolt-core behavior tests. */
final class JoltTestSupport {

    private JoltTestSupport() {}

    static byte[] utf8(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** Drains the token stream to EOF, failing on any parse error. */
    static void drain(byte[] json) {
        JoltParser parser = Jolt.parse(json);
        while (parser.nextToken() != Token.EOF) {
            // consume
        }
    }

    /** Drains the token stream to EOF with an explicit depth limit. */
    static void drain(byte[] json, int maxDepth) {
        JoltParser parser = Jolt.parse(json, maxDepth);
        while (parser.nextToken() != Token.EOF) {
            // consume
        }
    }

    /** The full token stream of a document (to EOF). */
    static Token[] tokens(String json) {
        JoltParser parser = Jolt.parse(utf8(json));
        List<Token> collected = new ArrayList<>();
        Token t;
        while ((t = parser.nextToken()) != Token.EOF) {
            collected.add(t);
        }
        return collected.toArray(new Token[0]);
    }
}
