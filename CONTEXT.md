# Jolt

Jolt is a Java library for parsing and serializing JSON, built for maximum performance on HotSpot JVMs. Performance is defined by the small-message workload: low latency and zero allocation on messages under ~4KB.

## Language

### Product shape

**Jolt**:
The library itself — a zero-dependency JSON toolkit for the JVM.
_Avoid_: "the parser" when referring to the whole library (see Parser).

**Parser**:
The reading direction: JSON text in, Java values out. Lives in the core layer.
_Avoid_: reader, deserializer, decoder.

**Writer**:
The writing direction: Java values in, JSON text out. Lives in the core layer.
_Avoid_: serializer, encoder.

**Core layer** (`jolt-core`):
The streaming parser and writer, zero dependencies. The only deliverable of milestone 1.

**Tree layer**:
An in-memory document model built on the core layer, delivered as the `jolt-tree` module (M2). Depends on `jolt-core`; the core never depends on it.
_Avoid_: DOM, JsonNode (Jackson's name).

**Node**:
The tree layer's single final class: one instance type for every JSON value, discriminated by `TypeTag`. Mutable after construction (fluent `put`/`add` return `this`).
_Avoid_: JsonObject/JsonArray (per-type class hierarchies), JoltNode.

**TypeTag**:
The node discriminator enum: `OBJECT`, `ARRAY`, `STRING`, `NUMBER`, `BOOLEAN`, `NULL`. One `NUMBER` tag — the parser's INT/FLOAT token split does not survive into the tree; numeric views answer per value.

**Missing vs JSON null**:
`get(key)` returning Java `null` means the key (or index) is absent; JSON `null` is a `Node` with `TypeTag.NULL`. Two different things, two different representations.
_Avoid_: MissingNode.

**Bind**:
The binding facade: `Bind.parse(json, Type)` constructs records/POJOs from JSON; `Bind.write(value)` serializes them back. JSON names are component/property names verbatim — no annotations, no naming strategies in M3.

**JsonBindException**:
The binding failure: a runtime exception carrying the JSON path (`$.user.tags[2]`), distinct from `JsonParseException` — parse errors are syntax, bind errors are data mapping. Two layers, two exceptions.

**Strict binding**:
No wide conversions: the JSON type must match the target ("42" does not bind to int). Missing reference components read as null (the constructor decides); missing or null values for primitive components throw.

**Value equality**:
`Node.equals`/`hashCode` follow JSON value semantics: objects compare key-order-independently, numbers compare by numeric value (BigDecimal semantics), `toString` emits compact JSON.

**Binding layer**:
POJO/record mapping built on the core and tree layers, delivered as the `jolt-bind` module (M3). Loads via the tree (parse → Node → constructed types; serialize symmetrically) — a direct streaming bind is a possible future evolution, not part of M3.

**`jolt-simd`**:
An optional experimental module using the Vector API for token scanning. Never a dependency of `jolt-core`.

### Semantics

**Strict mode**:
The default parsing semantics: exactly RFC 8259, nothing more.
_Avoid_: compliant mode, standard mode.

**Boundary exception**:
The error-handling contract: failures propagate internally as error codes (fast return, no throws); they materialize as a `JsonParseException` only at the public API boundary. The optional `tryParse` entry returns a `ParseResult` instead — a shared success sentinel, zero allocation on success.

**Engine parity**:
The contract that both lexical engines behave identically — same tokens, values, promotion boundaries, and error semantics — for identical document text. Guarded by dual-engine test matrices and the exhaustive escape round-trip; a drift between the engines' mirrored copies fails loudly.

**Lenient feature**:
An opt-in extension (comments, trailing commas, ...) enabled by explicit configuration. A lenient feature must never pollute the fast path when it is disabled.
_Avoid_: relaxed mode, permissive mode.

**Fast path**:
The shortest processing route for the common case. The fast-path contract: disabled lenient features and unused options cost zero.

**Number promotion**:
The strategy for JSON numbers: integers parse as `long` and decimals as `double` while they fit; values that overflow or need more precision are promoted to `BigDecimal`.
_Avoid_: fallback, coercion.

**Small-message workload**:
The primary performance target: high-throughput, low-latency parsing of messages under ~4KB (RPC, logging, messaging). Large-document streaming is supported by architecture but is not the optimization target.

**Lazy materialization**:
The core-layer contract that tokens record spans of the input instead of Java values; decoding happens only when a value is read. Skipping a value therefore costs nothing.

**Zero-allocation steady state**:
The hard performance contract: steady-state parsing on the fast path allocates zero bytes per operation, verified by JMH `-prof gc`. The library does not ship 1.0 without it.
