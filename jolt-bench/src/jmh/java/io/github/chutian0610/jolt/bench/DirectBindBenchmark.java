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
 * The M6 relative gate (T45): FOUR arms — direct / tree-path reflective (the JUDGMENT arm) /
 * Jackson afterburner databind (disclosure) / the M5 specialized tree binder (disclosure) —
 * on TWO fixtures (the M4/M5-calibrated top-level POJO and the whole-graph record), parse
 * direction only, same environment discipline as the M1/M4/M5 bars (3 forks × 5×1s). The
 * pinned bar: **direct parse ≥ 2× tree-path reflective parse** on the POJO judgment arm.
 *
 * <p><b>Measured conclusion (Apple Silicon / Zulu 21, 3 forks × 5×1s — committed per the
 * calibration clause):</b> the pinned bar (direct ≥ 2× tree-path reflective, POJO judgment
 * arm) is NOT met as written — direct parse runs at **1.94×** the reflective arm
 * (1.636M ± 0.045M vs 0.843M ± 0.009M ops/s; the error bands straddle 2×, 1.87–2.02).
 * Whole-graph record disclosure: **1.34×** (0.446M vs 0.334M ops/s) — the nested share
 * dilutes the top-level speedup, the same honest shape the M5 gate recorded.
 *
 * <p><b>Disclosure arms:</b> Jackson afterburner databind runs **3.815M** on the POJO —
 * 2.3× the direct arm (field-level bytecode writes remain the competitor shape's ceiling;
 * afterburner is inert on records, where plain databind runs 0.967M and the direct arm is
 * 2.2× FASTER than Jackson). The M5 specialized tree binder runs 1.205M (POJO) — the
 * direct arm is **1.36×** it; on the record graph, 1.24×. The user-facing default path
 * (facadeParseReflective, 1.086M) is outpaced by the direct arm **1.51×**.
 *
 * <p><b>Attribution and the M7 verdict (data-driven, per the spec's decision rule):</b> the
 * T44 allocation proof attributes the direct arm's remaining overhead to
 * reflective-construction bridging (args Object[] + primitive boxing, ~15% of 3624 B/op) —
 * exactly what a generated direct binder (token-consuming straight-line bytecode that
 * constructs directly) eliminates. With the bridging removed, the direct arm's ceiling
 * against Jackson afterburner's field-level writes becomes the M7 question; the 1.94× vs
 * 2× gap on the judgment arm is attributable to the same bridging. **M7 opens**: the
 * numbers quantify its target (erase the ~15% bridging and the ≥2× bar falls; contest
 * Jackson's ceiling with construction-level bytecode).
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
}
