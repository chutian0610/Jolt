package io.github.chutian0610.jolt.test;

import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.JsonParseException;
import io.github.chutian0610.jolt.Token;
import io.github.chutian0610.jolt.Utf8Parser;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
    Iterable<DynamicTest> reuseConsistency() throws IOException {
        File dir = corpusDir();
        File[] corpus = dir.listFiles((d, name) -> name.startsWith("y_") && name.endsWith(".json"));
        if (corpus == null || corpus.length == 0) {
            throw new IllegalStateException("No y_ cases in corpus directory " + dir);
        }
        Arrays.sort(corpus);
        Utf8Parser reused = Jolt.utf8Parser(firstCase(corpus));
        List<DynamicTest> tests = new ArrayList<>();
        for (File f : corpus) {
            byte[] raw = Files.readAllBytes(f.toPath());
            tests.add(DynamicTest.dynamicTest("reuse/" + f.getName(), () -> {
                Token[] viaReuse = tokens(reused.reset(raw));
                Token[] viaFresh = tokens(Jolt.parse(raw));
                assertArrayEquals(viaFresh, viaReuse, f.getName());
            }));
        }
        return tests;
    }

    @TestFactory
    Iterable<DynamicTest> inputStreamMatchesBytesPath() throws IOException {
        File dir = corpusDir();
        File[] corpus = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (corpus == null) {
            throw new IllegalStateException("Cannot list corpus directory " + dir);
        }
        Arrays.sort(corpus);
        List<DynamicTest> tests = new ArrayList<>();
        for (File f : corpus) {
            byte[] raw = Files.readAllBytes(f.toPath());
            boolean expectAccept;
            switch (f.getName().substring(0, 2)) {
                case "y_" -> expectAccept = true;
                case "n_" -> expectAccept = false;
                default -> expectAccept = "accept".equals(manifestAccept(f.getName()));
            }
            tests.add(DynamicTest.dynamicTest("stream/" + f.getName(), () -> {
                if (expectAccept) {
                    assertDoesNotThrow(() -> drain(Jolt.parse(new ByteArrayInputStream(raw))), f.getName());
                } else {
                    assertThrows(JsonParseException.class,
                            () -> drain(Jolt.parse(new ByteArrayInputStream(raw))), f.getName());
                }
            }));
        }
        return tests;
    }

    private static byte[] firstCase(File[] corpus) throws IOException {
        for (File f : corpus) {
            if (f.getName().startsWith("y_")) {
                return Files.readAllBytes(f.toPath());
            }
        }
        return "{}".getBytes();
    }

    private static String manifestAccept(String name) {
        try (java.io.InputStream in = ReuseCorpusTest.class
                .getResourceAsStream("/jsontestsuite/i-manifest.properties")) {
            java.util.Properties props = new java.util.Properties();
            props.load(in);
            return props.getProperty(name);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Token[] tokens(io.github.chutian0610.jolt.JoltParser parser) {
        List<Token> out = new ArrayList<>();
        Token t;
        while ((t = parser.nextToken()) != Token.EOF) {
            out.add(t);
        }
        return out.toArray(new Token[0]);
    }

    private static void drain(io.github.chutian0610.jolt.JoltParser parser) {
        while (parser.nextToken() != Token.EOF) {
            // consume
        }
    }

    private static File corpusDir() {
        File dir = new File(System.getProperty("jolt.jsontestsuite.dir", ""));
        if (!dir.isDirectory()) {
            throw new IllegalStateException("JSONTestSuite corpus not found at " + dir);
        }
        return dir;
    }
}
