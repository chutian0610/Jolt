<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/banner-dark.svg">
    <img alt="Jolt — a zero-dependency, performance-first JSON parser and writer for the Java 21+ JVM" src="docs/assets/banner-light.svg" width="680">
  </picture>
</p>

<p align="center">
  <a href="https://github.com/chutian0610/Jolt/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/chutian0610/Jolt/actions/workflows/ci.yml/badge.svg"></a>
  <a href="https://docs.oracle.com/javase/specs/jls/se21/html/index.html"><img alt="Java 21+" src="https://img.shields.io/badge/Java-21%2B-orange?logo=openjdk&amp;logoColor=white"></a>
  <a href="docs/adr/0001-strict-rfc8259-by-default.md"><img alt="strict RFC 8259" src="https://img.shields.io/badge/strict-RFC%208259-2ea44f"></a>
  <a href="docs/performance.md"><img alt="benchmarked with JMH" src="https://img.shields.io/badge/JMH-benchmarked-8250df"></a>
  <a href="LICENSE"><img alt="Apache-2.0" src="https://img.shields.io/badge/license-Apache--2.0-blue"></a>
</p>

<p align="center">
  <a href="#highlights">Highlights</a> ·
  <a href="#usage">Usage</a> ·
  <a href="#tree-layer-jolt-tree">Tree</a> ·
  <a href="#binding-layer-jolt-bind">Binding</a> ·
  <a href="#performance">Performance</a> ·
  <a href="#modules">Modules</a> ·
  <a href="#documentation">Docs</a>
</p>

---

A zero-dependency, performance-first JSON parser and writer for the Java 21+ JVM, engineered
for the small-message workload: RPC frames, log events, MQ payloads — messages under a few
kilobytes where latency and allocation are the whole cost.

**Status: pre-1.0, milestone M9 complete** (tag `v0.9.0-m9`). The streaming core, the tree
layer, the binding layer and the generated binders are in, gated in CI on every commit; the
Vector-API engine (`jolt-simd`) is built but parked — see below.

## Highlights

- **Streaming core** — a token-pull parser and writer with lazy materialization: values decode
  only when read, and steady-state parsing allocates zero bytes.
- **Tree layer** — one mutable `Node` class for every JSON value, compared by JSON value
  semantics.
- **Binding** — records and POJOs ↔ JSON with strict typing and no annotations, plus a direct
  read path that skips the tree.
- **Generated binders** — an optional module that emits a dedicated binder class per type as
  straight-line bytecode.
- **Correctness gates** — a ~1,560-case verification suite runs in CI on every commit.

## Usage

```java
// The core types live in io.github.chutian0610.jolt.

// Parse: token-pull streaming with lazy materialization — values decode only when read.
JoltParser parser = Jolt.parse(jsonBytes);          // byte[] (UTF-8) — the zero-copy fast path
Token t;
while ((t = parser.nextToken()) != Token.EOF) {
    if (t == Token.FIELD_NAME && parser.textValue().equals("skip-me")) {
        parser.skipChildren();                      // skip decodes nothing
    }
}

// String input runs on the UTF-16 engine — no transcoding bridge.
JoltParser p = Jolt.parse("{\"k\": \"v\"}");

// InputStream works through an internal buffering adapter.
JoltParser fromStream = Jolt.parse(inputStream);

// Reuse across messages: buffers retained, steady-state parsing allocates zero bytes.
Utf8Parser reused = Jolt.utf8Parser(jsonBytes);
reused.reset(nextMessageBytes);                     // config (switches, depth) stays fixed

// Exception-free validation: shared success sentinel, diagnostics on failure.
ParseResult result = Jolt.tryParse(jsonBytes);
if (!result.isSuccess()) { log(result.getFailure().getMessage()); }

// Writer: compact strict JSON, auto separators, reused output buffer.
String out = Jolt.write().startObject()
        .fieldName("name").value("jolt")
        .fieldName("tags").startArray().value("a").value("b").endArray()
        .endObject().toString();
```

Parsing is strict RFC 8259 by default. Comments and trailing commas are lenient features —
individually opt-in via `JoltConfig`, and they cost nothing when off
([ADR-0001](docs/adr/0001-strict-rfc8259-by-default.md)):

