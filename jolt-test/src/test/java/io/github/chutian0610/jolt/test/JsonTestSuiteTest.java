package io.github.chutian0610.jolt.test;

import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.JsonParseException;
import io.github.chutian0610.jolt.JoltParser;
import io.github.chutian0610.jolt.Token;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The JSONTestSuite corpus (pinned commit, fetched by the {@code downloadJsonTestSuite} task):
 * y_ files must parse to EOF, n_ files must fail, and i_ files must behave exactly as recorded
 * in the i-manifest (src/test/resources/jsontestsuite/i-manifest.properties) — the manifest is
 * the regression contract for implementation-defined cases.
 *
 * <p>Runs under the {@code json-test-suite} tag, part of {@code check} via the
 * {@code jsonTestSuiteTest} task.
 */
@Tag("json-test-suite")
class JsonTestSuiteTest {

    @TestFactory
    Stream<DynamicTest> jsonTestSuite() throws IOException {
        File dir = corpusDir();
        File[] corpus = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (corpus == null) {
            throw new IllegalStateException("Cannot list corpus directory " + dir);
        }
        Arrays.sort(corpus);
        return Arrays.stream(corpus)
                .filter(f -> f.getName().startsWith("y_") || f.getName().startsWith("n_")
                        || f.getName().startsWith("i_"))
                .map(f -> switch (f.getName().substring(0, 2)) {
                    case "y_" -> DynamicTest.dynamicTest(f.getName(), () -> assertParsesToEof(f));
                    case "n_" -> DynamicTest.dynamicTest(f.getName(), () -> assertRejected(f));
                    default -> DynamicTest.dynamicTest(f.getName(), () -> assertManifestBehavior(f));
                });
    }

    /** The manifest and the corpus must describe exactly the same set of i_ cases. */
    @Test
    void manifestCoversExactlyTheICases() throws IOException {
        File dir = corpusDir();
        Set<String> actual = new HashSet<>();
        File[] corpus = dir.listFiles((d, name) -> name.startsWith("i_") && name.endsWith(".json"));
        if (corpus != null) {
            for (File f : corpus) {
                actual.add(f.getName());
            }
        }
        Set<String> recorded = new HashSet<>(manifest().stringPropertyNames());
        assertEquals(recorded, actual,
                "manifest and corpus disagree — update the manifest deliberately, not silently");
    }

    private static File corpusDir() {
        File dir = new File(System.getProperty("jolt.jsontestsuite.dir", ""));
        if (!dir.isDirectory()) {
            // Wiring failure, not a pass: the suite must never silently run zero cases.
            throw new IllegalStateException(
                    "JSONTestSuite corpus not found at " + dir
                            + " — run via ./gradlew :jolt-test:jsonTestSuiteTest so the download task provides it");
        }
        return dir;
    }

    private static Properties manifest() throws IOException {
        Properties props = new Properties();
        try (InputStream in = JsonTestSuiteTest.class
                .getResourceAsStream("/jsontestsuite/i-manifest.properties")) {
            if (in == null) {
                fail("i-manifest.properties missing from test resources");
            }
            props.load(in);
        }
        return props;
    }

    private static void assertManifestBehavior(File file) throws IOException {
        String expected;
        try {
            expected = manifest().getProperty(file.getName());
        } catch (IOException e) {
            throw new IllegalStateException("cannot read i-manifest", e);
        }
        if (expected == null) {
            fail("No manifest entry for " + file.getName() + " — record its behavior deliberately");
        }
        if (expected.equals("accept")) {
            assertDoesNotThrow(() -> drain(Jolt.parse(read(file))), file.getName());
        } else if (expected.equals("reject")) {
            assertThrows(JsonParseException.class, () -> drain(Jolt.parse(read(file))), file.getName());
        } else {
            fail("Unknown manifest value '" + expected + "' for " + file.getName());
        }
    }

    private static byte[] read(File file) throws IOException {
        return Files.readAllBytes(file.toPath());
    }

    private static void assertParsesToEof(File file) throws IOException {
        byte[] json = read(file);
        assertDoesNotThrow(() -> drain(Jolt.parse(json)), file.getName());
    }

    private static void assertRejected(File file) throws IOException {
        byte[] json = read(file);
        assertThrows(JsonParseException.class, () -> drain(Jolt.parse(json)), file.getName());
    }

    private static void drain(JoltParser parser) {
        while (parser.nextToken() != Token.EOF) {
            // consume the stream
        }
    }
}
