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

### Direct bind (M6)

The direct read path: parser tokens feed the binder straight to the constructed target —
no tree materialization. Semantically EQUIVALENT to the tree path (same values, same error
matrix, same `$` paths — divergence between the two read paths is a gate failure), selected
explicitly; the tree path stays the default. Steady-state allocation is the target object
graph only.

```java
Order direct = Bind.parse(jsonBytes, Order.class,
        BindConfig.defaults().withDirect());   // tokens → constructor, no tree
assertEquals(treeParsed, direct);              // the two-arm gate: equal, always
```

**M6 performance and allocation (measured honestly, 3 forks × 5×1s / -prof gc):** the
reflective direct path ran **1.94× and 2.05×** the tree-path reflective parse on the
top-level POJO fixture across two independent runs (the pinned ≥2× bar straddled, not
claimed) and **1.26–1.34×** on the whole-graph record fixture. Allocation: **3624 B/op vs
the tree path's 7448** — zero tree/Node objects (JFR-verified); the remainder being the
target graph, text materialization, and reflective-construction bridging — the generated
direct binder's M7 elimination list.

**M7 trajectory (the generated direct read, two independent runs at final HEAD):** the
pinned ≥2× bar is now **MET CLEARLY**: generated direct **2.61× / 2.60×** the tree-path
reflective parse on the POJO judgment arm across two runs (2.116M / 2.125M vs 0.811M /
0.817M ops/s; the worst-band edge still clears 2×) and **1.48× / 1.54×** on the whole-graph
record. The honest headline is the FIFTH arm — the in-run A/B against the same M6
reflective direct read — which **disconfirms M6's attribution hypothesis**: the reflective
arm itself measures **2.099M / 2.156M** (parity, 1.01–1.02× the generated arm) and clears
2× too — the bridging elimination is real on the allocation side (−136 B/op, below) but
throughput-neutral; M6's 1.94× was cross-day machine drift, and the in-run A/B is the only
trustworthy comparator. The 2.6× belongs to the direct-read architecture itself.
Disclosure: Jackson afterburner's POJO ceiling stands at **1.82×** generated direct
(contested, not matched); Jackson databind wins the whole-graph record at **1.92–1.95×**;
the generated direct arm is **~2× the default facade path**. Allocation: reflective direct
**3624 B/op** (reproduced exactly), generated direct **3488 B/op** — the eliminated
**−136 B/op** matches the predicted args Object[10] + Long/Double constructor boxing, and
the JFR class distribution shows the bridging classes **absent** from the generated arm
(the Gen$ class live on its allocation stacks) against a 960 B/op target-graph baseline.
The three-grade correctness matrix (tree reflective / reflective direct / generated
direct: pairwise-equal values, pairwise-identical failures, byte-identical writes) is a
standing gate. Reproduction:

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew :jolt-bench:jmhJar
$JAVA_HOME/bin/java -jar jolt-bench/build/libs/*-jmh.jar ".*DirectBindBenchmark.*" -wi 5 -i 5 -f 3
$JAVA_HOME/bin/java -jar jolt-bench/build/libs/*-jmh.jar ".*DirectBindAlloc.*" -prof gc -wi 5 -i 5 -f 3
```

### Generated binders (jolt-gen, M4 + M5 + M7)

An optional module that runtime-generates a dedicated binder class per target type via
ByteBuddy. Non-public targets fall back to the reflective binder. Since M5 the generated
parse/write bodies are **straight-line per-component bytecode** — accessor/field-name
constants baked in, strict tag checks with direct Node-reader calls, cached child binders for
nested record/POJO components — no metadata arrays and no per-call property-name derivation
in the hot loop. Since M7 the SAME generated class carries THREE forms on one instance: the
tree `parse`, the **token-consuming direct read** (`parseTokens` — the field loop inline,
strict scalar reads through the shared error helpers, and DIRECT construction: NEW +
per-parameter stack passing + INVOKESPECIAL canonical, no args array, no constructor-path
boxing) and `write`. The declaration is the capability flag: a record/POJO whose component
types are all public and emittable gets the direct read; non-public component types keep
the whole type on the reflective arm. Records and POJOs both get the treatment; collection
components read through the shared Plan-driven value read (one door call per value).

```java
import io.github.chutian0610.jolt.gen.GeneratedBinders;
import io.github.chutian0610.jolt.bind.TypedBinder;

TypedBinder<Order> binder = GeneratedBinders.forType(Order.class);
Order order = binder.parse(node, "$", BindConfig.defaults());
binder.write(order, writer);
```

**Performance trajectory (the M4 → M5 story, measured honestly at each step):** the M4
generated binder executed its cached plan reflectively and measured **≈ 0.92× parse / 0.94×
write** — within noise of parity, honestly recorded rather than claimed. M5 delivered the
bytecode specialization and the bar (**parse ≥ 2× / write ≥ 1.5× vs the reflective binder**)
is now **MET**: specialized parse **3.28×** (6.15M ± 0.42M vs 1.88M ± 0.22M ops/s) and
specialized write **1.66×** (6.28M ± 0.29M vs 3.79M ± 0.13M ops/s) on the top-level POJO
arm, error bands disjoint. The plan-cached reference arm reproduces the M4 parity shape in
the same run (1.07× / 0.95×), attributing the delta to the straight-line bytecode itself.
One honest dilution disclosure: on a whole-graph nested record document the specialized
multiples dilute to parse 1.01× / write 1.06× — the top-level body is a minority of the
bind, and nested record/collection values route through the shared `Bind` dispatch by
design. Method: JMH 3 forks × 5×1s, Apple Silicon, Zulu 21. Reproduce with:

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew :jolt-bench:jmhJar
$JAVA_HOME/bin/java -jar jolt-bench/build/libs/*-jmh.jar ".*GenVsReflective.*" -wi 5 -i 5 -f 3
```

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
