package io.github.chutian0610.jolt.test;

import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.JoltParser;
import io.github.chutian0610.jolt.Token;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The JSONTestSuite corpus (pinned commit, fetched by the {@code downloadJsonTestSuite} task):
 * y_ files must parse to EOF, n_ files must fail. i_ files get per-case recorded behavior in T2.
 *
 * <p>Runs under the {@code json-test-suite} tag: excluded from {@code check} while the lexer is
 * minimal (T1), executed in full by {@code ./gradlew :jolt-test:jsonTestSuiteTest}.
 */
@Tag("json-test-suite")
class JsonTestSuiteTest {

    @TestFactory
    Stream<DynamicTest> jsonTestSuite() throws IOException {
        File dir = new File(System.getProperty("jolt.jsontestsuite.dir", ""));
        if (!dir.isDirectory()) {
            // Wiring failure, not a pass: the suite must never silently run zero cases.
            throw new IllegalStateException(
                    "JSONTestSuite corpus not found at " + dir
                            + " — run via ./gradlew :jolt-test:jsonTestSuiteTest so the download task provides it");
        }
        File[] corpus = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (corpus == null) {
            throw new IllegalStateException("Cannot list corpus directory " + dir);
        }
        return Stream.of(corpus)
                .filter(f -> f.getName().startsWith("y_") || f.getName().startsWith("n_"))
                .map(f -> f.getName().startsWith("y_")
                        ? DynamicTest.dynamicTest(f.getName(), () -> assertParsesToEof(f))
                        : DynamicTest.dynamicTest(f.getName(), () -> assertRejected(f)));
    }

    private static void assertParsesToEof(File file) throws IOException {
        byte[] json = Files.readAllBytes(file.toPath());
        assertDoesNotThrow(() -> drain(Jolt.parse(json)), file.getName());
    }

    private static void assertRejected(File file) throws IOException {
        byte[] json = Files.readAllBytes(file.toPath());
        assertThrows(RuntimeException.class, () -> drain(Jolt.parse(json)), file.getName());
    }

    private static void drain(JoltParser parser) {
        while (parser.nextToken() != Token.EOF) {
            // consume the stream
        }
    }
}
