# Jolt

A zero-dependency, performance-first JSON parser and writer for the Java 21+ JVM, engineered
for the small-message workload: RPC frames, log events, MQ payloads — messages under a few
kilobytes where latency and allocation are the whole cost.

**Status: milestone M1 complete** — the streaming core (`jolt-core`) passes every gate below.
The tree layer (M2) and data-binding layer (M3) are next.

## Usage

```java
// Parse: token-pull streaming with lazy materialization — values decode only when read.
JoltParser parser = Jolt.parse(jsonBytes);          // byte[] (UTF-8) — the zero-copy fast path
Token t;
while ((t = parser.nextToken()) != Token.EOF) {
    if (t == Token.FIELD_NAME && parser.textValue().equals("skip-me")) {
        parser.skipChildren();                      // skip decodes nothing
    }
}

// Reuse across messages: buffers retained, steady-state parsing allocates zero bytes.
Utf8Parser reused = Jolt.utf8Parser(jsonBytes);
reused.reset(nextMessageBytes);                     // config (switches, depth) stays fixed

// String input runs on the UTF-16 engine — no transcoding bridge.
JoltParser p = Jolt.parse("{\"k\": \"v\"}");

// InputStream works through an internal buffering adapter.
JoltParser fromStream = Jolt.parse(inputStream);

// Exception-free validation: shared success sentinel, diagnostics on failure.
ParseResult result = Jolt.tryParse(jsonBytes);
if (!result.isSuccess()) { log(result.getFailure().getMessage()); }

// Writer: compact strict JSON, auto separators, reused output buffer.
String out = Jolt.write().startObject()
        .fieldName("name").value("jolt")
        .fieldName("tags").startArray().value("a").value("b").endArray()
        .endObject().toString();
```

Numbers follow the promotion policy ([ADR-0002](docs/adr/0002-number-promotion.md)):
integers within long range read as `long`, floats up to 17 significant digits as `double`,
everything else promotes to an exact `BigDecimal` — and `numberText()` always returns the
untouched source. Parsing defaults to strict RFC 8259; comments and trailing commas are
individually opt-in via `JoltConfig` and cost nothing when off ([ADR-0001](docs/adr/0001-strict-rfc8259-by-default.md)).

## Tree layer (jolt-tree)

An in-memory document model on top of the core: one final mutable `Node` class discriminated
by `TypeTag`, fluent construction, strict accessors, and JSON value equality (objects
key-order-independent, numbers numerically compared). The `jolt-tree` module depends on
`jolt-core`; the core never depends on it.

```java
import io.github.chutian0610.jolt.tree.Node;
import io.github.chutian0610.jolt.tree.Tree;

Node doc = Tree.parse(jsonBytes);              // same engines, config, depth limit as the core
String name = doc.get("name").asText();        // strict: type mismatch throws
doc.get("tags").add("new");                    // mutable, fluent
byte[] out = doc.toJson().getBytes(...);       // serialize through the core writer

Node built = Node.object()
        .put("name", "jolt")
        .put("count", 42)
        .put("tags", Node.array().add("a").add((String) null))
        .put("ratio", 2.5);
```

**What the tree promises:** parsed-then-serialized documents are **byte-identical** whenever
the source was already in canonical compact form — NUMBER nodes retain and serialize their
original text verbatim (`1.50e2` stays `1.50e2`; `9007199254740993` never touches a double),
key order and escapes that re-encode identically survive byte for byte. The tree normalizes
only insignificant whitespace, escape FORMS (`\uXXXX`/`\/` → raw characters), and duplicate
object keys (map semantics, last value wins). Trees compare by value, so a tree loaded from
bytes equals the same document loaded from a String — both engines produce equal trees.

**What it does not promise:** the zero-allocation contract is a core-layer property; the tree
allocates by design and has no performance bars (its gates are correctness gates).

## Binding layer (jolt-bind)

Records and POJOs ↔ JSON, through the verified tree. Strict by design: the JSON type must
match the target — `"42"` does not bind to an int; every failure is a `JsonBindException`
carrying the JSON path. Unknown fields are ignored by default (a `withFailOnUnknown()` switch
exists). No annotations, no naming strategies: JSON names are component/property names
verbatim.

