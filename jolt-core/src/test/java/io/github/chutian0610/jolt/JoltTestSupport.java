package io.github.chutian0610.jolt;

import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.Named;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Stream;

/** Shared helpers for jolt-core behavior tests. */
final class JoltTestSupport {

    private JoltTestSupport() {}

    /** The dual-engine parameter source: every value-semantics matrix replays on both adapters. */
    static Stream<Arguments> engines() {
        return Stream.of(
                Arguments.of(Named.of("utf8", (BiFunction<String, Integer, JoltParser>)
                        (s, depth) -> Jolt.parse(utf8(s), depth))),
                Arguments.of(Named.of("utf16", (BiFunction<String, Integer, JoltParser>)
                        (s, depth) -> Jolt.parse(s, depth))));
    }

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
