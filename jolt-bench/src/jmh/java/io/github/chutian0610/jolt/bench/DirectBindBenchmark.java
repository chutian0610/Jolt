package io.github.chutian0610.jolt.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.afterburner.AfterburnerModule;
import io.github.chutian0610.jolt.bind.Bind;
import io.github.chutian0610.jolt.bind.BindConfig;
import io.github.chutian0610.jolt.bind.TypedBinder;
import io.github.chutian0610.jolt.gen.GeneratedBinders;
import io.github.chutian0610.jolt.tree.Tree;
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
 * The M7 relative gate (T51): FIVE arms — generated direct / tree-path reflective (the
 * JUDGMENT arm) / REFLECTIVE DIRECT (the bridging-elimination A/B: the same M6 direct read,
 * reflective construction) / Jackson afterburner databind (disclosure) / the M5 specialized
 * tree binder (disclosure) — on TWO fixtures (the M4/M5-calibrated top-level POJO and the
 * whole-graph record), parse direction only, 3 forks × 5×1s. The pinned bar: generated
 * direct ≥ 2× tree-path reflective on the POJO judgment arm (the bar the bridging held at
 * 1.94×–2.05× in M6). The two direct grades ride the same facade dispatch; the arms are
 * deterministic per fork via their @State classes — the generated arm's state registers the
 * binders (the lazy registry hit), the reflective arm's state deliberately does not (the
 * registry miss keeps the M6 reflective direct binder).
 *
 * <p><b>Measured conclusion (Apple Silicon / Zulu 21, 3 forks × 5×1s — committed per the
 * calibration clause): the bar is MET.</b> Generated direct **2.116M ± 0.085M** vs tree
 * reflective **0.811M ± 0.025M** on the POJO judgment arm = **2.61×** (the worst-band edge
 * still clears 2×: 2.43×). Whole-graph record: generated direct 0.474M ± 0.050M vs tree
 * 0.321M ± 0.009M = **1.48×** (the nested share dilutes the top-level speedup, the same
 * honest shape M5/M6 recorded).
 *
 * <p><b>The in-run A/B disconfirms M6's attribution hypothesis.</b> Reflective direct runs
 * **2.099M ± 0.080M** on the POJO — generated = **1.01× reflective** (bands overlap:
 * parity), and the reflective arm ITSELF clears the bar at 2.59×. The bridging elimination
 * (args Object[] + constructor boxing) is real on the allocation side (−136 B/op, see
 * DirectBindAllocBenchmark) but throughput-neutral on this fixture. The M6-T5 reading
 * (1.94×, the bar "held by the bridging") was cross-day machine drift: the SAME reflective
 * code path measures 2.59× in this run — in-run A/B is the only trustworthy comparator,
 * the calibration lesson proven by contradiction. JFR corroborates the arm identities:
 * the Gen$ class appears live on the generated arm's allocation stacks.
 *
 * <p><b>Disclosure arms:</b> Jackson afterburner **3.847M ± 0.191M** on the POJO = 1.82×
 * generated direct (field-level bytecode writes keep the competitor's POJO ceiling —
 * contested, not matched); afterburner is inert on records, where plain databind
 * **0.924M ± 0.056M** = 1.95× generated direct on the whole graph (Jackson's databind wins
 * the record graph — honest disclosure, unchanged since M6). The M5 specialized tree
 * binder: 1.206M POJO (generated direct 1.75× it) / 0.322M graph (1.47×). The user-facing
 * default path (facadeParseReflective, 1.035M ± 0.052M) — generated direct **2.04×** it.
 *
 * <p>Reproduce: {@code ./gradlew :jolt-bench:jmhJar} then
 * {@code java -jar jolt-bench/build/libs/*-jmh.jar ".*DirectBindBenchmark.*" -wi 5 -i 5 -f 3}.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class DirectBindBenchmark {

    /** Public static POJO fixture (the M4/M5-calibrated judgment shape). */
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

    /** The whole-graph record: nested record, list of records, map, enum, boxed null,
     * exact decimals — every shape the direct path covers. */
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
    private BindConfig direct;
    private BindConfig tree;
    private TypedBinder<PojoOrder> pojoSpecialized;
    private TypedBinder<GraphOrder> graphSpecialized;
    private ObjectMapper pojoMapper;
    private ObjectMapper recordMapper;

    @Setup(Level.Iteration)
    public void setup() throws Exception {
        pojoBytes = POJO_JSON.getBytes(StandardCharsets.UTF_8);
        graphBytes = GRAPH_JSON.getBytes(StandardCharsets.UTF_8);
        direct = BindConfig.defaults().withDirect();
        tree = BindConfig.defaults();
        // registering the binders routes this state's withDirect calls to the GENERATED
        // direct read (the lazy registry hit)
        pojoSpecialized = GeneratedBinders.forType(PojoOrder.class);
        graphSpecialized = GeneratedBinders.forType(GraphOrder.class);
        pojoMapper = new ObjectMapper();
        pojoMapper.registerModule(new AfterburnerModule());
        recordMapper = new ObjectMapper();
    }

    // --- POJO fixture (the judgment arm's shape) ---

    @Benchmark
    public PojoOrder pojoDirectParse() {
        return Bind.parse(pojoBytes, PojoOrder.class, direct);
    }

    @Benchmark
    public PojoOrder pojoReflectiveParse() {
        return Bind.parse(pojoBytes, PojoOrder.class, tree);
    }

    @Benchmark
    public PojoOrder pojoJacksonParse() throws Exception {
        return pojoMapper.readValue(pojoBytes, PojoOrder.class);
    }

    @Benchmark
    public PojoOrder pojoSpecializedParse() {
        return pojoSpecialized.parse(Tree.parse(pojoBytes), "$", BindConfig.defaults());
    }

    // --- whole-graph record fixture (disclosure) ---

    @Benchmark
    public GraphOrder graphDirectParse() {
        return Bind.parse(graphBytes, GraphOrder.class, direct);
    }

    @Benchmark
    public GraphOrder graphReflectiveParse() {
        return Bind.parse(graphBytes, GraphOrder.class, tree);
    }

    @Benchmark
    public GraphOrder graphJacksonParse() throws Exception {
        // Afterburner is inert on records (no setters to fuse) — the disclosure notes it
        return recordMapper.readValue(graphBytes, GraphOrder.class);
    }

    @Benchmark
    public GraphOrder graphSpecializedParse() {
        return graphSpecialized.parse(Tree.parse(graphBytes), "$", BindConfig.defaults());
    }

    /** Reference arm: the reflective facade path users actually take (POJO). */
    @Benchmark
    public PojoOrder facadeParseReflective() throws Exception {
        return Bind.parse(POJO_JSON, PojoOrder.class, BindConfig.defaults());
    }

    /**
     * The A/B state: NO binder registration — the lazy registry miss keeps this state's
     * withDirect calls on the M6 REFLECTIVE direct binder (per-fork determinism: each
     * benchmark method runs its own fork JVM, so this state's setup is the only setup).
     */
    @State(Scope.Benchmark)
    public static class ReflectiveDirectState {
        byte[] pojoBytes;
        byte[] graphBytes;
        BindConfig direct;

        @Setup(Level.Iteration)
        public void setup() {
            pojoBytes = POJO_JSON.getBytes(StandardCharsets.UTF_8);
            graphBytes = GRAPH_JSON.getBytes(StandardCharsets.UTF_8);
            direct = BindConfig.defaults().withDirect();
        }
    }

    @Benchmark
    public PojoOrder pojoReflectiveDirectParse(ReflectiveDirectState state) {
        return Bind.parse(state.pojoBytes, PojoOrder.class, state.direct);
    }

    @Benchmark
    public GraphOrder graphReflectiveDirectParse(ReflectiveDirectState state) {
        return Bind.parse(state.graphBytes, GraphOrder.class, state.direct);
    }
}