```java
import io.github.chutian0610.jolt.bind.Bind;
import io.github.chutian0610.jolt.bind.BindConfig;

record Order(String id, Customer customer, List<Item> items) {}

Order order = Bind.parse(jsonBytes, Order.class);   // strict scalars, nested types,
                                                   // List/Map/arrays, enums by name
String json = Bind.write(order);                   // byte-identical for canonical documents

Order strict = Bind.parse(jsonBytes, Order.class,
        BindConfig.defaults().withFailOnUnknown());  // unknown fields → exception
```

**What binding promises:** JSON → record → JSON is byte-identical for canonical documents
(the verbatim-number serialization is inherited); missing reference components read as null
while missing/null primitives throw; abstract targets are rejected (polymorphism is a future
feature). Binding has no performance bars (same note as the tree layer).

## Performance

The M1 bars, measured on the final M1 code (commit `31f06c2`-lineage, verified at gate review):

| Benchmark | Result |
|---|---|
| Small-message parse (token drain, reused instance) | **7.67M ± 0.11M ops/s** |
| Jackson 2.18 databind `readTree` (the M1 bar) | 3.49M ± 0.10M ops/s |
| dsl-json 2.0.2 untyped `deserialize` (stretch) | 5.06M ± 0.54M ops/s |
| Steady-state allocation, reused instance | **≈ 0 B/op** (`gc.alloc.rate.norm ≈ 10⁻⁴`) |

Method: JMH, 3 forks × 5 iterations × 1s (throughput), `-prof gc` for allocation; Apple Silicon
macOS, Zulu JDK 21. Corpus: one ~90-byte object, pre-built `byte[]`, no IO in the loop.

**Honest shape disclosure:** Jolt's arm drains the token stream with lazy materialization
(nothing is built); the Jackson and dsl-json arms materialize full trees — each arm is its
library's canonical small-message read path. This is "cheapest faithful read vs canonical
read", not tokenizer-vs-tokenizer. Note also that Jackson's Afterburner module accelerates
POJO binding and is inert on `readTree`; the bar compares against Jackson's canonical read
regardless. Reproduce with:

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew :jolt-bench:jmhJar
$JAVA_HOME/bin/java -jar jolt-bench/build/libs/*-jmh.jar "CompetitorBenchmark.*" -wi 5 -i 5 -f 3
$JAVA_HOME/bin/java -jar jolt-bench/build/libs/*-jmh.jar "ZeroAllocBenchmark.reusedInstanceTokenDrain" -prof gc -wi 5 -i 5 -f 2
```

## Correctness

Every commit runs a ~1,560-case verification gate in CI:

- **JSONTestSuite** (pinned corpus): 319 cases × both engines, y/n/i with per-case recorded
  behavior (`i-manifest.properties` is a deliberate regression contract).
- **Writer round-trip**: every y_ document parses → rewrites → re-parses to an identical token
  stream, and the rewrite is a byte-exact fixed point (both engines).
- **Reuse + adapter matrices**: one reset-reused instance replays the corpus identically; the
  InputStream adapter matches the byte[] path verdict-for-verdict.
- **Differential suite vs Jackson**: verdict agreement and canonical value-tree equality on the
  whole corpus; 36 policy divergences (surrogates, raw-byte strictness, BOM sniffing, empty
  documents, number precision) are individually recorded and direction-pinned.
- **Strict-parity**: lenient switches present but off are statistically indistinguishable from
  the default strict path.

## Building

```bash
./gradlew build                        # full check gate (needs gh authenticated for corpus fetch)
./gradlew :jolt-test:jsonTestSuiteTest # the corpus suites alone
./gradlew :jolt-bench:jmh              # benchmarks
```

Modules: `jolt-core` (streaming parser + writer, zero dependencies), `jolt-bench` (JMH),
`jolt-test` (corpus + differential gates).

## Design

Domain vocabulary lives in [CONTEXT.md](CONTEXT.md); the architectural decisions in
[docs/adr/](docs/adr/) — strict-by-default with zero-cost lenient switches (0001), number
promotion (0002), VarHandle-not-Unsafe / no JDK internals (0003), a zero-dependency layered
core (0004), the three-tier error model (0005), and the Java 21 / HotSpot-first platform
stance (0006).

## License

[Apache-2.0](LICENSE)