```java
JoltParser lenient = Jolt.parse(jsonBytes,
        JoltConfig.strict().withComments().withTrailingCommas());
```

Numbers follow the promotion policy ([ADR-0002](docs/adr/0002-number-promotion.md)):
integers within long range read as `long`, floats up to 17 significant digits as `double`,
everything else promotes to an exact `BigDecimal` — and `numberText()` always returns the
untouched source.

## Tree layer (`jolt-tree`)

An in-memory document model on top of the core: one final mutable `Node` class discriminated
by `TypeTag`, fluent construction, strict accessors, and JSON value equality (objects
key-order-independent, numbers numerically compared).

```java
import io.github.chutian0610.jolt.tree.Node;
import io.github.chutian0610.jolt.tree.Tree;

Node doc = Tree.parse(jsonBytes);              // same engines, config, depth limit as the core
String name = doc.get("name").asText();        // strict: type mismatch throws
doc.get("tags").add("new");                    // mutable, fluent
byte[] out = doc.toJson().getBytes(StandardCharsets.UTF_8);   // through the core writer

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

## Binding layer (`jolt-bind`)

Records and POJOs ↔ JSON, through the verified tree. Strict by design: the JSON type must
match the target — `"42"` does not bind to an int; every failure is a `JsonBindException`
carrying the JSON path. Unknown fields are ignored by default (a `withFailOnUnknown()` switch
exists). No annotations, no naming strategies: JSON names are component/property names
verbatim.

```java
import io.github.chutian0610.jolt.bind.Bind;
import io.github.chutian0610.jolt.bind.BindConfig;

public record Order(String id, Customer customer, List<Item> items) {}

Order order = Bind.parse(jsonBytes, Order.class);   // strict scalars, nested types,
                                                   // List/Map/arrays, enums by name
String json = Bind.write(order);                   // byte-identical for canonical documents

Order strict = Bind.parse(jsonBytes, Order.class,
        BindConfig.defaults().withFailOnUnknown()); // unknown fields → exception
```

| Read path | Selected by | Builds a tree? | Binder |
|---|---|---|---|
| **Tree** (the default) | `BindConfig.defaults()` | yes | the reflective binder |
| **Direct** | `BindConfig.defaults().withDirect()` | no | the reflective direct binder, or the generated direct read when `jolt-gen` provides one |

The two read paths are semantically equivalent — same values, same error matrix, same `$`
paths; a divergence between them is a gate failure. On the direct path, steady-state
allocation is the target object graph only.

**What binding promises:** JSON → record → JSON is byte-identical for canonical documents
(the verbatim-number serialization is inherited); missing reference components read as null
while missing/null primitives throw; abstract targets are rejected (polymorphism is a future
feature). Binding has no performance bars (same note as the tree layer).

**Generated binders (`jolt-gen`, optional):** runtime-generates a dedicated binder class per
target type via ByteBuddy, carrying the tree parse, the direct read and the write on one
instance. Non-public targets fall back to the reflective binder.

```java
GeneratedBinders.forType(Order.class);   // generate once, cached per type

Order direct = Bind.parse(jsonBytes, Order.class,
        BindConfig.defaults().withDirect());   // now read by the generated direct read
