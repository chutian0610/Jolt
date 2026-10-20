package io.github.chutian0610.jolt.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.JsonParseException;
import io.github.chutian0610.jolt.JoltParser;
import io.github.chutian0610.jolt.Token;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Differential suite (T8): Jolt vs Jackson on the whole corpus. Where the two implementations'
 * policies align, accept/reject verdicts must agree and the canonicalized parses must be equal
 * (objects keyed and sorted, numbers normalized through BigDecimal text). Where policies
 * deliberately differ, the divergence is RECORDED here — each entry names the class and who is
 * stricter — and those cases assert nothing beyond compiling both parses.
 *
 * <p>Recorded divergences:
 * <ul>
 *   <li>{@code i_string_*surrogate*}: escaped lone surrogates — Jackson accepts
 *       (lenient replacement policy), Jolt rejects at materialization (our recorded policy).</li>
 *   <li>{@code i_string_*}: invalid raw UTF-8 (overlong, encoded surrogates, out-of-range) —
 *       Jackson's byte parser is lenient, Jolt validates bytes strictly (our recorded policy).</li>
 *   <li>{@code i_structure_UTF-8_BOM_empty_object} and UTF-16/BOM files: Jackson sniffs and
 *       strips BOMs and detects UTF-16; Jolt is UTF-8-bytes-are-the-truth strict (ADR-0001).</li>
 *   <li>{@code n_single_space}, {@code n_structure_no_data}: Jackson's readTree returns a
 *       MissingNode for whitespace-only/empty input; RFC 8259 requires exactly one value —
 *       Jolt rejects, the corpus agrees with Jolt.</li>
 *   <li>{@code i_number_*} giant/overflow forms: value-level divergence from number-handling
 *       policies (Jackson underflows through double or uses BigInteger for big ints; Jolt keeps
 *       exact BigDecimal per ADR-0002) — these assert nothing beyond both parses succeeding.</li>
 * </ul>
 *
 * <p>Anti-rot: a NEW divergence (any case not on the list) fails loudly — Jackson-accept with
 * a Jolt reject escapes as an exception, Jackson-reject with a Jolt accept trips the verdict
 * assertion, and unlisted dual-accepts go through the value equality check.
 */
@Tag("json-test-suite")
class DifferentialTest {

    private static final ObjectMapper JACKSON = new ObjectMapper();

    /** Cases where a recorded policy divergence exists; verdict and value both skip. */
    private static final List<String> RECORDED_DIVERGENCE = List.of(
            // escaped lone surrogates: Jackson accepts, Jolt rejects
            "i_object_key_lone_2nd_surrogate.json",
            "i_string_1st_surrogate_but_2nd_missing.json",
            "i_string_1st_valid_surrogate_2nd_invalid.json",
            "i_string_incomplete_surrogate_and_escape_valid.json",
            "i_string_incomplete_surrogate_pair.json",
            "i_string_incomplete_surrogates_escape_valid.json",
            "i_string_invalid_lonely_surrogate.json",
            "i_string_invalid_surrogate.json",
            "i_string_inverted_surrogates_U+1D11E.json",
            "i_string_lone_second_surrogate.json",
            // raw-byte strictness: Jackson lenient, Jolt strict
            "i_string_UTF-16LE_with_BOM.json",
            "i_string_UTF8_surrogate_U+D800.json",
            "i_string_invalid_utf-8.json",
            "i_string_iso_latin_1.json",
            "i_string_lone_utf8_continuation_byte.json",
            "i_string_not_in_unicode_range.json",
            "i_string_overlong_sequence_2_bytes.json",
            "i_string_overlong_sequence_6_bytes.json",
            "i_string_overlong_sequence_6_bytes_null.json",
            "i_string_truncated-utf-8.json",
            "i_string_utf16BE_no_BOM.json",
            "i_string_utf16LE_no_BOM.json",
            "i_structure_UTF-8_BOM_empty_object.json",
            // empty/whitespace documents: Jackson MissingNode, Jolt RFC-rejects
            "n_single_space.json",
            "n_structure_no_data.json",
            // number precision: Jackson double-routed, Jolt exact BigDecimal
            "i_number_double_huge_neg_exp.json",
            "i_number_huge_exp.json",
            "i_number_neg_int_huge_exp.json",
            "i_number_pos_double_huge_exp.json",
            "i_number_real_neg_overflow.json",
            "i_number_real_pos_overflow.json",
            "i_number_real_underflow.json",
            "i_number_too_big_neg_int.json",
            "i_number_too_big_pos_int.json",
            "i_number_very_big_negative_int.json");

