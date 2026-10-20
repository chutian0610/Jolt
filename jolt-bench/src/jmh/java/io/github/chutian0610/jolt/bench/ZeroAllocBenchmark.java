package io.github.chutian0610.jolt.bench;

import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.JoltParser;
import io.github.chutian0610.jolt.Token;
import io.github.chutian0610.jolt.Utf8Parser;
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
 * The zero-allocation proof (T7, M1 contract): steady-state token-stream parsing on the byte[]
 * fast path through a reused instance must allocate 0 B/op — verify with `-prof gc`
 * (gc.alloc.rate.norm). Fresh-instance allocation is shown for contrast.
 */
@BenchmarkMode(Mode.Throughput)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class ZeroAllocBenchmark {

    private byte[] json;
    private Utf8Parser reused;

    @Setup
    public void setup() {
        json = ("{\"name\":\"jolt\",\"count\":42,\"ok\":true,"
                + "\"tags\":[\"a\",\"b\",\"c\"],\"ratio\":2.5}")
                .getBytes(StandardCharsets.UTF_8);
        reused = Jolt.utf8Parser(json);
    }

    @Benchmark
    public int reusedInstanceTokenDrain() {
        reused.reset(json);
        int tokens = 0;
        while (reused.nextToken() != Token.EOF) {
            tokens++;
        }
        return tokens;
    }

    @Benchmark
    public int freshInstanceTokenDrain() {
        JoltParser parser = Jolt.parse(json);
        int tokens = 0;
        while (parser.nextToken() != Token.EOF) {
            tokens++;
        }
        return tokens;
    }
}
