package io.github.chutian0610.jolt.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.afterburner.AfterburnerModule;
import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.Utf8Parser;
import io.github.chutian0610.jolt.bind.Bind;
import io.github.chutian0610.jolt.bind.BindConfig;
import io.github.chutian0610.jolt.bind.TypedBinder;
import io.github.chutian0610.jolt.gen.GeneratedBinders;
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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The M8 tracer (T1, #56; grows into the T3 gate): the M8 form vs the M8-OFF reference arm,
 * IN-RUN, on the M7 gate's two fixtures — plus the competitor and the standing tree-path
 * judgment arm as disclosure. Both Jolt arms are generated classes of the SAME build: the M8
 * form ({@code GeneratedBinders.forType}, the facade's binder — here called through the
 * binder so the two arms are symmetric) and the M7-form reference
 * ({@code GeneratedBinders.referenceForType}, the {@code Gen$Ref$} namespace: String-name
 * chain through the frozen {@code fieldNameEquals(String)} overload + per-value door calls).
 * The in-run ratio M8-on / M8-off is the self-referential bar's instrument (spec #54's PIN:
 * ≥ 1.5× on the whole-graph record); the afterburner ratio is the POJO bar's (≥ 0.9×).
 *
 * <p>T1 carries lever #1 only (the baked {@code FieldName} chain); T2 adds the collection-loop
 * inlining and the numeric text path on top. The reference arm never changes.
 *
 * <p><b>Measured at T1 — lever #1 only (Apple Silicon / Zulu 21, 3 forks × 5×1s, -prof gc,
 * 2026-11-02):</b> the baked
 * chain removed EXACTLY the dissection's #1 cost — POJO 800 → 464 B/op (−336), graph 3488 →
 * 1832 B/op (−1656), the ledger's chain line to the byte. POJO M8-on 3.376M ± 0.152M
 * (296 ns — the ladder predicted 294) = 1.56× M8-off / 0.88× afterburner; graph M8-on
 * 0.763M ± 0.034M = 1.38× M8-off / 0.83× databind. Facade ≈ binder (the registry dispatch
 * is free). Cross-run (disclosure only): the same afterburner arm read 4.225M in the T0 run
 * hours earlier — ±10% intra-day drift, in-run ratios only.
 *
 * <p><b>Measured at T2 — the full lever set (#57: collection-loop inlining, the per-parse
 * carrier, the parser-internal numeric text path; Apple Silicon / Zulu 21, 3 forks × 5×1s,
 * -prof gc, 2026-11-02):</b> BOTH PINNED BARS CLEAR, and the graph contest flips. POJO
 * M8-on <b>3.774M ± 0.052M</b> (265 ns) = <b>0.95× afterburner</b> (3.957M ± 0.032M; the
 * pinned ≥ 0.9× MET; the 1.0× aspiration stays unreached — bands do not overlap) and
 * <b>1.72×</b> M8-off (2.192M ± 0.043M); <b>392 B/op</b> (the numeric text path's −72 on
 * top of T1's 464). Graph M8-on <b>0.950M ± 0.032M</b> (1052 ns) = <b>1.68× the M8-off arm</b>
 * (0.565M ± 0.013M; the pinned ≥ 1.5× MET) and <b>1.02× Jackson databind</b> (0.933M ±
 * 0.015M) — the whole-graph deficit (1.92–1.95× at v0.7.1) is GONE: bands overlap, the
 * facade arm runs ahead (0.963M = 1.03×); <b>1352 B/op</b> (−480 from T1: the numeric text,
 * the enum name, the reflective element bodies and their args Object[] all gone). The user
 * path: facade 3.826M POJO / 0.963M graph. M8-off moved again (POJO 800 → 728, graph 3248 →
 * 2832 B/op) — the shared substrate (parser numeric path, reflective element chains and
 * bodies) improves under it; the self-referential ratio stays conservative by construction.
 * The pinned bars are #58's adjudication (two independent runs at final HEAD); this is the
 * T2 tracer's one run.
 *
 * <p>Reproduce: {@code ./gradlew :jolt-bench:jmhJar} then
 * {@code java -jar jolt-bench/build/libs/*-jmh.jar ".*M8Tracer.*" -prof gc -wi 5 -i 5 -f 3}.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class M8TracerBenchmark {

    /** Public static POJO fixture (the M4/M5/M7-calibrated judgment shape). */
    public static class PojoOrder {
        private String id;
        private int quantity;
        private BigDecimal price;
        private String status;
        private List<String> tags;

        public PojoOrder() {}

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public int getQuantity() { return quantity; }
        public void setQuantity(int quantity) { this.quantity = quantity; }
        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal price) { this.price = price; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
    }

    public enum Status { ACTIVE, ARCHIVED }

    public record Item(String sku, int quantity, BigDecimal price) {}

    public record Customer(String name, List<String> emails, Integer tier) {}

    public record GraphOrder(String id, Customer customer, List<Item> items, Status status,
                             Map<String, String> labels, Integer note, long total,
                             BigInteger sequence, double ratio, boolean gift) {}

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
    private BindConfig config;
    private BindConfig direct;
    private BindConfig tree;
    private TypedBinder<PojoOrder> pojoM8On;
    private TypedBinder<PojoOrder> pojoM8Off;
    private TypedBinder<GraphOrder> graphM8On;
    private TypedBinder<GraphOrder> graphM8Off;
    private ObjectMapper pojoMapper;
    private ObjectMapper recordMapper;
    /** The two generated arms share one re-armed parser (the facade's per-thread reuse,
     * mirrored symmetrically — the reference arm has no facade route); single-threaded run. */
    private Utf8Parser parser;

    @Setup(Level.Iteration)
    public void setup() {
        pojoBytes = POJO_JSON.getBytes(StandardCharsets.UTF_8);
        graphBytes = GRAPH_JSON.getBytes(StandardCharsets.UTF_8);
        config = BindConfig.defaults();
        direct = BindConfig.defaults().withDirect();
        tree = BindConfig.defaults();
        pojoM8On = GeneratedBinders.forType(PojoOrder.class);
        pojoM8Off = GeneratedBinders.referenceForType(PojoOrder.class);
        graphM8On = GeneratedBinders.forType(GraphOrder.class);
        graphM8Off = GeneratedBinders.referenceForType(GraphOrder.class);
        pojoMapper = new ObjectMapper();
        pojoMapper.registerModule(new AfterburnerModule());
        recordMapper = new ObjectMapper();
        parser = Jolt.utf8Parser(pojoBytes);
    }

    // --- POJO fixture (the afterburner contest) ---

    /** The M8 form: the baked FieldName chain (T1) [+ inlined loops, numeric path at T2]. */
    @Benchmark
    public PojoOrder pojoM8On() {
        return pojoM8On.parseTokens(parser.reset(pojoBytes), config);
    }

    /** The M8-OFF reference: the M7-form body in the same build (the comparator). */
    @Benchmark
    public PojoOrder pojoM8Off() {
        return pojoM8Off.parseTokens(parser.reset(pojoBytes), config);
    }

    /** The user path: the facade's withDirect dispatch onto the registered M8 binder. */
    @Benchmark
    public PojoOrder pojoM8OnFacade() {
        return Bind.parse(pojoBytes, PojoOrder.class, direct);
    }

    @Benchmark
    public PojoOrder pojoJacksonAfterburner() throws Exception {
        return pojoMapper.readValue(pojoBytes, PojoOrder.class);
    }

    /** The standing judgment arm: the user-facing tree-path reflective read. */
    @Benchmark
    public PojoOrder pojoTreeReflective() {
        return Bind.parse(pojoBytes, PojoOrder.class, tree);
    }

    // --- whole-graph record fixture (the self-referential bar) ---

    @Benchmark
    public GraphOrder graphM8On() {
        return graphM8On.parseTokens(parser.reset(graphBytes), config);
    }

    @Benchmark
    public GraphOrder graphM8Off() {
        return graphM8Off.parseTokens(parser.reset(graphBytes), config);
    }

    @Benchmark
    public GraphOrder graphM8OnFacade() {
        return Bind.parse(graphBytes, GraphOrder.class, direct);
    }

    /** Afterburner is inert on records — plain databind is Jackson's record arm (disclosure). */
    @Benchmark
    public GraphOrder graphJacksonDatabind() throws Exception {
        return recordMapper.readValue(graphBytes, GraphOrder.class);
    }

    @Benchmark
    public GraphOrder graphTreeReflective() {
        return Bind.parse(graphBytes, GraphOrder.class, tree);
    }
}