    @TestFactory
    Iterable<DynamicTest> jacksonDifferential() {
        List<DynamicTest> tests = new ArrayList<>();
        for (CorpusSupport.GateCase c : CorpusSupport.verdictCases().toList()) {
            tests.add(DynamicTest.dynamicTest(c.name(), () -> {
                JsonNode jackson;
                try {
                    jackson = JACKSON.readTree(c.bytes());
                } catch (IOException jacksonRejected) {
                    assertFalse(c.accept(), c.name() + ": Jolt accepts, Jackson rejects");
                    return;
                }

                // Recorded policy divergences: pin the recorded TOKEN-LEVEL direction so the
                // list cannot rot (a Jackson update flipping a verdict surfaces here). Value
                // readers are NOT exercised — escaped-surrogate materialization rejection and
                // BigDecimal range limits are reader-level policies, recorded elsewhere.
                if (RECORDED_DIVERGENCE.contains(c.name())) {
                    if (!c.accept()) {
                        // Recorded as reject (raw-byte strictness, BOM, empty docs):
                        // the rejection must persist.
                        assertThrows(JsonParseException.class,
                                () -> CorpusSupport.tokens(Jolt.parse(c.bytes())), c.name());
                    } else {
                        CorpusSupport.tokens(Jolt.parse(c.bytes())); // token-level accept must persist
                    }
                    return;
                }
                assertEquals(canonicalJackson(jackson), canonicalJolt(Jolt.parse(c.bytes())),
                        c.name() + ": canonical parses diverge");
            }));
        }
        return tests;
    }

    // --- canonicalization: language-neutral value trees ---

    private static Object canonicalJackson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> out = new TreeMap<>();
            node.fields().forEachRemaining(e -> out.put(e.getKey(), canonicalJackson(e.getValue())));
            return out;
        }
        if (node.isArray()) {
            List<Object> out = new ArrayList<>();
            node.forEach(n -> out.add(canonicalJackson(n)));
            return out;
        }
        if (node.isNumber()) {
            return node.decimalValue().stripTrailingZeros().toPlainString();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.textValue();
    }

    private static Object canonicalJolt(JoltParser parser) throws IOException {
        Token t = parser.nextToken();
        return canonicalValue(parser, t);
    }


    /** Canonicalizes the value whose START/scalar token has already been consumed. */
    private static Object canonicalValue(JoltParser parser, Token t) throws IOException {
        return switch (t) {
            case START_OBJECT -> {
                Map<String, Object> out = new TreeMap<>();
                Token next;
                while ((next = parser.nextToken()) != Token.END_OBJECT) {
                    assertEquals(Token.FIELD_NAME, next);
                    String key = parser.textValue();
                    Token value = parser.nextToken();
                    out.put(key, canonicalValue(parser, value));
                }
                yield out;
            }
            case START_ARRAY -> {
                List<Object> out = new ArrayList<>();
                Token next;
                while ((next = parser.nextToken()) != Token.END_ARRAY) {
                    out.add(canonicalValue(parser, next));
                }
                yield out;
            }
            case VALUE_STRING -> parser.textValue();
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> new BigDecimal(parser.numberText())
                    .stripTrailingZeros().toPlainString();
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_NULL -> null;
            default -> throw new AssertionError("unexpected token " + t);
        };
    }
}
