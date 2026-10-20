package io.github.chutian0610.jolt.bench;

import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.JoltConfig;
import io.github.chutian0610.jolt.JoltParser;
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
 * Strict-path parity benchmark (T9 acceptance): the default strict parse vs. a parse carrying
 * the lenient switches present but disabled — the two must be statistically indistinguishable,
 * demonstrating that disabled features cost zero (ADR-0001).
 */
@BenchmarkMode(Mode.Throughput)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class StrictParityBenchmark {

    private byte[] json;
    private JoltConfig strictConfig;

    @Setup
    public void setup() {
        json = ("{\"name\":\"jolt\",\"count\":42,\"ok\":true,"
                + "\"tags\":[\"a\",\"b\",\"c\"],\"ratio\":2.5}")
                .getBytes(StandardCharsets.UTF_8);
        strictConfig = JoltConfig.strict();
    }

    @Benchmark
    public int strictDefault() {
        return drain(Jolt.parse(json));
    }

    @Benchmark
    public int strictViaConfigSwitchesOff() {
        return drain(Jolt.parse(json, strictConfig));
    }

    private static int drain(JoltParser parser) {
        int tokens = 0;
        while (parser.nextToken() != Token.EOF) {
            tokens++;
        }
        return tokens;
    }
}
