package io.github.chutian0610.jolt.bench;

import io.github.chutian0610.jolt.bind.Bind;
import io.github.chutian0610.jolt.bind.BindConfig;
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
 * The M6 allocation-contract proof (T44): the direct read path's steady-state allocation is
 * the TARGET OBJECT GRAPH ONLY (target instances, nested objects, collection containers and
 * elements, boxed scalars — the values themselves), with ≈ 0 B/op beyond it. The proof shape:
 * {@code directParse} (the direct arm) compared against {@code baselineConstruct} (a
 * hand-written constructor of the SAME object graph from the same literals — the target
 * graph's own allocation footprint) and {@code treeParse} (the default tree-path arm,
 * disclosed for the Node-graph delta). The contract holds when direct ≈ baseline within
 * measurement resolution.
 *
 * <p><b>Measured conclusion (Apple Silicon / Zulu 21, 3 forks × 5×1s, -prof gc — committed
 * per the calibration clause):</b> the contract as written (direct ≈ baseline, ≈0 beyond the
 * target graph) is NOT met: baseline 960 B/op, direct 3624 ± 0 B/op, tree 7448 B/op. The
 * recalibration is evidence-backed and attributed: JFR allocation samples show NO tree/Node
 * classes on the direct arm — the entire 3624 decomposes into (a) the target object graph
 * (Order/Customer/Item/BigDecimal/containers/boxing, ≈35%), (b) text materialization and its
 * JDK String/BigDecimal construction companions (byte[]/char[], ≈45% — the data itself plus
 * the unavoidable decode buffers), (c) reflective-construction bridging (the args
 * Object[] + primitive boxing around newInstance, ≈15%), (d) framework noise (≈5%). The
 * eliminated-and-accounted list: component plans are resolved at construction (Type[] from
 * getActualTypeArguments was allocating per parse — eliminated, JFR-verified), array
 * elements buffer in a pooled per-thread scratch (reused, zero steady-state), field-name
 * matching is zero-allocation (fieldNameEquals). The remaining bridging is the reflective
 * constructor's essence and is on the M7 (generated direct binder) elimination list, which
 * also carries the BigDecimal rawText span-construction candidate. The direct arm runs at
 * 48.7% of the tree arm's allocation — the Node toll is gone; the rest is data and
 * reflective bridging.
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

    /** The direct arm under proof. */
    @Benchmark
    public Order directParseOrder() {
        return Bind.parse(orderBytes, Order.class, direct);
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
