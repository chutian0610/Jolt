package io.github.chutian0610.jolt.bench;

import com.dslplatform.json.DslJson;
import com.dslplatform.json.runtime.Settings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.module.afterburner.AfterburnerModule;
import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.Token;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.charset.StandardCharsets;

/**
 * The M1 performance bar (T8): small-message parse throughput, Jolt vs Jackson-afterburner
 * (databind readTree) vs dsl-json. Corpus: one ~90-byte representative small message, read from
 * a pre-built byte[] (no IO in the loop). The gate: Jolt >= Jackson afterburner.
 */
@BenchmarkMode(Mode.Throughput)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class CompetitorBenchmark {

    private byte[] json;
    private ObjectMapper jacksonAfterburner;
    private DslJson<Object> dslJson;

    @Setup
    public void setup() {
        json = ("{\"name\":\"jolt\",\"count\":42,\"ok\":true,"
                + "\"tags\":[\"a\",\"b\",\"c\"],\"ratio\":2.5}")
                .getBytes(StandardCharsets.UTF_8);
        jacksonAfterburner = JsonMapper.builder()
                .addModule(new AfterburnerModule())
                .build();
        dslJson = new DslJson<>(Settings.withRuntime().allowArrayFormat(true));
    }

    @Benchmark
    public int joltTokenDrain() {
        var parser = Jolt.parse(json);
        int tokens = 0;
        while (parser.nextToken() != Token.EOF) {
            tokens++;
        }
        return tokens;
    }

    @Benchmark
    public JsonNode jacksonAfterburnerReadTree() throws Exception {
        return jacksonAfterburner.readTree(json);
    }

    /**
     * dsl-json untyped binding (its documented Object-mode path via the runtime analyzer
     * settings) — the shape comparable to Jackson's readTree.
     */
    @Benchmark
    public Object dslJsonReadAny() throws Exception {
        return dslJson.deserialize(Object.class, json, json.length);
    }
}
