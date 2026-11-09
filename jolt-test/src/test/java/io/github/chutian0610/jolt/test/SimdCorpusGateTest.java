package io.github.chutian0610.jolt.test;

import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.JsonParseException;
import io.github.chutian0610.jolt.JoltParser;
import io.github.chutian0610.jolt.SimdUtf8Parser;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import java.util.Arrays;
import java.util.stream.Stream;

import static io.github.chutian0610.jolt.test.CorpusSupport.GateCase;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The JSONTestSuite corpus replayed on the third engine: every verdict case (y_/n_/i_) runs
 * through {@link SimdUtf8Parser} as raw UTF-8 bytes — accept parses to EOF, reject raises —
 * byte-for-byte the scalar engine's contract. The vectorized clean run must never change a
 * verdict, a token boundary, or an error offset.
 *
 * <p>Runs under the {@code json-test-suite} tag, part of {@code check} via the
 * {@code jsonTestSuiteTest} task.
 */
@Tag("json-test-suite")
class SimdCorpusGateTest {

    @TestFactory
    Stream<DynamicTest> jsonTestSuiteThroughSimdEngine() {
        return CorpusSupport.verdictCases()
                .map(c -> DynamicTest.dynamicTest("simd/" + c.name(), () -> runCase(c)));
    }

    /**
     * Token-stream and diagnostic parity with the scalar engine on every corpus case: the same
     * tokens to EOF on accept; the same rejection message (detail + offset + position) on reject.
     */
    @TestFactory
    Stream<DynamicTest> simdMatchesScalarOnEveryCorpusCase() {
        return CorpusSupport.verdictCases()
                .map(c -> DynamicTest.dynamicTest("parity/" + c.name(), () -> assertParity(c)));
    }

    private static void runCase(GateCase c) {
        SimdUtf8Parser parser = new SimdUtf8Parser(c.bytes());
        if (c.accept()) {
            assertDoesNotThrow(() -> CorpusSupport.drain(parser), c.name());
        } else {
            assertThrows(JsonParseException.class, () -> CorpusSupport.drain(parser), c.name());
        }
    }

    private static void assertParity(GateCase c) {
        Object scalar = outcome(Jolt.utf8Parser(c.bytes()));
        Object simd = outcome(new SimdUtf8Parser(c.bytes()));
        assertEquals(scalar, simd, c.name());
    }

    /** The token list to EOF, or the boundary exception's full message on rejection. */
    private static Object outcome(JoltParser parser) {
        try {
            return Arrays.asList(CorpusSupport.tokens(parser));
        } catch (JsonParseException e) {
            return "rejected: " + e.getMessage();
        }
    }
}
