package io.github.chutian0610.jolt.test;

import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.JsonParseException;
import io.github.chutian0610.jolt.Utf8Parser;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static io.github.chutian0610.jolt.test.CorpusSupport.GateCase;
import static io.github.chutian0610.jolt.test.CorpusSupport.drain;
import static io.github.chutian0610.jolt.test.CorpusSupport.tokens;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Reuse and adapter matrices (T7): every corpus case re-parsed through ONE reused engine
 * instance must match a fresh instance exactly, and the InputStream adapter must reproduce the
 * byte[] path's accept/reject behavior.
 */
@Tag("json-test-suite")
class ReuseCorpusTest {

    @TestFactory
    Iterable<DynamicTest> reuseConsistency() {
        List<GateCase> cases = CorpusSupport.yCases().toList();
        Utf8Parser reused = Jolt.utf8Parser(cases.get(0).bytes());
        List<DynamicTest> tests = new ArrayList<>();
        for (GateCase c : cases) {
            tests.add(DynamicTest.dynamicTest("reuse/" + c.name(), () -> {
                assertArrayEquals(tokens(Jolt.parse(c.bytes())), tokens(reused.reset(c.bytes())),
                        c.name());
            }));
        }
        return tests;
    }

    @TestFactory
    Iterable<DynamicTest> inputStreamMatchesBytesPath() {
        List<DynamicTest> tests = new ArrayList<>();
        for (GateCase c : CorpusSupport.verdictCases().toList()) {
            tests.add(DynamicTest.dynamicTest("stream/" + c.name(), () -> {
                if (c.accept()) {
                    assertDoesNotThrow(
                            () -> drain(Jolt.parse(new ByteArrayInputStream(c.bytes()))), c.name());
                } else {
                    assertThrows(JsonParseException.class,
                            () -> drain(Jolt.parse(new ByteArrayInputStream(c.bytes()))), c.name());
                }
            }));
        }
        return tests;
    }
}