```

Supported targets, the capability rule, every entry point and switch:
[docs/binding.md](docs/binding.md).

## Performance

Measured with JMH on Apple Silicon (Zulu JDK 21): 3 forks × 5 × 1 s, `-prof gc` for
allocation. Two multiples in a cell are two independent runs.

**Core token drain** — ~90-byte message, reused instance (steady-state ≈ 0 B/op):

| Library | ops/s |
|---|---:|
| **Jolt** | **7.67M ± 0.11M** |
| dsl-json 2.0.2 | 5.06M ± 0.54M |
| Jackson 2.18 `readTree` | 3.49M ± 0.10M |

**Binding — the generated direct read vs Jackson 2.18:**

| Benchmark | Result | Against |
|---|---|---|
| POJO | **1.022× / 0.999×** — parity | Jackson 2.18 + Afterburner (the outright-win pin is **not met**) |
| Whole-graph record | **1.06× / 1.08×** | Jackson 2.18 databind |
| Allocation | **392 B/op** (POJO), **1352 B/op** (whole-graph) | a 960 B/op target-graph floor |

> **Honest shape disclosure (the core drain row):** Jolt's arm drains the token stream with lazy
> materialization (nothing is built); the Jackson and dsl-json arms materialize full trees — each
> arm is its library's canonical small-message read path. This is "cheapest faithful read vs
> canonical read", not tokenizer-vs-tokenizer. Note also that Jackson's Afterburner module
> accelerates POJO binding and is inert on `readTree`; the bar compares against Jackson's canonical
> read regardless.

Every bar of every milestone — including the ones that were straddled or not met — is in
[docs/performance.md](docs/performance.md), with the commands to reproduce each number.

## jolt-simd (experimental — parked)

An optional module holding `SimdUtf8Parser`, a third engine that runs the two vectorizable
shapes of the scanner on the JDK Vector API. **It is not shipped:** built under a win-to-ship
rule (≥ 1.15× the scalar floor on the compact fixtures), it measured parity — 1.01× on the POJO
fixture and 1.05× / 0.99× on the graph fixture — so it records an honest negative result and
stays out of the release. What it does win is the workload this library does not optimize:
long strings, at about 4.5×. The full table and how to try it anyway: [docs/simd.md](docs/simd.md).

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

## Modules

| Module | What it is | Depends on |
|---|---|---|
| `jolt-core` | Streaming parser + writer | nothing |
| `jolt-tree` | Document model (`Node`, `Tree`) | `jolt-core` |
| `jolt-bind` | Binding facade (`Bind`, `BindConfig`) | `jolt-core`, `jolt-tree` |
| `jolt-gen` | Generated binders — optional | `jolt-bind`, ByteBuddy |
| `jolt-simd` | Vector-API engine — experimental, parked | `jolt-core`, plus `--add-modules jdk.incubator.vector` |
| `jolt-bench` | JMH benchmarks | all of the above |
| `jolt-test` | Corpus + differential gates | all of the above |

## Building

Needs JDK 21 and the Gradle wrapper.

```bash
./gradlew build                        # full check gate (needs gh authenticated for corpus fetch)
./gradlew :jolt-test:jsonTestSuiteTest # the corpus suites alone
./gradlew :jolt-bench:jmh              # benchmarks
```

## Documentation

| Document | Covers |
|---|---|
| [CONTEXT.md](CONTEXT.md) | Domain vocabulary: the project's terms, and what to avoid saying instead. |
| [docs/binding.md](docs/binding.md) | The binding layer in depth: read paths, generated binders, entry points, switches. |
| [docs/performance.md](docs/performance.md) | The benchmark record: method, every milestone's bars and disclosures, reproduction commands. |
| [docs/simd.md](docs/simd.md) | The parked Vector-API engine: the negative result, and how to try it anyway. |
| [docs/adr/](docs/adr/) | Architecture decision records, indexed below. |
| [docs/agents/](docs/agents/) | Conventions for coding agents working in this repo. |

| ADR | Decision |
|---|---|
| [0001](docs/adr/0001-strict-rfc8259-by-default.md) | Parse strictly RFC 8259 by default; lenient features are explicit opt-in switches |
| [0002](docs/adr/0002-number-promotion.md) | Number promotion: `long`/`double` fast paths with a `BigDecimal` upgrade |
| [0003](docs/adr/0003-no-jdk-internals-varhandle-over-unsafe.md) | No JDK internal APIs — VarHandle over Unsafe |
| [0004](docs/adr/0004-three-layer-zero-dependency-core.md) | Three layers on a zero-dependency core |
| [0005](docs/adr/0005-three-tier-error-model.md) | Errors: internal error codes, one boundary exception, an optional `tryParse` surface |
| [0006](docs/adr/0006-platform-java21-hotspot-first.md) | Platform: Java 21 baseline, HotSpot C2 first, native-image out of scope |
| [0007](docs/adr/0007-controlled-mirror-for-the-direct-read.md) | The direct read's bodies stay a controlled mirror: one algorithm, two transcriptions |

## License

[Apache-2.0](LICENSE)
