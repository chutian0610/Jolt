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

    /** The escaped-lone-surrogate i_ cases: token-level accept, tree-level reject (both
     * engines) — strings must materialize to enter a tree. Shared by the differential and
     * tree-parity gates so the lists cannot drift apart. */
    static final List<String> ESCAPED_SURROGATE_ACCEPT_CASES = List.of(
            "i_object_key_lone_2nd_surrogate.json",
            "i_string_1st_surrogate_but_2nd_missing.json",
            "i_string_1st_valid_surrogate_2nd_invalid.json",
            "i_string_incomplete_surrogate_and_escape_valid.json",
            "i_string_incomplete_surrogate_pair.json",
            "i_string_incomplete_surrogates_escape_valid.json",
            "i_string_invalid_lonely_surrogate.json",
            "i_string_invalid_surrogate.json",
            "i_string_inverted_surrogates_U+1D11E.json",
            "i_string_lone_second_surrogate.json");

    /**
     * Corpus cases where a recorded policy divergence exists between Jolt and Jackson (the
     * five classes documented in the differential gate): verdict and value comparisons skip
     * these; the tree-parity gate uses the same list.
     */
    static final List<String> RECORDED_DIVERGENCE = buildRecordedDivergence();

    private static List<String> buildRecordedDivergence() {
        List<String> combined = new ArrayList<>(ESCAPED_SURROGATE_ACCEPT_CASES);
        // raw-byte strictness: Jackson lenient, Jolt strict
        combined.add("i_string_UTF-16LE_with_BOM.json");
        combined.add("i_string_UTF8_surrogate_U+D800.json");
        combined.add("i_string_invalid_utf-8.json");
        combined.add("i_string_iso_latin_1.json");
        combined.add("i_string_lone_utf8_continuation_byte.json");
        combined.add("i_string_not_in_unicode_range.json");
        combined.add("i_string_overlong_sequence_2_bytes.json");
        combined.add("i_string_overlong_sequence_6_bytes.json");
        combined.add("i_string_overlong_sequence_6_bytes_null.json");
        combined.add("i_string_truncated-utf-8.json");
        combined.add("i_string_utf16BE_no_BOM.json");
        combined.add("i_string_utf16LE_no_BOM.json");
        combined.add("i_structure_UTF-8_BOM_empty_object.json");
        // empty/whitespace documents: Jackson MissingNode, Jolt RFC-rejects
        combined.add("n_single_space.json");
        combined.add("n_structure_no_data.json");
        // number precision: Jackson double-routed, Jolt exact BigDecimal
        combined.add("i_number_double_huge_neg_exp.json");
        combined.add("i_number_huge_exp.json");
        combined.add("i_number_neg_int_huge_exp.json");
        combined.add("i_number_pos_double_huge_exp.json");
        combined.add("i_number_real_neg_overflow.json");
        combined.add("i_number_real_pos_overflow.json");
        combined.add("i_number_real_underflow.json");
        combined.add("i_number_too_big_neg_int.json");
        combined.add("i_number_too_big_pos_int.json");
        combined.add("i_number_very_big_negative_int.json");
        return List.copyOf(combined);
    }

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
                    throw new IllegalStateException("Unknown manifest value '" + recorded
                            + "' for " + caseName
                            + " — case names must be y_/n_/i_ prefixed; i_ cases need a manifest entry");
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

    /** Strict UTF-8 decode (REPORT): malformed bytes raise, never silently replace. */
    static String decodeStrict(byte[] raw) throws java.nio.charset.CharacterCodingException {
        return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(raw))
                .toString();
    }
}
