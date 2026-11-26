# Technologies

What Jolt builds on, where each piece lives, and the boundary it must not cross. The decisions
behind these choices are argued in [docs/adr/](adr/); what they bought is measured in
[docs/performance.md](performance.md). This page is the map of the moving parts.

## Platform

**Java 21, HotSpot first** ([ADR-0006](adr/0006-platform-java21-hotspot-first.md)). The
toolchain baseline every module compiles against (enforced via Gradle toolchains). Performance
claims are per-VM, never universal — the bars are only valid on the VM they were taken on,
which is why the fixed-runner snapshots exist ([docs/performance.md](performance.md)).

## The dependency discipline

**Zero-dependency core** ([ADR-0004](adr/0004-three-layer-zero-dependency-core.md)).
`jolt-core` — the streaming parser and writer — compiles against the JDK alone: no
third-party libraries, no `VarHandle`, no `sun.misc.Unsafe`, no reflection, no bytecode
generation. Steady-state parsing allocates zero bytes as plain Java; the steady state is the
JIT's business, not the library's. `jolt-tree` and `jolt-bind` join the core in the
dependency discipline; only modules that genuinely need a runtime carry one.

**Standard reflection, no JDK internals** ([ADR-0003](adr/0003-no-jdk-internals-varhandle-over-unsafe.md)).
The reflective read/write path in `jolt-bind` runs on plain `java.lang.reflect` accessors —
`getDeclaredField`-grade, nothing lower. `Unsafe` is banned outright; `VarHandle` is the
sanctioned ceiling should low-level access ever be needed. The generated path (below) exists
precisely to route around reflection on the hot path.

**ByteBuddy — jolt-gen's one runtime dependency** ([ADR-0007](adr/0007-controlled-mirror-for-the-direct-read.md)).
The generated binders are runtime bytecode: ByteBuddy emits the binder subclasses (field
loops, drain bodies, the specialized twins) that read the parser's tokens straight into the
target type and write values straight back out, bypassing reflection per call. The emitted
bodies are a controlled mirror of the reflective semantics — a divergence between the two
read paths is a gate failure. ByteBuddy is the only third-party runtime dependency in the
deliverable set, and it stays quarantined in `jolt-gen`: `jolt-core` never sees it.

## The experimental edge

**JDK Vector API (incubating)** — `jolt-simd`. The third engine runs the scanner's two
vectorizable shapes on `jdk.incubator.vector`; every JVM that loads it needs
`--add-modules jdk.incubator.vector`. The module is built and **parked**: the win-to-ship
rule was not met, the negative result is recorded and reproduced on two architectures
([docs/simd.md](simd.md)). It is never a dependency of any other module — whoever adds the
module adds the incubator flag with it.

## Measurement and verification

**JMH** — every number in this repo is a JMH number. `jolt-bench` (via the `me.champeau.jmh`
Gradle plugin) holds the full baseline; the methodology — in-run A/B, error bands, the
±10%/2-hour drift rule, fixed-runner snapshots — is specified in
[docs/performance.md](performance.md).

**Jackson, afterburner, dsl-json — benchmark arms only.** The competitor comparators
(Jackson databind and afterburner 2.18.2, dsl-json) live in `jolt-bench`'s JMH scope. They
never enter a deliverable module's dependency graph; Jackson databind also serves as the
reference binder in `jolt-gen`'s test scope.

**JUnit Jupiter — the gates.** The `jolt-test` module runs the corpus and differential gates
(~1,560 correctness cases, the UTF-8↔UTF-16 parity matrix, the strict-parity benchmark pair)
in CI on every commit; see the module table in the [README](../README.md#modules).

## Build and CI

**Gradle (Kotlin DSL)** — seven modules: `jolt-core`, `jolt-tree`, `jolt-bind`, `jolt-gen`,
`jolt-simd`, `jolt-bench`, `jolt-test`. The dependency edges point one way:

```
jolt-core ← jolt-tree ← jolt-bind ← jolt-gen
jolt-core ← jolt-simd          (never the reverse)
jolt-bench, jolt-test ← all of the above
```

**GitHub Actions** — `ci.yml` gates every commit (build + benchmark smoke); 
`benchmark-snapshot.yml` takes the full fixed-runner baseline on manual trigger.
