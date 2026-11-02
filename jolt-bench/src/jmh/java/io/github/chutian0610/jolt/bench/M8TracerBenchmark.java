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
 * 2026-11-02; committed per the calibration clause):</b> the baked chain removes EXACTLY the
 * dissection's #1 cost — POJO 800 → <b>464 B/op</b> (−336), graph 3488 → <b>1832 B/op</b>
 * (−1656), the ledger's chain line to the byte. In-run: POJO M8-on <b>3.376M ± 0.152M</b>
 * (296 ns — the dissection's ladder predicted 294) vs M8-off 2.158M ± 0.098M = <b>1.56×</b>;
 * vs afterburner 3.835M ± 0.156M = <b>0.88×</b> (T1 alone; the pinned 0.9× bar is T3's call
 * after T2's levers — the ladder projects ~240 ns ≈ parity). Graph M8-on
 * <b>0.763M ± 0.034M</b> vs M8-off 0.552M ± 0.029M = <b>1.38×</b> (T1 alone; the pinned
 * ≥ 1.5× bar awaits T2's collection inlining — projected ~1.6×); vs databind 0.922M ±
 * 0.040M = 0.83×. The facade arms equal the binder arms (the registry dispatch is free:
 * 3.324M / 0.756M). <b>Disclosure — the comparator moved too:</b> M8-off reads 3248 B/op,
 * not v0.7.1's 3488, because its {@code List<Item>} door enters the reflective Item body,
 * whose chain bakes FieldName keys as well (both grades, one rule) — the self-referential
 * ratio is conservative by construction, crediting only the generated BODY's change; T2's
 * inlining takes the M8 form off that door while the reference keeps it. Cross-run
 * (disclosure only): the same afterburner arm read 4.225M in the T0 run hours earlier —
 * ±10% intra-day drift, in-run ratios only.
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
