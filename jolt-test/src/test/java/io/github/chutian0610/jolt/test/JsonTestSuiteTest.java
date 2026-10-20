package io.github.chutian0610.jolt.test;

import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.JsonParseException;
import io.github.chutian0610.jolt.JoltParser;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import static io.github.chutian0610.jolt.test.CorpusSupport.GateCase;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The JSONTestSuite corpus (pinned commit, fetched by the {@code downloadJsonTestSuite} task),
 * replayed on BOTH engines: every case runs as raw UTF-8 bytes and as a String (decoded
 * strictly — a file that cannot decode as UTF-8 is rejection on the String path).
 * y_ files must parse to EOF, n_ files must fail, and i_ files must behave exactly as
 * recorded in the i-manifest (src/test/resources/jsontestsuite/i-manifest.properties).
 *
 * <p>Runs under the {@code json-test-suite} tag, part of {@code check} via the
 * {@code jsonTestSuiteTest} task.
 */
@Tag("json-test-suite")
class JsonTestSuiteTest {

    @TestFactory
    Stream<DynamicTest> jsonTestSuite() {
        return CorpusSupport.verdictCases()
                .flatMap(c -> Stream.of(
                        DynamicTest.dynamicTest("bytes/" + c.name(),
                                () -> runCase(c, false)),
                        DynamicTest.dynamicTest("string/" + c.name(),
                                () -> runCase(c, true))));
    }

    /** Runs one corpus case on one engine: UTF-8 bytes, or String via strict UTF-8 decoding. */
    private static void runCase(GateCase c, boolean asString) throws CharacterCodingException {
        JoltParser parser;
        if (asString) {
            String text;
            try {
                text = decodeStrict(c.bytes());
            } catch (CharacterCodingException undecodable) {
                // The bytes cannot even form a String; on the String path that is rejection.
                if (c.accept()) {
                    fail("expected accept but the file is not decodable as UTF-8: " + c.name());
                }
                return;
            }
            parser = Jolt.parse(text);
        } else {
            parser = Jolt.parse(c.bytes());
        }
        if (c.accept()) {
            assertDoesNotThrow(() -> CorpusSupport.drain(parser), c.name());
        } else {
            assertThrows(JsonParseException.class, () -> CorpusSupport.drain(parser), c.name());
        }
    }

    /** The manifest and the corpus must describe exactly the same set of i_ cases. */
    @Test
    void manifestCoversExactlyTheICases() {
        Set<String> actual = new HashSet<>();
        CorpusSupport.iCases().forEach(c -> actual.add(c.name()));
        Set<String> recorded = new HashSet<>(CorpusSupport.manifest().stringPropertyNames());
        assertEquals(recorded, actual,
                "manifest and corpus disagree — update the manifest deliberately, not silently");
    }

    /**
     * Matrix cell {comments+trailing commas ON} × corpus: lenient mode is a strict superset, so
     * every y_ case must still parse under both switches on (n_ behavior may legitimately shift
     * and is not pinned here).
     */
    @Test
    void yCasesStillAcceptedUnderBothLenientSwitches() throws CharacterCodingException {
        io.github.chutian0610.jolt.JoltConfig lenient = io.github.chutian0610.jolt.JoltConfig.strict()
                .withComments().withTrailingCommas();
        for (GateCase c : CorpusSupport.yCases().toList()) {
            assertDoesNotThrow(() -> CorpusSupport.drain(Jolt.parse(c.bytes(), lenient)),
                    "lenient/bytes: " + c.name());
            assertDoesNotThrow(() -> CorpusSupport.drain(Jolt.parse(decodeStrict(c.bytes()), lenient)),
                    "lenient/string: " + c.name());
        }
    }

    private static String decodeStrict(byte[] raw) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw))
                .toString();
    }
}
