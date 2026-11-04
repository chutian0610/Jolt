package io.github.chutian0610.jolt.bench;

import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.Token;
import io.github.chutian0610.jolt.Utf8Parser;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * The M9-T0 scanner dissection (#63): the scanner segment itemized. Two instrument families:
 *
 * <p>1. The M8 fixtures' full drains (the scanner segment AS the binder sees it — POJO and
 * whole-graph, the same documents as M8TracerBenchmark), one per thread-reused parser.
 *
 * <p>2. Homogeneous amplification documents — each makes ONE scanner sub-segment dominate,
 * so its standalone per-byte rate falls out of 1/ops × bytes; the fixture ledger is then
 * bounded by combining the rates with the fixtures' byte counts and token counts:
 *
 * <ul>
 *   <li>{@code structural} — compact {@code {"k":1,...}} objects: pure token dispatch
 *       (the state machine, per-token overhead, span setup);</li>
 *   <li>{@code longStrings} — 200-char ASCII string values: the string-span scan's
 *       per-byte rate (the loop {@code scanStringSpan} runs);</li>
 *   <li>{@code longNumbers} — 15-digit integer values: the number scan's per-byte rate;</li>
 *   <li>{@code whitespaceHeavy} — 8 spaces between tokens: the whitespace skip's per-byte
 *       rate (the compact fixtures have none — the honest disclosure is whether this
 *       segment matters for the small-message workload at all).</li>
 * </ul>
 *
 * <p>Plus one UTF-16 drain (the String engine's fixture drain) for the dual-engine ledger.
 * Every arm drains to EOF and returns the token count; the parser is re-armed per call like
 * the binders' per-thread parser (single-threaded run). Measurement/analysis only — no
 * product code.
 *
 * <p>Reproduce: {@code ./gradlew :jolt-bench:jmhJar} then
 * {@code java -jar jolt-bench/build/libs/*-jmh.jar ".*ScannerDissection.*" -prof gc -wi 5 -i 5 -f 3}
 * (add {@code -prof jfr} on a single fork for the hot-frame evidence).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class ScannerDissectionBenchmark {

    private static final String POJO_JSON = "{\"id\":\"o-1\",\"quantity\":2,\"price\":1.50,"
            + "\"status\":\"ACTIVE\",\"tags\":[\"a\",\"b\",\"c\"]}";
    private static final String GRAPH_JSON = "{\"id\":\"o-1\",\"customer\":"
            + "{\"name\":\"ann\",\"emails\":[\"a@x\",\"b@x\"],\"tier\":2},"
            + "\"items\":[{\"sku\":\"s1\",\"quantity\":2,\"price\":1.50},"
            + "{\"sku\":\"s2\",\"quantity\":1,\"price\":92233720368547758079}],"
            + "\"status\":\"ACTIVE\",\"labels\":{\"src\":\"mq\"},\"note\":null,"
            + "\"total\":9007199254740993,\"sequence\":123456789012345678901234567890,"
            + "\"ratio\":2.5,\"gift\":false}";

    private byte[] pojoBytes;
    private byte[] graphBytes;
    private byte[] structuralBytes;
    private byte[] longStringsBytes;
    private byte[] longNumbersBytes;
    private byte[] whitespaceBytes;
    private String pojoString;
    private Utf8Parser parser;

    @Setup(Level.Iteration)
    public void setup() {
        pojoBytes = POJO_JSON.getBytes(StandardCharsets.UTF_8);
        graphBytes = GRAPH_JSON.getBytes(StandardCharsets.UTF_8);
        pojoString = POJO_JSON;
        // structural: 40 compact fields — dispatch-dominated (every byte is punctuation or a
        // 1-char key/value; the state machine and per-token overhead dominate)
        StringBuilder s = new StringBuilder("{");
        for (int i = 0; i < 40; i++) {
            if (i > 0) {
                s.append(',');
            }
            s.append("\"k").append(i).append("\":").append(i);
        }
        s.append('}');
        structuralBytes = s.toString().getBytes(StandardCharsets.UTF_8);
        // longStrings: 8 fields with 200-char ASCII values — the string-span scan dominates
        StringBuilder ls = new StringBuilder("{");
        String value = "x".repeat(200);
        for (int i = 0; i < 8; i++) {
            if (i > 0) {
                ls.append(',');
            }
            ls.append("\"k").append(i).append("\":\"").append(value).append('"');
        }
        ls.append('}');
        longStringsBytes = ls.toString().getBytes(StandardCharsets.UTF_8);
        // longNumbers: 8 fields with 15-digit integers — the number scan dominates
        StringBuilder ln = new StringBuilder("{");
        for (int i = 0; i < 8; i++) {
            if (i > 0) {
                ln.append(',');
            }
            ln.append("\"k").append(i).append("\":123456789012345");
        }
        ln.append('}');
        longNumbersBytes = ln.toString().getBytes(StandardCharsets.UTF_8);
        // whitespaceHeavy: ONE object with 8 spaces before EVERY token (field name, colon,
        // value, comma, closer) — the whitespace skip dominates (the compact fixtures have
        // ZERO whitespace; a document is one value, so the padding lives inside it)
        StringBuilder ws = new StringBuilder("{");
        String pad = " ".repeat(8);
        for (int i = 0; i < 40; i++) {
            if (i > 0) {
                ws.append(',');
            }
            ws.append(pad).append("\"k").append(i).append('"').append(pad).append(':')
                    .append(pad).append('1');
        }
        ws.append(pad).append('}');
        whitespaceBytes = ws.toString().getBytes(StandardCharsets.UTF_8);
        parser = Jolt.utf8Parser(pojoBytes);
    }

    private static int drain(io.github.chutian0610.jolt.JoltParser p) {
        int tokens = 0;
        while (p.nextToken() != Token.EOF) {
            tokens++;
        }
        return tokens;
    }

    // --- the fixtures' full drains (the scanner segment the binder pays) ---

    @Benchmark
    public int pojoDrain() {
        parser.reset(pojoBytes);
        return drain(parser);
    }

    @Benchmark
    public int graphDrain() {
        parser.reset(graphBytes);
        return drain(parser);
    }

    /** The String engine's fixture drain (the dual-engine ledger line). */
    @Benchmark
    public int pojoDrainUtf16() {
        io.github.chutian0610.jolt.JoltParser p = Jolt.parse(pojoString, 512);
        return drain(p);
    }

    // --- the homogeneous amplification documents ---

    @Benchmark
    public int structuralDrain() {
        parser.reset(structuralBytes);
        return drain(parser);
    }

    @Benchmark
    public int longStringsDrain() {
        parser.reset(longStringsBytes);
        return drain(parser);
    }

    @Benchmark
    public int longNumbersDrain() {
        parser.reset(longNumbersBytes);
        return drain(parser);
    }

    @Benchmark
    public int whitespaceHeavyDrain() {
        parser.reset(whitespaceBytes);
        return drain(parser);
    }
}
