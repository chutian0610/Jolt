package io.github.chutian0610.jolt.bench;

import io.github.chutian0610.jolt.bind.Bind;
import io.github.chutian0610.jolt.bind.BindConfig;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The allocation proof (T44 for the M6 reflective read; T51 for the M7 generated read):
 * the direct read path's steady-state allocation is the TARGET OBJECT GRAPH ONLY, with the
 * reflective-construction bridging (the args Object[] + constructor-path boxing) as the
 * eliminated delta between the two direct grades. Arms: {@code reflectiveDirectParseOrder}
 * (the M6 measured arm — this class never registers binders, so the lazy registry miss
 * keeps withDirect on the reflective direct read), {@code generatedDirectParseOrder} (its
 * state registers the binder — the same facade call routes to the generated read),
 * {@code treeParseOrder} (the Node-toll disclosure) and {@code baselineConstructOrder}
 * (the target graph's own footprint).
 *
 * <p><b>Measured conclusion (Apple Silicon / Zulu 21, 3 forks × 5×1s, -prof gc — committed
 * per the calibration clause):</b> baseline 960 B/op · generated direct **3488 ± 0 B/op** ·
 * reflective direct **3624 ± 0 B/op** (M6-T4's number reproduced exactly — the lazy-registry
 * routing is verified) · tree 7448 B/op. The bridging elimination = **−136 B/op (−3.8%)**,
 * matching the predicted args Object[10] + Long/Double constructor-boxing cost — the
 * bridging classes are GONE from the generated arm. The JFR class distribution (single
 * fork, qualitative) shows the generated arm dominated by the target graph and text
 * (byte[]/char[] decode buffers, BigDecimal/BigInteger/String, LinkedHashMap$Entry,
 * Order/Item/Customer) with NO Long/Double/Boolean constructor-boxing classes in its
 * samples — while the reflective arm still shows them (Double sampled) — and the Gen$
 * class live on the generated arm's allocation stacks (runtime identity of the arm).
 * Object[] samples appear on BOTH arms from the target graph's ArrayList/HashMap
 * containers and sampler noise — the B/op delta is the quantitative bridging proof; the
 * throughput A/B (DirectBindBenchmark) shows the elimination is allocation-side,
 * throughput-neutral.
 *
 * <p>Reproduce: {@code ./gradlew :jolt-bench:jmhJar} then
 * {@code java -jar jolt-bench/build/libs/*-jmh.jar ".*DirectBindAlloc.*" -prof gc -wi 5 -i 5 -f 3}
 * (add {@code -prof jfr} for the class-distribution evidence).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class DirectBindAllocBenchmark {

    public enum Status { ACTIVE, ARCHIVED }

    public record Item(String sku, int quantity, BigDecimal price) {}

    public record Customer(String name, List<String> emails, Integer tier) {}

    /** The full-graph record: nested record, list of records, map, enum, boxed null,
     * exact decimals — every allocation shape the contract names. */
    public record Order(String id, Customer customer, List<Item> items, Status status,
                        Map<String, String> labels, Integer note, long total,
                        BigInteger sequence, double ratio, boolean gift) {}

    private static final String ORDER_JSON = "{\"id\":\"o-1\",\"customer\":"
            + "{\"name\":\"ann\",\"emails\":[\"a@x\",\"b@x\"],\"tier\":2},"
            + "\"items\":[{\"sku\":\"s1\",\"quantity\":2,\"price\":1.50},"
            + "{\"sku\":\"s2\",\"quantity\":1,\"price\":92233720368547758079}],"
            + "\"status\":\"ACTIVE\",\"labels\":{\"src\":\"mq\"},\"note\":null,"
            + "\"total\":9007199254740993,\"sequence\":123456789012345678901234567890,"
            + "\"ratio\":2.5,\"gift\":false}";

    private byte[] orderBytes;
    private BindConfig direct;
    private BindConfig tree;

    @Setup(Level.Iteration)
    public void setup() {
        orderBytes = ORDER_JSON.getBytes(StandardCharsets.UTF_8);
        direct = BindConfig.defaults().withDirect();
        tree = BindConfig.defaults();
    }

    /**
     * The REFLECTIVE direct arm (the M6 measured arm, kept under its measured name's
     * neighbor): this class's setup never registers binders, so the lazy registry miss
     * keeps withDirect on the M6 reflective direct binder — the A/B baseline whose 3624
     * B/op carried the ~15% reflective-construction bridging.
     */
    @Benchmark
    public Order reflectiveDirectParseOrder() {
        return Bind.parse(orderBytes, Order.class, direct);
    }

    /**
     * The GENERATED direct arm — the bridging elimination's proof side: construction is
     * INVOKESPECIAL with stack-passed parameters, no args Object[], no constructor-path
     * boxing. Its state registers the binder (the lazy registry hit), so the same facade
     * call routes to the generated read.
     */
    @State(Scope.Benchmark)
    public static class GeneratedState {
        byte[] orderBytes;
        BindConfig direct;

        @Setup(Level.Iteration)
        public void setup() {
            orderBytes = ORDER_JSON.getBytes(StandardCharsets.UTF_8);
            direct = BindConfig.defaults().withDirect();
            GeneratedBinders.forType(Order.class);
        }
    }

    @Benchmark
    public Order generatedDirectParseOrder(GeneratedState state) {
        return Bind.parse(state.orderBytes, Order.class, state.direct);
    }

    /** Disclosure: the default tree-path arm (the Node toll). */
    @Benchmark
    public Order treeParseOrder() {
        return Bind.parse(orderBytes, Order.class, tree);
    }

    /** The target object graph's own footprint: the same graph built by hand from literals. */
    @Benchmark
    public Order baselineConstructOrder() {
        List<Item> items = new ArrayList<>(2);
        items.add(new Item("s1", 2, new BigDecimal("1.50")));
        items.add(new Item("s2", 1, new BigDecimal("92233720368547758079")));
        List<String> emails = new ArrayList<>(2);
        emails.add("a@x");
        emails.add("b@x");
        Map<String, String> labels = new LinkedHashMap<>(1);
        labels.put("src", "mq");
        return new Order("o-1",
                new Customer("ann", emails, Integer.valueOf(2)),
                items,
                Status.ACTIVE,
                labels,
                null,
                9007199254740993L,
                new BigInteger("123456789012345678901234567890"),
                Double.valueOf(2.5d),
                Boolean.FALSE);
    }
}
