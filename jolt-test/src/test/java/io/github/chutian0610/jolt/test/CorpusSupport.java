package io.github.chutian0610.jolt.test;

import io.github.chutian0610.jolt.JoltParser;
import io.github.chutian0610.jolt.Token;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * The corpus module: typed access to the pinned JSONTestSuite cases plus the shared token
 * helpers the gate classes run their verdict logic with. Gate classes hold verdicts only —
 * corpus listing, byte reading, and expected-verdict classification live here.
 */
final class CorpusSupport {

    private static Properties manifestCache;

    /** One corpus case: its name, its exact bytes, and the recorded expected verdict. */
    record GateCase(String name, byte[] bytes, boolean accept) {}

    private CorpusSupport() {}

    /** Every y_ case (guaranteed-accept documents: round-trip and lenient-superset gates). */
    static Stream<GateCase> yCases() {
        return cases("y_");
    }

    /** Every y_/n_/i_ case with its fail-loud expected verdict. */
    static Stream<GateCase> verdictCases() {
        return cases(null);
    }

    /** The i_ cases, with their manifest-recorded verdicts. */
    static Stream<GateCase> iCases() {
        return cases("i_");
    }

    private static Stream<GateCase> cases(String prefix) {
        File dir = corpusDir();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json")
                && (prefix == null || name.startsWith(prefix)));
        if (files == null || files.length == 0) {
            throw new IllegalStateException("No corpus cases (prefix " + prefix + ") in " + dir);
        }
        Arrays.sort(files);
        return Arrays.stream(files).map(f -> {
            try {
                return new GateCase(f.getName(), Files.readAllBytes(f.toPath()), expectAccept(f.getName()));
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + f, e);
            }
        });
    }

    private static File corpusDir() {
        File dir = new File(System.getProperty("jolt.jsontestsuite.dir", ""));
        if (!dir.isDirectory()) {
            throw new IllegalStateException(
                    "JSONTestSuite corpus not found at " + dir
                            + " — run via ./gradlew :jolt-test:jsonTestSuiteTest so the download task provides it");
        }
        return dir;
    }

    static synchronized Properties manifest() {
        if (manifestCache == null) {
            Properties props = new Properties();
            try (InputStream in = CorpusSupport.class
                    .getResourceAsStream("/jsontestsuite/i-manifest.properties")) {
                if (in == null) {
                    throw new IllegalStateException("i-manifest.properties missing from test resources");
                }
                props.load(in);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read i-manifest", e);
            }
            manifestCache = props;
        }
        return manifestCache;
    }

    /**
     * Whether a corpus case must be accepted: y_ always, n_ never, i_ exactly as the manifest
     * records. A mistyped or missing manifest value fails loudly — never silently rejects.
     */
    static boolean expectAccept(String caseName) {
        switch (caseName.substring(0, 2)) {
            case "y_":
                return true;
            case "n_":
                return false;
            default: {
                String recorded = manifest().getProperty(caseName);
                if (!"accept".equals(recorded) && !"reject".equals(recorded)) {
                    throw new IllegalStateException(
                            "Unknown manifest value '" + recorded + "' for " + caseName);
                }
                return "accept".equals(recorded);
            }
        }
    }

    /** Drains the token stream to EOF, failing on any parse error. */
    static void drain(JoltParser parser) {
        while (parser.nextToken() != Token.EOF) {
            // consume
        }
    }

    /** The full token stream of a document (to EOF). */
    static Token[] tokens(JoltParser parser) {
        List<Token> out = new ArrayList<>();
        Token t;
        while ((t = parser.nextToken()) != Token.EOF) {
            out.add(t);
        }
        return out.toArray(new Token[0]);
    }
}
