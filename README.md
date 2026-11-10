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
(contested, not matched; M8 contested it further — see below); Jackson databind won the
whole-graph record at **1.92–1.95×** at M7 (M8 flipped this — see below);
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

**M8 trajectory (the Jackson ceiling contest, gate-adjudicated at final HEAD across two
independent runs, in-run A/B):** M8 opened with a dissection (#55) that OVERTURNED the
grilling's prime suspect — the binder match chain, not text materialization, dominated both
directions (`fieldNameEquals(String)` keeps a single-entry expected-name cache; every chain
miss re-encoded the name: 47–53% of read time, 1656 of the graph's 3488 B/op; the door
machinery was the minor share, 8–14%). The one-time recalibration re-ranked the levers and
admitted ONE additive jolt-core API — the pre-encoded `FieldName` key
(`JoltParser.fieldNameEquals(FieldName)`, both engines; the String overload frozen as the
reference arm's substrate). Lever by lever, the generated direct read went POJO
491 → 296 → **265 ns** / 800 → 464 → **392 B/op**, whole-graph 1958 → 1311 → **1052 ns** /
3488 → 1832 → **1352 B/op**: baked-key chains for BOTH direct grades (T1), collection-loop
inlining at full width — element loops and element reads in the parent body, record/POJO
elements through the monomorphic child body, per-parse path carrier, `bigDecimalValue`
parsing the span in place (T2). **Both T0-pinned bars MET at the gate, every run**:
POJO generated direct **0.95× / 1.11× / 0.97×** Jackson afterburner (pinned ≥ 0.9×; the
1.0× parity aspiration disclosed unreached — bands do not overlap in the clean runs) and
whole-graph **1.66× / 1.68× / 1.61×** the in-run M8-off reference arm (pinned ≥ 1.5×; the
comparator is the M7-form body under the `Gen$Ref$` namespace in the same build, frozen
`fieldNameEquals(String)` + door calls — a conservative comparator, since the shared
substrate improves under it). **The whole-graph databind deficit is GONE**: 1.92–1.95×
behind at v0.7.1 → **1.01–1.03× (parity-to-ahead, bands overlap)**. Allocation: generated
direct **1352 B/op** vs the 960 B/op target-graph floor (reproduced exactly), reflective
direct **1552** (from 3624 — the shared substrate's effect on the other grade), tree
**7456** (+8: the parser instance grew by the numeric-scratch field past an alignment
boundary, and the tree path allocates a fresh parser per parse — its documented
no-zero-alloc contract; the direct path's reuse hides it). The fast lane ran on the way
(#60/#61, shipped as **v0.7.2** from the v0.7.1 line): the direct read's primitive-array
build (`ArrayStoreException`) and an element-failure infinite loop — both fixed before the
inline form landed. Correctness: the three-grade matrix and the element-error cells
(message + `$.x[i]` byte-identical across M8 / M8-off / tree) are standing gates.
Reproduction:

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew :jolt-bench:jmhJar
$JAVA_HOME/bin/java -jar jolt-bench/build/libs/*-jmh.jar ".*M8Tracer.*" -prof gc -wi 5 -i 5 -f 3
$JAVA_HOME/bin/java -jar jolt-bench/build/libs/*-jmh.jar ".*GapDissection.*" -prof gc -wi 5 -i 5 -f 3
```

**M9 trajectory (the scanner floor attack, gate-adjudicated at final HEAD across two
independent runs, in-run A/B):** M9 opened with the scanner dissection (#63) whose honest
headline reframed the target — the compact corpus's scanner cost is **per token (7.2–8.6
ns/token), not per byte** (string spans 0.625 ns/byte, whitespace ~0.2–0.3 ns/byte and absent
from compact fixtures; the dispatch family ≈ 55–60% of drain samples). The T0-ranked levers
were then measured one by one, and the projections were refuted by the measurements — three
times: the per-token shave delivered **+1.0–2.8%** on the pinned drains (adjacent-pair
adjudicated; projected −6–9% — C2 had already CSE'd the redundant probe), the string-span
SWAR was built, measured at **−8–17%** on the pinned corpus (projected −2–6%; the per-span
word-evaluation tax dominates 2–6-byte spans) and retracted per win-to-ship, and the number
and whitespace levers were no-ops by the dissection's own ledger. What landed in jolt-core:
the merged fetch (one bounds check + one load per token, `skipWhitespace` folded away) and
the cached container top — behavior byte-identical, 2,709 gates green with zero edits
(#64).

**The gate adjudication (#66, two independent runs at final HEAD, 3×5×1s, `-prof gc`):**
the **outright-win pin — generated direct ≥ 1.0× Jackson afterburner in-run with error bands
disjoint and ahead — is NOT MET, disclosed**: POJO generated direct **1.022× / 0.999×**
afterburner (3.903M vs 3.819M; 3.858M vs 3.862M — parity-to-ahead point estimates, bands
overlapping in both runs). The pin's evidence (the −25–40 ns lever projection ≈ 1.08–1.10×)
was falsified by T1's measurements; the one-time recalibration clause was left unused on
purpose — re-pinning after seeing the gate numbers would discard the disjointness noise
guard, so the miss is disclosed with its attribution instead. The **zero-allocation arm is
MET**: tokenDrain ≈ 0 B/op (0.001–0.021 across every drain) and generated direct
**392.004 / 1352.015 B/op** — byte-identical across both runs and to the baseline code run
the same hour (#64's instrument check; the +0.003/+0.015 last digits vs M8-day's .001 are
machine state, not allocation). The **simd ship rule is NOT MET** — adjudicated in #65,
the module is parked (see the jolt-simd section below). Jackson multiples, disclosure only:
POJO **parity-to-ahead in point estimates** (the contested ceiling is now met at parity in
both runs' centers), whole-graph **1.06× / 1.08× databind** (the M8 flip holds and widens)
and **1.67× / 1.73× the M8-off reference arm**. Cross-run tokenDrain multiples vs
v0.8.0-m8 (8.74M) read 0.96–0.99× — inside the machine's ±10%/2-hour drift; the levers' real
effect lives in the adjacent-pair ratios, not cross-run multiples. Method note inherited
from this milestone: the machine drifts up to ±10% within two hours, so every cross-config
claim in M9 was adjudicated with **adjacent-pair alternation** (baseline/config runs
interleaved, ratio per adjacent pair, median) wherever no in-run A/B arm exists; the scanner
has no dual form — stated honestly, the Utf16 engine stays scalar by design.

Reproduction:

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew :jolt-bench:jmhJar
$JAVA_HOME/bin/java -jar jolt-bench/build/libs/*-jmh.jar ".*M8Tracer.*" -prof gc -wi 5 -i 5 -f 3
$JAVA_HOME/bin/java -jar jolt-bench/build/libs/*-jmh.jar ".*ScannerDissection.*" -prof gc -wi 5 -i 5 -f 3
$JAVA_HOME/bin/java -jar jolt-bench/build/libs/*-jmh.jar ".*SimdDissection.*" -prof gc -wi 5 -i 5 -f 3
```

### Generated binders (jolt-gen, M4 + M5 + M7 + M8)

An optional module that runtime-generates a dedicated binder class per target type via
ByteBuddy. Non-public targets fall back to the reflective binder — as do public targets the
capability predicate refuses (a non-public reference component type keeps the WHOLE type on
the reflective arm, never a half-generated class; #53). Since M5 the generated
parse/write bodies are **straight-line per-component bytecode** — accessor/field-name
constants baked in, strict tag checks with direct Node-reader calls, cached child binders for
nested record/POJO components — no metadata arrays and no per-call property-name derivation
in the hot loop. Since M7 the SAME generated class carries THREE forms on one instance: the
tree `parse`, the **token-consuming direct read** (`parseTokens` — the field loop inline,
strict scalar reads through the shared error helpers, and DIRECT construction: NEW +
per-parameter stack passing + INVOKESPECIAL canonical, no args array, no constructor-path
boxing) and `write`. The declaration is the capability flag: a record/POJO whose component
types are all public and emittable gets the direct read; non-public component types keep
the whole type on the reflective arm. Records and POJOs both get the treatment. Since M8,
collection components DECOMPOSE at generation time: a shape the inliner supports
(`List<X>`, `Map<String,X>`, reference-component arrays, nested to any depth; scalars,
enums, record/POJO element children through the monomorphic child body) emits its element
loop INTO the parent body — no per-value door call; everything else (untyped/natural
elements, wildcards, non-String keys, polymorphic, non-public or non-capable element
classes, primitive-component arrays) keeps the shared Plan-driven door. Field matching uses
pre-encoded `FieldName` keys (the M8-T0 dissection's #1 cost was the String overload's
single-entry cache thrash — 47–53% of read time). `GeneratedBinders.referenceForType`
emits the M7-form body (String chain + door calls) under the `Gen$Ref$` namespace as a
benchmark-only comparator arm — never registered with the facade.

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

## jolt-simd (M9, experimental — parked)

`jolt-simd` is an optional module holding `SimdUtf8Parser`: a full UTF-8 parser (a third engine
under the same parity gates as the two core engines) that runs the two vectorizable shapes the
M9 scanner dissection identified — the string-span clean run and whitespace runs — on the
JDK Vector API. The token dispatch, numbers and literals stay scalar (no vector shape exists
for a branchy one-byte-lookahead state machine).

**It is not shipped.** The module was built under a win-to-ship rule pinned before any code
existed: the SIMD engine's in-run token drain must beat the scalar floor by ≥ 1.15× on the
compact fixture corpus (both drains), or it records the honest negative result and stays out
of the release. Two independent in-run A/B runs (3 forks × 5×1s, `-prof gc`, Apple Silicon,
Zulu 21):

| Token drain | Scalar | SIMD | Ratio (run 1 / run 2) |
|---|---|---|---|
| POJO fixture (16 tokens, 77 B) | 8.59M / 8.61M ops/s | 8.71M / 8.71M ops/s | **1.01× / 1.01×** |
| Graph fixture (52 tokens, 318 B) | 2.44M / 2.58M ops/s | 2.56M / 2.55M ops/s | **1.05× / 0.99×** |
| Long strings (disclosure: 8 × 200-char values) | 0.96M / 0.99M ops/s | 4.52M / 4.40M ops/s | 4.68× / 4.46× |

Both compact drains sit at parity — bands overlap in both runs — against a 1.15× bar: the
small-message scanner cost is per token, and the spans the vectors can compress are 2–6
bytes. Where spans dominate (the disclosure row) the clean run is a 4.5× win; that is not
the workload this library optimizes. Zero allocation holds on every arm (≈ 0 B/op — the
vector mask chain is fully intrinsified in steady state).

Using it anyway (UTF-8 engine only; the String engine stays scalar): every JVM that loads
the engine needs `--add-modules jdk.incubator.vector`, and the core's one hook routes the
`JoltParser`-typed UTF-8 entries (`parse`, `tryParse`, the `InputStream` adapters) to it:

```java
Jolt.installUtf8ParserFactory(SimdUtf8Parser.factory());   // default: the scalar engine
JoltParser p = Jolt.parse(bytes);                          // now a SimdUtf8Parser
```

The concrete reuse entry `Jolt.utf8Parser` stays scalar by contract (its `reset` type cannot
be honored by a factory-produced engine); construct `new SimdUtf8Parser(bytes)` and `reset`
it directly for the reuse shape. Reproduce the A/B:

```bash
./gradlew :jolt-bench:jmhJar
$JAVA_HOME/bin/java -jar jolt-bench/build/libs/*-jmh.jar ".*SimdDissection.*" -prof gc
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

Modules: `jolt-core` (streaming parser + writer, zero dependencies), `jolt-tree` (document
model), `jolt-bind` (binding facade), `jolt-gen` (generated binders), `jolt-simd` (the parked
Vector-API engine — needs `--add-modules jdk.incubator.vector`), `jolt-bench` (JMH),
`jolt-test` (corpus + differential gates).

## Design

Domain vocabulary lives in [CONTEXT.md](CONTEXT.md); the architectural decisions in
[docs/adr/](docs/adr/) — strict-by-default with zero-cost lenient switches (0001), number
promotion (0002), VarHandle-not-Unsafe / no JDK internals (0003), a zero-dependency layered
core (0004), the three-tier error model (0005), and the Java 21 / HotSpot-first platform
stance (0006).

## License

[Apache-2.0](LICENSE)
