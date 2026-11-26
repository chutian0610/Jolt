# jolt-simd (M9, experimental — parked)

`jolt-simd` is an optional module holding `SimdUtf8Parser`: a full UTF-8 parser (a third engine
under the same parity gates as the two core engines) that runs the two vectorizable shapes the
M9 scanner dissection identified — the string-span clean run and whitespace runs — on the JDK
Vector API. The token dispatch, numbers and literals stay scalar (no vector shape exists for a
branchy one-byte-lookahead state machine).

It is never a dependency of `jolt-core`, `jolt-tree`, `jolt-bind` or `jolt-gen`
([ADR-0004](adr/0004-three-layer-zero-dependency-core.md)): whoever adds the module adds the
incubator flag with it.

**It is not shipped.** The module was built under a win-to-ship rule pinned before any code
existed: the SIMD engine's in-run token drain must beat the scalar floor by ≥ 1.15× on the
compact fixture corpus (both drains), or it records the honest negative result and stays out of
the release.

## The ship rule and its result

Two independent in-run A/B runs (3 forks × 5×1s, `-prof gc`, Apple Silicon, Zulu 21):

| Token drain | Scalar | SIMD | Ratio (run 1 / run 2) |
|---|---|---|---|
| POJO fixture (16 tokens, 77 B) | 8.59M / 8.61M ops/s | 8.71M / 8.71M ops/s | **1.01× / 1.01×** |
| Graph fixture (52 tokens, 318 B) | 2.44M / 2.58M ops/s | 2.56M / 2.55M ops/s | **1.05× / 0.99×** |
| Long strings (disclosure: 8 × 200-char values) | 0.96M / 0.99M ops/s | 4.52M / 4.40M ops/s | 4.68× / 4.46× |

**The ship rule is NOT MET** — adjudicated in #65. Both compact drains sit at parity — bands
overlap in both runs — against a 1.15× bar: the small-message scanner cost is per token, and the
spans the vectors can compress are 2–6 bytes. Where spans dominate (the disclosure row) the clean
run is a 4.5× win; that is not the workload this library optimizes. Zero allocation holds on
every arm (≈ 0 B/op — the vector mask chain is fully intrinsified in steady state).

The dissection behind the lever, and the rest of the M9 record, is in
[performance.md](performance.md#m9-the-scanner-floor-attack).

## Using it anyway

UTF-8 engine only; the String engine stays scalar. Every JVM that loads the engine needs
`--add-modules jdk.incubator.vector`. The core's one hook routes the `JoltParser`-typed UTF-8
entries (`parse`, `tryParse`, the `InputStream` adapters) to it:

```java
Jolt.installUtf8ParserFactory(SimdUtf8Parser.factory());   // default: the scalar engine
JoltParser p = Jolt.parse(bytes);                          // now a SimdUtf8Parser
```

The concrete reuse entry `Jolt.utf8Parser` stays scalar by contract (its `reset` type cannot be
honored by a factory-produced engine); construct `new SimdUtf8Parser(bytes)` and `reset` it
directly for the reuse shape.

## Reproduce the A/B

Set up as described in [performance.md](performance.md#reproducing-the-baseline), then:

```bash
$JAVA_HOME/bin/java -jar jolt-bench/build/libs/*-jmh.jar ".*SimdDissection.*" -prof gc -wi 5 -i 5 -f 3 --add-modules jdk.incubator.vector
```

Without `--add-modules jdk.incubator.vector` the JVM cannot load the engine's vector classes.
