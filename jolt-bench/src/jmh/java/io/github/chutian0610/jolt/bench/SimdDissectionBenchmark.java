package io.github.chutian0610.jolt.bench;

import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.SimdUtf8Parser;
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
 * The M9-T2 in-run A/B (spec #62): the scalar floor vs the Vector-API engine on the SAME
 * fixtures, parsers re-armed per call (the zero-allocation reuse shape). The pinned ship rule
 * — SimdUtf8Parser's in-run tokenDrain ≥ 1.15× the scalar tokenDrain on the fixture corpus
 * (both compact drains), else the honest negative result and the module stays out of the tag.
 * The longStrings pair is the disclosure arm: the one fixture where the span shape dominates,
 * the segment the vector clean run can actually compress.
 *
 * <p>Measurement context inherited from T1 (#64): the machine drifts up to ±10% within hours,
 * so single-run cross-config comparisons are void; within ONE JMH invocation the arms' forks
 * sit minutes apart, which bounds the drift the in-run ratio carries. Adjudication runs the
 * whole invocation twice.
 *
 * <p>Every fork needs the incubator module (the containment rule: whoever loads the engine's
 * vector code adds the flag). Reproduce: {@code ./gradlew :jolt-bench:jmhJar} then
 * {@code java -jar jolt-bench/build/libs/*-jmh.jar ".*SimdDissection.*" -prof gc}.
 *
 * <p><b>Adjudicated (#65, two independent runs, 3×5×1s, -prof gc, Apple Silicon / Zulu 21):
 * NOT SHIPPED.</b> pojoDrain simd/scalar 1.014× / 1.012× (8.71M vs 8.59M / 8.61M), graphDrain
 * 1.048× / 0.988× (2.56M vs 2.44M; 2.55M vs 2.58M) — bands overlapping in both runs, far from
 * the pinned 1.15×. The disclosure arm: longStrings 4.68× / 4.46× (4.52M vs 0.96M). Zero
 * allocation on every arm (0.001–0.008 B/op). The compact corpus's scanner cost is per token,
 * and its spans are 2–6 bytes: the vector clean run matches the scalar floor there and wins
 * only where spans dominate.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"--add-modules=jdk.incubator.vector"})
public class SimdDissectionBenchmark {

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
    private byte[] longStringsBytes;
    private Utf8Parser scalar;
    private SimdUtf8Parser simd;

    @Setup(Level.Iteration)
    public void setup() {
        pojoBytes = POJO_JSON.getBytes(StandardCharsets.UTF_8);
        graphBytes = GRAPH_JSON.getBytes(StandardCharsets.UTF_8);
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
        scalar = Jolt.utf8Parser(pojoBytes);
        simd = new SimdUtf8Parser(pojoBytes);
    }

    private static int drain(io.github.chutian0610.jolt.JoltParser p) {
        int tokens = 0;
        while (p.nextToken() != Token.EOF) {
            tokens++;
        }
        return tokens;
    }

    // --- the pinned instrument: the fixture corpus's token drains, both engines ---

    @Benchmark
    public int pojoDrainScalar() {
        scalar.reset(pojoBytes);
        return drain(scalar);
    }

    @Benchmark
    public int pojoDrainSimd() {
        simd.reset(pojoBytes);
        return drain(simd);
    }

    @Benchmark
    public int graphDrainScalar() {
        scalar.reset(graphBytes);
        return drain(scalar);
    }

    @Benchmark
    public int graphDrainSimd() {
        simd.reset(graphBytes);
        return drain(simd);
    }

    // --- the disclosure arm: the span-dominated document ---

    @Benchmark
    public int longStringsDrainScalar() {
        scalar.reset(longStringsBytes);
        return drain(scalar);
    }

    @Benchmark
    public int longStringsDrainSimd() {
        simd.reset(longStringsBytes);
        return drain(simd);
    }
}
