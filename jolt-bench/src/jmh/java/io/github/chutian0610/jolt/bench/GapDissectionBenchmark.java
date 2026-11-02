package io.github.chutian0610.jolt.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.afterburner.AfterburnerModule;
import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.Token;
import io.github.chutian0610.jolt.Utf8Parser;
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
 * The M8-T0 gap dissection (#55): ONE run carrying the in-run A/B table — generated direct vs
 * Jackson afterburner on the POJO, vs databind on the whole-graph record, plus the standing
 * tree-path reflective judgment arm — AND the ladder that itemizes the gap between the
 * generated direct read and its own floor, per fixture:
 *
 * <ul>
 *   <li>{@code tokenDrain} — the scanner alone (lazy materialization: nothing built);</li>
 *   <li>{@code fieldNameChain} — the scanner plus the generated body's name-match chain on
 *       every FIELD_NAME ({@code fieldNameEquals} against the baked names in the binder's
 *       table order, first match wins), values skipped — isolates the matching cost;</li>
 *   <li>{@code handIdeal} — a hand-written direct read with the SAME chain: every value
 *       materialized through the public parser API and the target graph constructed by
 *       straight-line code (no door calls, no path carrier, no reflection) — the ceiling a
 *       generated binder can reach on today's parser API;</li>
 *   <li>{@code baselineConstruct} — the target graph built from literals (the footprint
 *       floor, the M6/M7 960 B/op arm on the graph);</li>
 *   <li>{@code generatedDirect} — the arm under dissection.</li>
 * </ul>
 *
 * <p>Per fixture, B/op(k+1) − B/op(k) itemizes one suspect's allocation and
 * 1/ops(k+1) − 1/ops(k) its time; the gap generatedDirect − handIdeal is what the generated
 * side alone can still recover, handIdeal − tokenDrain is what only the parser API can.
 * Fixtures are the M7 gate's shapes and documents (DirectBindBenchmark), redeclared so this
 * class owns its generated binders. Measurement/analysis only — no product code.
 *
 * <p><b>Measured conclusion (Apple Silicon / Zulu 21, 3 forks × 5×1s, -prof gc — 2026-11-02,
 * the M8-T0 dissection, #55; committed per the calibration clause):</b> the grilling's prime
 * suspect is OVERTURNED — the field-name match chain, not text materialization, dominates
 * both directions. Each FIELD_NAME token runs the binder's name table through
 * {@code fieldNameEquals(String)}, whose single-entry expected-name cache misses on every
 * new name and re-encodes it ({@code getBytes → byte[]}): POJO 14 re-encodings/op, graph 69.
 * Time ladder (differential of 1/ops, ns): POJO scan 112 → +chain 232 (47% of the 491 ns
 * read) → +materialize+construct 80 (16%) → +generated machinery 67 (14%); graph scan 383 →
 * +chain 1035 (53% of the 1958 ns read) → +mat+construct 377 (19%) → +machinery 164 (8%).
 * B/op: POJO generated 800 = chain 336 + materialization 288 + graph floor 176 (hand-ideal
 * equals generated EXACTLY — the generated machinery allocates zero); graph generated 3488 =
 * chain 1656 + materialization-over-floor 800 (≈430 unavoidable string data + ≈370 numeric
 * text) + machinery 72 (the reflective Item elements' args Object[] — the M7 elimination
 * never reached collection elements). JFR (single fork, qualitative): the chain arm is
 * 99.96% byte[]; the generated arms show encodeUTF8/copyOfRangeByte (the chain),
 * readRecordBody's args Object[] and ArrayList.grow, no Long/Double/Boolean boxing classes;
 * execution samples carry safepoint bias (ThreadLocal shares inflated — the ladder's
 * differentials are the quantitative basis; one 8.3 GB infra sample distorts class shares).
 * In-run A/B (same run): generated POJO 2.037M ± 0.203M vs afterburner 4.225M ± 0.095M =
 * 0.48× (afterburner allocates 45% MORE — 1160 vs 800 B/op — allocation is not the POJO
 * throughput lever); graph 0.511M ± 0.008M vs databind 0.989M ± 0.004M = 0.52×. Cross-run
 * disclosure: M7 measured afterburner 3.847M / generated 2.116M — both arms drifted
 * (−/+) since, in-run A/B is again the only comparator.
 *
 * <p>Reproduce: {@code ./gradlew :jolt-bench:jmhJar} then
 * {@code java -jar jolt-bench/build/libs/*-jmh.jar ".*GapDissection.*" -prof gc -wi 5 -i 5 -f 3}
 * (add {@code -prof jfr} on a single fork for the class-histogram evidence).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class GapDissectionBenchmark {

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

    /** The whole-graph record: nested record, list of records, map, enum, boxed null,
     * exact decimals — every shape the direct path covers. */
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
    private ObjectMapper pojoMapper;
    private ObjectMapper recordMapper;
    /** The ladder's parser, re-armed per call like the binders' per-thread parser (the M1
     * reuse discipline); the run is single-threaded, as the M7 gate was. */
    private Utf8Parser parser;

    @Setup(Level.Iteration)
    public void setup() {
        pojoBytes = POJO_JSON.getBytes(StandardCharsets.UTF_8);
        graphBytes = GRAPH_JSON.getBytes(StandardCharsets.UTF_8);
        direct = BindConfig.defaults().withDirect();
        tree = BindConfig.defaults();
        // registering the binders routes this state's withDirect calls to the GENERATED
        // direct read (the lazy registry hit); the tree-path facade never consults the registry
        GeneratedBinders.forType(PojoOrder.class);
        GeneratedBinders.forType(GraphOrder.class);
        pojoMapper = new ObjectMapper();
        pojoMapper.registerModule(new AfterburnerModule());
        recordMapper = new ObjectMapper();
        parser = Jolt.utf8Parser(pojoBytes);
    }

    // =========================================================================
    // A. the in-run A/B table
    // =========================================================================

    @Benchmark
    public PojoOrder pojoGeneratedDirect() {
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

    @Benchmark
    public GraphOrder graphGeneratedDirect() {
        return Bind.parse(graphBytes, GraphOrder.class, direct);
    }

    /** Afterburner is inert on records — plain databind is Jackson's record arm. */
    @Benchmark
    public GraphOrder graphJacksonDatabind() throws Exception {
        return recordMapper.readValue(graphBytes, GraphOrder.class);
    }

    @Benchmark
    public GraphOrder graphTreeReflective() {
        return Bind.parse(graphBytes, GraphOrder.class, tree);
    }

    // =========================================================================
    // B. the ladder — POJO fixture
    // =========================================================================

    @Benchmark
    public int pojoTokenDrain() {
        Utf8Parser p = parser.reset(pojoBytes);
        int tokens = 0;
        while (p.nextToken() != Token.EOF) {
            tokens++;
        }
        return tokens;
    }

    /** The generated POJO body's table is the setters sorted by name: id, price, quantity,
     * status, tags — the chain below is that order. */
    @Benchmark
    public int pojoFieldNameChain() {
        Utf8Parser p = parser.reset(pojoBytes);
        p.nextToken(); // START_OBJECT
        int matched = 0;
        while (p.nextToken() != Token.END_OBJECT) {
            if (p.fieldNameEquals("id")) {
                p.nextToken();
                matched++;
            } else if (p.fieldNameEquals("price")) {
                p.nextToken();
                matched++;
            } else if (p.fieldNameEquals("quantity")) {
                p.nextToken();
                matched++;
            } else if (p.fieldNameEquals("status")) {
                p.nextToken();
                matched++;
            } else if (p.fieldNameEquals("tags")) {
                p.nextToken(); // START_ARRAY
                while (p.nextToken() != Token.END_ARRAY) {
                    // scalar elements: already consumed
                }
                matched++;
            } else {
                skipValue(p);
            }
        }
        return matched;
    }

    @Benchmark
    public PojoOrder pojoHandIdeal() {
        Utf8Parser p = parser.reset(pojoBytes);
        p.nextToken(); // START_OBJECT
        PojoOrder out = new PojoOrder();
        while (p.nextToken() != Token.END_OBJECT) {
            if (p.fieldNameEquals("id")) {
                p.nextToken();
                out.setId(p.textValue());
            } else if (p.fieldNameEquals("price")) {
                p.nextToken();
                out.setPrice(p.bigDecimalValue());
            } else if (p.fieldNameEquals("quantity")) {
                p.nextToken();
                out.setQuantity((int) p.longValue());
            } else if (p.fieldNameEquals("status")) {
                p.nextToken();
                out.setStatus(p.textValue());
            } else if (p.fieldNameEquals("tags")) {
                p.nextToken(); // START_ARRAY
                List<String> tags = new ArrayList<>();
                while (p.nextToken() != Token.END_ARRAY) {
                    tags.add(p.textValue());
                }
                out.setTags(tags);
            } else {
                skipValue(p);
            }
        }
        return out;
    }

    /** The POJO target graph from literals (the footprint floor); the list grows like a
     * streaming read's would (length unknown up front). */
    @Benchmark
    public PojoOrder pojoBaselineConstruct() {
        PojoOrder out = new PojoOrder();
        out.setId("o-1");
        out.setQuantity(2);
        out.setPrice(new BigDecimal("1.50"));
        out.setStatus("ACTIVE");
        List<String> tags = new ArrayList<>();
        tags.add("a");
        tags.add("b");
        tags.add("c");
        out.setTags(tags);
        return out;
    }

    // =========================================================================
    // B. the ladder — whole-graph record fixture
    // =========================================================================

    @Benchmark
    public int graphTokenDrain() {
        Utf8Parser p = parser.reset(graphBytes);
        int tokens = 0;
        while (p.nextToken() != Token.EOF) {
            tokens++;
        }
        return tokens;
    }

    /** The record bodies' tables are component declaration order; nested objects run their
     * own chain, map keys are data (no chain — the reflective readMap materializes them). */
    @Benchmark
    public int graphFieldNameChain() {
        Utf8Parser p = parser.reset(graphBytes);
        p.nextToken(); // START_OBJECT
        int matched = 0;
        while (p.nextToken() != Token.END_OBJECT) {
            if (p.fieldNameEquals("id")) {
                p.nextToken();
                matched++;
            } else if (p.fieldNameEquals("customer")) {
                p.nextToken(); // START_OBJECT
                matched += chainCustomerBody(p);
            } else if (p.fieldNameEquals("items")) {
                p.nextToken(); // START_ARRAY
                while (p.nextToken() != Token.END_ARRAY) {
                    matched += chainItemBody(p);
                }
                matched++;
            } else if (p.fieldNameEquals("status")) {
                p.nextToken();
                matched++;
            } else if (p.fieldNameEquals("labels")) {
                p.nextToken(); // START_OBJECT
                while (p.nextToken() != Token.END_OBJECT) {
                    p.nextToken(); // the value
                }
                matched++;
            } else if (p.fieldNameEquals("note")) {
                p.nextToken();
                matched++;
            } else if (p.fieldNameEquals("total")) {
                p.nextToken();
                matched++;
            } else if (p.fieldNameEquals("sequence")) {
                p.nextToken();
                matched++;
            } else if (p.fieldNameEquals("ratio")) {
                p.nextToken();
                matched++;
            } else if (p.fieldNameEquals("gift")) {
                p.nextToken();
                matched++;
            } else {
                skipValue(p);
            }
        }
        return matched;
    }

    private static int chainCustomerBody(Utf8Parser p) {
        int matched = 0;
        while (p.nextToken() != Token.END_OBJECT) {
            if (p.fieldNameEquals("name")) {
                p.nextToken();
                matched++;
            } else if (p.fieldNameEquals("emails")) {
                p.nextToken(); // START_ARRAY
                while (p.nextToken() != Token.END_ARRAY) {
                    // scalar elements: already consumed
                }
                matched++;
            } else if (p.fieldNameEquals("tier")) {
                p.nextToken();
                matched++;
            } else {
                skipValue(p);
            }
        }
        return matched;
    }

    private static int chainItemBody(Utf8Parser p) {
        int matched = 0;
        while (p.nextToken() != Token.END_OBJECT) {
            if (p.fieldNameEquals("sku")) {
                p.nextToken();
                matched++;
            } else if (p.fieldNameEquals("quantity")) {
                p.nextToken();
                matched++;
            } else if (p.fieldNameEquals("price")) {
                p.nextToken();
                matched++;
            } else {
                skipValue(p);
            }
        }
        return matched;
    }

    @Benchmark
    public GraphOrder graphHandIdeal() {
        Utf8Parser p = parser.reset(graphBytes);
        p.nextToken(); // START_OBJECT
        String id = null;
        Customer customer = null;
        List<Item> items = null;
        Status status = null;
        Map<String, String> labels = null;
        Integer note = null;
        long total = 0L;
        BigInteger sequence = null;
        double ratio = 0d;
        boolean gift = false;
        while (p.nextToken() != Token.END_OBJECT) {
            if (p.fieldNameEquals("id")) {
                p.nextToken();
                id = p.textValue();
            } else if (p.fieldNameEquals("customer")) {
                p.nextToken(); // START_OBJECT
                customer = handCustomerBody(p);
            } else if (p.fieldNameEquals("items")) {
                p.nextToken(); // START_ARRAY
                items = new ArrayList<>();
                while (p.nextToken() != Token.END_ARRAY) {
                    items.add(handItemBody(p));
                }
            } else if (p.fieldNameEquals("status")) {
                p.nextToken();
                // no zero-allocation value comparison exists on the parser API: the enum
                // name materializes, exactly as both direct grades do today
                status = Status.valueOf(p.textValue());
            } else if (p.fieldNameEquals("labels")) {
                p.nextToken(); // START_OBJECT
                labels = new LinkedHashMap<>();
                while (p.nextToken() != Token.END_OBJECT) {
                    String key = p.textValue(); // map keys are data
                    p.nextToken();
                    labels.put(key, p.textValue());
                }
            } else if (p.fieldNameEquals("note")) {
                Token v = p.nextToken();
                note = v == Token.VALUE_NULL ? null : Integer.valueOf((int) p.longValue());
            } else if (p.fieldNameEquals("total")) {
                p.nextToken();
                total = p.longValue();
            } else if (p.fieldNameEquals("sequence")) {
                p.nextToken();
                // no bytes→BigInteger read exists on the parser API: through the exact decimal,
                // as both direct grades do today
                sequence = p.bigDecimalValue().toBigIntegerExact();
            } else if (p.fieldNameEquals("ratio")) {
                p.nextToken();
                ratio = p.doubleValue();
            } else if (p.fieldNameEquals("gift")) {
                gift = p.nextToken() == Token.VALUE_TRUE;
            } else {
                skipValue(p);
            }
        }
        return new GraphOrder(id, customer, items, status, labels, note, total, sequence,
                ratio, gift);
    }

    private static Customer handCustomerBody(Utf8Parser p) {
        String name = null;
        List<String> emails = null;
        Integer tier = null;
        while (p.nextToken() != Token.END_OBJECT) {
            if (p.fieldNameEquals("name")) {
                p.nextToken();
                name = p.textValue();
            } else if (p.fieldNameEquals("emails")) {
                p.nextToken(); // START_ARRAY
                emails = new ArrayList<>();
                while (p.nextToken() != Token.END_ARRAY) {
                    emails.add(p.textValue());
                }
            } else if (p.fieldNameEquals("tier")) {
                Token v = p.nextToken();
                tier = v == Token.VALUE_NULL ? null : Integer.valueOf((int) p.longValue());
            } else {
                skipValue(p);
            }
        }
        return new Customer(name, emails, tier);
    }

    private static Item handItemBody(Utf8Parser p) {
        String sku = null;
        int quantity = 0;
        BigDecimal price = null;
        while (p.nextToken() != Token.END_OBJECT) {
            if (p.fieldNameEquals("sku")) {
                p.nextToken();
                sku = p.textValue();
            } else if (p.fieldNameEquals("quantity")) {
                p.nextToken();
                quantity = (int) p.longValue();
            } else if (p.fieldNameEquals("price")) {
                p.nextToken();
                price = p.bigDecimalValue();
            } else {
                skipValue(p);
            }
        }
        return new Item(sku, quantity, price);
    }

    /** The graph from literals — the same graph as the DirectBindAllocBenchmark 960 B/op floor. */
    @Benchmark
    public GraphOrder graphBaselineConstruct() {
        List<Item> items = new ArrayList<>(2);
        items.add(new Item("s1", 2, new BigDecimal("1.50")));
        items.add(new Item("s2", 1, new BigDecimal("92233720368547758079")));
        List<String> emails = new ArrayList<>(2);
        emails.add("a@x");
        emails.add("b@x");
        Map<String, String> labels = new LinkedHashMap<>(1);
        labels.put("src", "mq");
        return new GraphOrder("o-1",
                new Customer("ann", emails, Integer.valueOf(2)),
                items,
                Status.ACTIVE,
                labels,
                null,
                9007199254740993L,
                new BigInteger("123456789012345678901234567890"),
                2.5d,
                false);
    }

    /** An unknown field's value: consumed, containers skipped whole (lazy materialization). */
    private static void skipValue(Utf8Parser p) {
        Token v = p.nextToken();
        if (v == Token.START_OBJECT || v == Token.START_ARRAY) {
            p.skipChildren();
        }
    }
}
