package io.github.chutian0610.jolt;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static io.github.chutian0610.jolt.JoltTestSupport.utf8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JoltLenientTest {

    private static final JoltConfig COMMENTS = JoltConfig.strict().withComments();

    private static void assertAccepted(String json, JoltConfig config) {
        assertDoesNotThrow(() -> drain(Jolt.parse(utf8(json), config)), "utf8: " + json);
        assertDoesNotThrow(() -> drain(Jolt.parse(json, config)), "utf16: " + json);
    }

    private static void assertRejected(String json, JoltConfig config) {
        assertThrows(JsonParseException.class, () -> drain(Jolt.parse(utf8(json), config)), "utf8: " + json);
        assertThrows(JsonParseException.class, () -> drain(Jolt.parse(json, config)), "utf16: " + json);
    }

    private static void drain(JoltParser parser) {
        while (parser.nextToken() != Token.EOF) {
            // consume
        }
    }

    @Test
    void commentsAreRejectedInDefaultStrictMode() {
        assertRejected("// c\n{\"a\":1}", JoltConfig.strict());
        assertRejected("{/*c*/}", JoltConfig.strict());
        assertRejected("{\"a\":1 // c\n}", JoltConfig.strict());
    }

    @Test
    void lineCommentsAreAcceptedWhenEnabled() {
        assertAccepted("// note\n{\"a\":1}", COMMENTS);
        assertAccepted("[\n 1, // first\n 2 // second\n]", COMMENTS);
        assertAccepted("{\"a\":1 // trailing\n}", COMMENTS);
        assertAccepted("//\r\n{\"a\":1}", COMMENTS);
    }

    @Test
    void blockCommentsAreAcceptedWhenEnabled() {
        assertAccepted("{/* multi\nline comment */}", COMMENTS);
        assertAccepted("{\"a\" /*k*/: /*v*/ 1 /*end*/}", COMMENTS);
        assertAccepted("[/*only comments here*/ 1]", COMMENTS);
        assertAccepted("{\"a\":/*direct*/1}", COMMENTS);
    }

    @Test
    void unterminatedBlockCommentIsRejected() {
        assertRejected("{/* never closed}", COMMENTS);
    }

    @Test
    void slashWithoutCommentFormIsRejected() {
        assertRejected("{\"a\": /x}", COMMENTS);
        assertRejected("[/]", COMMENTS);
    }

    @Test
    void blockCommentsDoNotNest() {
        // The first */ closes the comment; the trailing " */" is then a syntax error.
        assertRejected("/* /* */ */", COMMENTS);
    }

    @Test
    void slashesInsideStringsAreNeverComments() {
        assertAccepted("\"http://example.com/*not-a-comment*/\"", JoltConfig.strict());
        assertAccepted("\"//也不是注释\"", COMMENTS);
    }

    private static final JoltConfig TRAILING = JoltConfig.strict().withTrailingCommas();

    @Test
    void trailingCommasAreRejectedInDefaultStrictMode() {
        assertRejected("[1,]", JoltConfig.strict());
        assertRejected("{\"a\":1,}", JoltConfig.strict());
        assertRejected("[[1,]]", JoltConfig.strict());
    }

    @Test
    void trailingCommasAreAcceptedWhenEnabled() {
        assertAccepted("[1,]", TRAILING);
        assertAccepted("[1,2,]", TRAILING);
        assertAccepted("{\"a\":1,}", TRAILING);
        assertAccepted("{\"a\":1,\"b\":2,}", TRAILING);
        assertAccepted("[[1,],[2,],]", TRAILING);
        assertAccepted("{\"a\":[1,],}", TRAILING);
        assertAccepted("[1 , ]", TRAILING);
    }

    @Test
    void onlyOneTrailingCommaIsAllowed() {
        assertRejected("[1,,]", TRAILING);
        assertRejected("{\"a\":1,,}", TRAILING);
        assertRejected("[,]", TRAILING);
        assertRejected("{,}", TRAILING);
        assertRejected("{\"a\":1,\"b\":}", TRAILING);
    }

    @Test
    void featuresAreIndependentInTheFourWayMatrix() {
        // A document using BOTH lenient features passes only with BOTH switches on.
        String doc = "// leading\n[1, /* end */]";
        assertRejected(doc, JoltConfig.strict());
        assertRejected(doc, COMMENTS);
        assertRejected(doc, TRAILING);
        assertAccepted(doc, COMMENTS.withTrailingCommas());

        // Each feature alone works on a document that only needs it.
        assertAccepted("// c\n[1]", COMMENTS);
        assertAccepted("[1,]", TRAILING);
    }

    @Test
    void plainParseOverloadsStayStrict() {
        assertThrows(JsonParseException.class, () -> drain(Jolt.parse(utf8("[1,]"))));
        assertThrows(JsonParseException.class, () -> drain(Jolt.parse("[1,]")));
        assertThrows(JsonParseException.class, () -> drain(Jolt.parse(utf8("//c\n1"))));
        assertThrows(JsonParseException.class, () -> drain(Jolt.parse("//c\n1")));
    }

    @Test
    void configComposesWithDepthLimitInAnyOrder() {
        JoltConfig cfg = JoltConfig.strict().withTrailingCommas().withMaxDepth(2);
        JoltConfig sameOtherOrder = JoltConfig.strict().withMaxDepth(2).withTrailingCommas();
        assertAccepted("[[1],]", cfg);
        assertAccepted("[[1],]", sameOtherOrder);
        assertRejected("[[[1]]]", cfg);
    }
}
