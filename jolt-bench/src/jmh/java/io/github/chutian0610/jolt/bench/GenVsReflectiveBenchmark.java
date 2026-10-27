package io.github.chutian0610.jolt.bench;

import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.JoltWriter;
import io.github.chutian0610.jolt.bind.Bind;
import io.github.chutian0610.jolt.bind.BindConfig;
import io.github.chutian0610.jolt.bind.TypedBinder;
import io.github.chutian0610.jolt.gen.GeneratedBinders;
import io.github.chutian0610.jolt.tree.Node;
import io.github.chutian0610.jolt.tree.Tree;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The M4 relative bar (T32): generated vs reflective binder on the SAME fixture document,
 * parse (Node → POJO) and write (POJO → JSON) directions, same environment discipline as the
 * M1 bars. The bar: parse ≥ 2×, write ≥ 1.5× — with the spec's evidence-based recalibration
 * clause if the generated implementation (plan-cached execution over an inherited base, no
 * inlined bytecode specialization) does not reach the multiples on first measurement.
 */
@BenchmarkMode(Mode.Throughput)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class GenVsReflectiveBenchmark {

    /** Public static POJO fixture (ByteBuddy subclassing requires an accessible type). */
    public static class Order {
        private String id;
        private int quantity;
        private BigDecimal price;
        private String status;
        private List<String> tags;

        public Order() {}

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

    private Node node;
    private TypedBinder<Order> reflective;
    private TypedBinder<Order> generated;
    private Order order;
    private String orderJson;

    @Setup(Level.Iteration)
    public void setup() {
        orderJson = "{\"id\":\"o-1\",\"quantity\":2,\"price\":1.50,"
                + "\"status\":\"ACTIVE\",\"tags\":[\"a\",\"b\",\"c\"]}";
        node = Tree.parse(orderJson.getBytes(StandardCharsets.UTF_8));
        reflective = Bind.reflectiveBinder(Order.class);
        generated = GeneratedBinders.forType(Order.class);
        order = generated.parse(node, "$", BindConfig.defaults());
    }

    @Benchmark
    public Order reflectiveParse() {
        return reflective.parse(node, "$", BindConfig.defaults());
    }

    @Benchmark
    public Order generatedParse() {
        return generated.parse(node, "$", BindConfig.defaults());
    }

    @Benchmark
    public String reflectiveWrite() {
        JoltWriter writer = Jolt.write();
        reflective.write(order, writer);
        return writer.toString();
    }

    @Benchmark
    public String generatedWrite() {
        JoltWriter writer = Jolt.write();
        generated.write(order, writer);
        return writer.toString();
    }

    /** Reference arm: the reflective facade path users actually take. */
    @Benchmark
    public Order facadeParseReflective() {
        return Bind.parse(orderJson.getBytes(StandardCharsets.UTF_8), Order.class, BindConfig.defaults());
    }
}
