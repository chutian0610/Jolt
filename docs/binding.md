# Binding

Records and POJOs ↔ JSON. The binding layer sits on the verified core and tree layers: by
default a document loads through the tree, and on the direct read path it goes straight from
parser tokens to the constructed target.

Strict by design: the JSON type must match the target — `"42"` does not bind to an int, and
every failure is a `JsonBindException` carrying the JSON path. No annotations, no naming
strategies: JSON names are component/property names verbatim.

- [Quick start](#quick-start)
- [What binding promises](#what-binding-promises)
- [The two read paths](#the-two-read-paths)
- [Generated binders (jolt-gen)](#generated-binders-jolt-gen)
- [Entry points](#entry-points)
- [Configuration](#configuration)
- [Design](#design)

## Quick start

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

## What binding promises

- **Round-trip.** JSON → record → JSON is byte-identical for canonical documents — the
  verbatim-number serialization is inherited from the tree layer.
- **Missing values.** Missing reference components read as null while missing/null primitives
  throw.
- **Abstract targets are rejected** (polymorphism is a future feature).
- **Supported targets.** Records (nested, by component name), POJOs (public no-arg constructor +
  public setters), collections (`List<X>`, `X[]`, `Map<String,X>` with generic signatures),
  enums (by `name()`), all strict scalars, and untyped `Object` elements via natural mapping.
- **No performance bars.** Binding has no performance bars (same note as the tree layer). The
  relative bars that later milestones pinned for the direct and generated reads — met and not
  met — are in the [performance record](performance.md).

## The two read paths

The direct read path: parser tokens feed the binder straight to the constructed target — no
tree materialization. It is selected explicitly; the tree path stays the default.

```java
Order treeParsed = Bind.parse(jsonBytes, Order.class);
Order direct = Bind.parse(jsonBytes, Order.class,
        BindConfig.defaults().withDirect());   // tokens → constructor, no tree
assertEquals(treeParsed, direct);              // the two-arm gate: equal, always
```

| | Tree path (default) | Direct path |
|---|---|---|
| Selected by | `BindConfig.defaults()` | `BindConfig.defaults().withDirect()` |
| Reads from | A `Node` tree built by `Tree.parse` | Parser tokens |
| Binder | The reflective binder | The reflective direct binder — or the generated direct read, when a registered generated binder declares one |
| Target types | Any bindable type | Public targets only; a non-public target stays on the tree path |
| Steady-state allocation | The tree and the target graph | The target object graph only; error paths are assembled lazily on failure |

- **Semantic equivalence.** The two paths are semantically EQUIVALENT: same values, same error
  matrix, same `$` paths. A divergence between them is a gate failure, not a footnote.
- **"Direct" names what the read side skips.** The write direction has been streaming since M4.
- **Generated code for nested values.** Whenever a generated binder is registered for a type,
  nested record/POJO values of that type route to it.

## Generated binders (jolt-gen)

An optional module that runtime-generates a dedicated binder class per target type via ByteBuddy.
The core, tree and bind modules never depend on it
([ADR-0004](adr/0004-three-layer-zero-dependency-core.md)).

### Who gets generated code

The capability predicate — one rule for both read paths (#53):

- Non-public targets fall back to the reflective binder.
- So do public targets the predicate refuses: a non-public reference component type keeps the
  WHOLE type on the reflective arm — never a half-generated class.
- Records and POJOs both get the treatment.

### What a generated class is

Since M5 the generated parse/write bodies are **straight-line per-component bytecode**:
accessor/field-name constants baked in, strict tag checks with direct Node-reader calls, cached
child binders for nested record/POJO components — no metadata arrays and no per-call
property-name derivation in the hot loop.

Since M7 the SAME generated class carries THREE forms on one instance: the tree `parse`, the
**token-consuming direct read** (`parseTokens`) and `write`. The direct read has the field loop
inline, strict scalar reads through the shared error helpers, and DIRECT construction: NEW +
per-parameter stack passing + INVOKESPECIAL canonical, no args array, no constructor-path boxing.
A generated class emits `parseTokens` exactly when its target shape is supported.

Since M8, collection components DECOMPOSE at generation time:

| Element shape | What the inliner emits |
|---|---|
| `List<X>`, `Map<String,X>`, reference-component arrays, nested to any depth — for scalar, enum, record and POJO elements (record/POJO elements through the monomorphic child body) | The element loop INTO the parent body — no per-value door call |
| Untyped/natural elements, wildcards, non-String keys, polymorphic, non-public or non-capable element classes, primitive-component arrays | The shared Plan-driven door |

Field matching uses pre-encoded `FieldName` keys (the M8-T0 dissection's #1 cost was the String
overload's single-entry cache thrash — 47–53% of read time).

### Using a generated binder

```java
import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.JoltWriter;
import io.github.chutian0610.jolt.bind.TypedBinder;
import io.github.chutian0610.jolt.gen.GeneratedBinders;
import io.github.chutian0610.jolt.tree.Tree;

TypedBinder<Order> binder = GeneratedBinders.forType(Order.class);   // cached per type

Node node = Tree.parse(jsonBytes);
Order order = binder.parse(node, "$", BindConfig.defaults());         // the tree form

JoltWriter writer = Jolt.write();
binder.write(order, writer);                                          // the write form
String json = writer.toString();
```

`GeneratedBinders.forType` also registers the binder with the facade, which is what lets the
direct path use the generated direct read — see
[How the direct path picks its binder](#how-the-direct-path-picks-its-binder).

### Benchmark-only arms

`GeneratedBinders.referenceForType` emits the M7-form body (String chain + door calls) under the
`Gen$Ref$` namespace as a benchmark-only comparator arm, and `planCachedForType` returns the
M4-era plan-executing binder. Neither is a supported path, and neither is ever registered with
the facade.

## Entry points

| Call | What it does |
|---|---|
| `Bind.parse(json, type)` | Tree path with the default config: unknown fields ignored. `json` is `byte[]` or `String`. |
| `Bind.parse(json, type, config)` | The tree path — or the direct path for a public target under `withDirect()`. |
| `Bind.parse(json, binder)` | The tree path through an explicit `TypedBinder`. |
| `Bind.write(value)` | Serializes a value as compact JSON. |
| `Bind.write(value, binder)` | The same, through an explicit binder. |
| `Bind.reflectiveBinder(type)` | The reflective `TypedBinder` for a type. |
| `GeneratedBinders.forType(type)` | jolt-gen. The generated binder for a type, cached per class and registered with the facade. |

## Configuration

`BindConfig` is immutable: start from `defaults()` and chain the switches.

| Switch | Default | Effect |
|---|---|---|
| `BindConfig.defaults()` | — | Unknown fields ignored; the tree path. |
| `.withFailOnUnknown()` | off | Unknown JSON fields raise `JsonBindException` instead of being ignored. |
| `.withDirect()` | off | The direct read path, for public targets. |

## How the direct path picks its binder

Under `withDirect()`, the facade consults the generated-binder registry before the reflective
direct binder:

1. A generated binder that DECLARES the direct read (`hasDirectRead`) consumes the tokens itself.
2. A registry hit without a direct read — and an outright miss — keep the reflective direct
   binder, unchanged.

```java
GeneratedBinders.forType(Order.class);   // generate once: cached, and registered with the facade

Order order = Bind.parse(jsonBytes, Order.class,
        BindConfig.defaults().withDirect());   // now the generated direct read
```

Both grades reuse the thread's parser pair and path carrier. Steady-state allocation is the
target object graph only.

## Design

- **Two grades, one algorithm.** The reflective direct binder and the generated direct read carry
  the same read algorithm as two hand-kept transcriptions — a controlled mirror, held level by
  the gate matrices ([ADR-0007](adr/0007-controlled-mirror-for-the-direct-read.md)).
- **Two layers, two exceptions.** `JsonBindException` carries the JSON path (`$.user.tags[2]`)
  and is distinct from `JsonParseException`: parse errors are syntax, bind errors are data mapping.
- **The dependency points one way.** `jolt-bind` never depends on `jolt-gen`: generated binders
  register with the facade through `Bind.registerGenerated`, in the forward direction.
- **Vocabulary.** `Bind`, `Direct bind`, `Binding shape`, `Capability predicate`,
  `Specialization`, `Strict binding`, `TypedBinder` and `JsonBindException` are defined in
  [CONTEXT.md](../CONTEXT.md).
