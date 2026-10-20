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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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

    private static Properties manifestCache;

    @TestFactory
    Stream<DynamicTest> jsonTestSuite() {
        File dir = corpusDir();
        File[] corpus = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (corpus == null) {
            throw new IllegalStateException("Cannot list corpus directory " + dir);
        }
        Arrays.sort(corpus);
        return Arrays.stream(corpus)
                .filter(f -> f.getName().startsWith("y_") || f.getName().startsWith("n_")
                        || f.getName().startsWith("i_"))
                .flatMap(f -> Stream.of(
                        DynamicTest.dynamicTest("bytes/" + f.getName(),
                                () -> runCase(f.getName(), read(f), false)),
                        DynamicTest.dynamicTest("string/" + f.getName(),
                                () -> runCase(f.getName(), read(f), true))));
    }

    /** Runs one corpus case on one engine: UTF-8 bytes, or String via strict UTF-8 decoding. */
    private static void runCase(String caseName, byte[] raw, boolean asString) throws IOException {
        boolean expectAccept;
        switch (caseName.substring(0, 2)) {
            case "y_" -> expectAccept = true;
            case "n_" -> expectAccept = false;
            default -> {
                String recorded = manifest().getProperty(caseName);
                if (!"accept".equals(recorded) && !"reject".equals(recorded)) {
                    // The manifest is a regression contract: a mistyped value must fail loudly,
                    // never silently count as rejection.
                    throw new IllegalStateException(
                            "Unknown manifest value '" + recorded + "' for " + caseName);
                }
                expectAccept = "accept".equals(recorded);
            }
        }
        JoltParser parser;
        if (asString) {
            String text;
            try {
                text = decodeStrict(raw);
            } catch (CharacterCodingException undecodable) {
                // The bytes cannot even form a String; on the String path that is rejection.
                if (expectAccept) {
                    fail("expected accept but the file is not decodable as UTF-8: " + caseName);
                }
                return;
            }
            parser = Jolt.parse(text);
        } else {
            parser = Jolt.parse(raw);
        }
        if (expectAccept) {
            assertDoesNotThrow(() -> drain(parser), caseName);
        } else {
            assertThrows(JsonParseException.class, () -> drain(parser), caseName);
        }
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

    /**
     * Matrix cell {comments+trailing commas ON} × corpus: lenient mode is a strict superset, so
     * every y_ case must still parse under both switches on (n_ behavior may legitimately shift
     * and is not pinned here).
     */
    @Test
    void yCasesStillAcceptedUnderBothLenientSwitches() throws IOException {
        io.github.chutian0610.jolt.JoltConfig lenient = io.github.chutian0610.jolt.JoltConfig.strict()
                .withComments().withTrailingCommas();
        File dir = corpusDir();
        File[] corpus = dir.listFiles((d, name) -> name.startsWith("y_") && name.endsWith(".json"));
        if (corpus == null) {
            throw new IllegalStateException("Cannot list corpus directory " + dir);
        }
        for (File f : corpus) {
            byte[] raw = read(f);
            assertDoesNotThrow(() -> drain(Jolt.parse(raw, lenient)), "lenient/bytes: " + f.getName());
            assertDoesNotThrow(() -> drain(Jolt.parse(decodeStrict(raw), lenient)),
                    "lenient/string: " + f.getName());
        }
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

    private static String decodeStrict(byte[] raw) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw))
                .toString();
    }

    private static synchronized Properties manifest() throws IOException {
        if (manifestCache == null) {
            Properties props = new Properties();
            try (InputStream in = JsonTestSuiteTest.class
                    .getResourceAsStream("/jsontestsuite/i-manifest.properties")) {
                if (in == null) {
                    fail("i-manifest.properties missing from test resources");
                }
                props.load(in);
            }
            manifestCache = props;
        }
        return manifestCache;
    }

    private static byte[] read(File file) throws IOException {
        return Files.readAllBytes(file.toPath());
    }

    private static void drain(JoltParser parser) {
        while (parser.nextToken() != Token.EOF) {
            // consume the stream
        }
    }
}
