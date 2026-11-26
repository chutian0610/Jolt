# Performance

Jolt's benchmark record: the method, the numbers claimed at each milestone, and the commands
that reproduce them. Every milestone pinned its bars up front and recorded the outcome as it
came out at the gate — met, straddled, or not met. The README keeps the headline; this file
keeps the whole trajectory, disclosures included.

- [Method](#method)
- [M1: the streaming core](#m1-the-streaming-core)
- [M4 and M5: generated binders](#m4-and-m5-generated-binders)
- [M6: the reflective direct read](#m6-the-reflective-direct-read)
- [M7: the generated direct read](#m7-the-generated-direct-read)
- [M8: the Jackson ceiling contest](#m8-the-jackson-ceiling-contest)
- [M9: the scanner floor attack](#m9-the-scanner-floor-attack)
- [Reproducing the baseline](#reproducing-the-baseline)

M2 (the tree layer) and M3 (the binding layer) set no performance bars — their gates are
correctness gates, which is why they have no section here.

## Method

- **Harness.** JMH: 3 forks × 5 iterations × 1 s after 5 warmup iterations (`-wi 5 -i 5 -f 3`;
  the allocation check runs 2 forks), `-prof gc` for allocation. **The headline numbers are the
  fixed standard runner's** (see Snapshots); the historical gate bars in the milestone sections
  below were pinned and adjudicated on the **gate machine** — a fixed local development machine,
  hardware undisclosed — running Zulu JDK 21.
- **In-run A/B.** The competitor arm and the Jolt arm it is judged against run in the same JMH
  run. It is the only trustworthy comparator (the M6 cross-day drift lesson): gates are
  adjudicated in-run, and cross-run multiples are disclosure only.
- **Error bands.** Every multiple is read with its error bands: overlapping bands mean parity,
  and a win needs bands that are disjoint and ahead.
- **Adjacent-pair alternation.** The gate machine drifts up to ±10% within two hours, so wherever
  no in-run A/B arm exists, baseline and config runs are interleaved and the ratio is taken per
  adjacent pair (median).
- **Snapshots.** The `benchmark-snapshot` workflow runs the full baseline on a standard runner
  (manual trigger only), so snapshots are comparable across time and free of local-machine
  noise — **this is the record the README quotes**. See
  [Reproducing the baseline](#reproducing-the-baseline). The first fixed snapshot —
  the post-C4–C9 baseline — lives at
  [docs/benchmarks/c4-c9-post-merge-snapshot.md](benchmarks/c4-c9-post-merge-snapshot.md).
- **Adjudication platform.** Performance verdicts adjudicate on the fixed standard runner
  (Linux) — the gate machine's numbers are archive evidence and never adjudicate a bar.
  Settled after the first snapshot exposed the platform sensitivity: under this rule, the
  M9 outright-win pin (POJO generated direct ≥ 1.0× Jackson afterburner) is re-adjudicated
  **MET** — 1.34–1.37× with disjoint bands across three batches; the gate's parity-to-ahead
  NOT MET (#66) stands as the gate machine's archive entry.

## M1: the streaming core

The M1 bars, measured on the final M1 code (commit `31f06c2`-lineage, verified at gate review):

| Benchmark | Result |
|---|---|
| Small-message parse (token drain, reused instance) | **7.67M ± 0.11M ops/s** |
| Jackson 2.18 databind `readTree` (the M1 bar) | 3.49M ± 0.10M ops/s |
| dsl-json 2.0.2 untyped `deserialize` (stretch) | 5.06M ± 0.54M ops/s |
| Steady-state allocation, reused instance | **≈ 0 B/op** (`gc.alloc.rate.norm ≈ 10⁻⁴`) |

Corpus: one ~90-byte object, pre-built `byte[]`, no IO in the loop.

**Honest shape disclosure:** Jolt's arm drains the token stream with lazy materialization
(nothing is built); the Jackson and dsl-json arms materialize full trees — each arm is its
library's canonical small-message read path. This is "cheapest faithful read vs canonical
read", not tokenizer-vs-tokenizer. Note also that Jackson's Afterburner module accelerates
POJO binding and is inert on `readTree`; the bar compares against Jackson's canonical read
regardless.

Reproduce:

```bash
$JAVA_HOME/bin/java -jar jolt-bench/build/libs/*-jmh.jar "CompetitorBenchmark.*" -wi 5 -i 5 -f 3
$JAVA_HOME/bin/java -jar jolt-bench/build/libs/*-jmh.jar "ZeroAllocBenchmark.reusedInstanceTokenDrain" -prof gc -wi 5 -i 5 -f 2
```

## M4 and M5: generated binders

The M4 → M5 story, measured honestly at each step. Both rows are the top-level POJO arm with
disjoint error bands; the multiples are against the reflective binder.

| Generated binder | Parse | Write |
|---|---|---|
| M4 — the cached plan, executed reflectively | ≈ 0.92× | ≈ 0.94× |
| M5 — straight-line bytecode | **3.28×** (6.15M ± 0.42M vs 1.88M ± 0.22M ops/s) | **1.66×** (6.28M ± 0.29M vs 3.79M ± 0.13M ops/s) |
| The bar | ≥ 2× — **met** | ≥ 1.5× — **met** |

- M4's ≈ 0.92× parse / 0.94× write is within noise of parity, honestly recorded rather than
  claimed.
- The plan-cached reference arm reproduces the M4 parity shape in the same run (1.07× / 0.95×),
  attributing the delta to the straight-line bytecode itself.
- **Honest dilution disclosure:** on a whole-graph nested record document the specialized
  multiples dilute to parse 1.01× / write 1.06× — the top-level body is a minority of the bind,
  and nested record/collection values route through the shared `Bind` dispatch by design.

Reproduce:

```bash
$JAVA_HOME/bin/java -jar jolt-bench/build/libs/*-jmh.jar ".*GenVsReflective.*" -wi 5 -i 5 -f 3
```

## M6: the reflective direct read

Measured honestly, 3 forks × 5×1s / `-prof gc`. The multiples are the reflective direct path
over the tree-path reflective parse.

| Fixture | Result |
|---|---|
| Top-level POJO | **1.94× / 2.05×** across two independent runs — the pinned ≥ 2× bar straddled, not claimed |
| Whole-graph record | **1.26–1.34×** |
| Allocation | **3624 B/op** vs the tree path's **7448** — zero tree/Node objects (JFR-verified) |

The allocation is the target graph, text materialization, and reflective-construction bridging
— the generated direct binder's M7 elimination list.

M7's fifth arm later attributed the POJO multiple to cross-day machine drift; see
[M7: the generated direct read](#m7-the-generated-direct-read).

Reproduce:

```bash
$JAVA_HOME/bin/java -jar jolt-bench/build/libs/*-jmh.jar ".*DirectBindBenchmark.*" -wi 5 -i 5 -f 3
$JAVA_HOME/bin/java -jar jolt-bench/build/libs/*-jmh.jar ".*DirectBindAlloc.*" -prof gc -wi 5 -i 5 -f 3
```

## M7: the generated direct read

Two independent runs at final HEAD.

**The pinned ≥ 2× bar is met clearly.** The multiples are generated direct over the tree-path
reflective parse.

| Fixture | Result |
|---|---|
| POJO (the judgment arm) | **2.61× / 2.60×** (2.116M / 2.125M vs 0.811M / 0.817M ops/s; the worst-band edge still clears 2×) |
| Whole-graph record | **1.48× / 1.54×** |

**The honest headline is the fifth arm** — the in-run A/B against the same M6 reflective direct
read — which disconfirms M6's attribution hypothesis:

- The reflective arm itself measures **2.099M / 2.156M** ops/s (parity, 1.01–1.02× the generated
  arm) and clears 2× too.
- The bridging elimination is real on the allocation side (−136 B/op) but throughput-neutral.
- M6's 1.94× was cross-day machine drift, and the in-run A/B is the only trustworthy comparator.
  The 2.6× belongs to the direct-read architecture itself.

Disclosure:

- Jackson afterburner's POJO ceiling stands at **1.82×** generated direct (contested, not
  matched; M8 contested it further).
- Jackson databind won the whole-graph record at **1.92–1.95×** at M7 — until
  [M8 flipped it](#m8-the-jackson-ceiling-contest).
- The generated direct arm is **~2× the default facade path**.

Allocation:

| Arm | B/op |
|---|---|
| Reflective direct | **3624** (reproduced exactly) |
| Generated direct | **3488** |
| Target-graph baseline | 960 |

The eliminated **−136 B/op** matches the predicted args `Object[10]` + Long/Double constructor
boxing, and the JFR class distribution shows the bridging classes absent from the generated arm
(the `Gen$` class lives on its allocation stacks).

Correctness: the three-grade matrix (tree reflective / reflective direct / generated direct:
pairwise-equal values, pairwise-identical failures, byte-identical writes) is a standing gate.

Reproduce:

```bash
$JAVA_HOME/bin/java -jar jolt-bench/build/libs/*-jmh.jar ".*DirectBindBenchmark.*" -wi 5 -i 5 -f 3
$JAVA_HOME/bin/java -jar jolt-bench/build/libs/*-jmh.jar ".*DirectBindAlloc.*" -prof gc -wi 5 -i 5 -f 3
```

## M8: the Jackson ceiling contest

Gate-adjudicated at final HEAD across two independent runs, in-run A/B.

### The dissection (#55)

M8 opened with a dissection that overturned the grilling's prime suspect: the binder match
chain, not text materialization, dominated both directions. `fieldNameEquals(String)` keeps a
single-entry expected-name cache, so every chain miss re-encoded the name — 47–53% of read time,
1656 of the graph's 3488 B/op. The door machinery was the minor share, 8–14%.

The one-time recalibration re-ranked the levers and admitted ONE additive `jolt-core` API — the
pre-encoded `FieldName` key (`JoltParser.fieldNameEquals(FieldName)`, both engines; the String
overload frozen as the reference arm's substrate).

### The levers

| Task | Lever |
|---|---|
| T1 | Baked `FieldName` match chains for BOTH direct grades |
| T2 | Collection-loop inlining at full width — element loops and element reads in the parent body, record/POJO elements through the monomorphic child body, per-parse path carrier, `bigDecimalValue` parsing the span in place |

Lever by lever, the generated direct read went (start → after T1 → after T2):

| Fixture | Latency | Allocation |
|---|---|---|
| POJO | 491 → 296 → **265 ns** | 800 → 464 → **392 B/op** |
| Whole-graph | 1958 → 1311 → **1052 ns** | 3488 → 1832 → **1352 B/op** |

### The gate: both T0-pinned bars met, every run

| Bar | Result | Pin |
|---|---|---|
| POJO generated direct vs Jackson afterburner | **0.95× / 1.11× / 0.97×** | ≥ 0.9× — **met**. The 1.0× parity aspiration is disclosed unreached: bands do not overlap in the clean runs |
| Whole-graph vs the in-run M8-off reference arm | **1.66× / 1.68× / 1.61×** | ≥ 1.5× — **met** |

The M8-off comparator is the M7-form body under the `Gen$Ref$` namespace in the same build —
frozen `fieldNameEquals(String)` + door calls. It is a conservative comparator, since the
shared substrate improves under it.

**The whole-graph databind deficit is GONE:** 1.92–1.95× behind at v0.7.1 → **1.01–1.03×**
(parity-to-ahead, bands overlap).

Allocation:

| Arm | B/op | Note |
|---|---|---|
| Generated direct | **1352** | Against the 960 B/op target-graph floor (reproduced exactly) |
| Reflective direct | **1552** | From 3624 — the shared substrate's effect on the other grade |
| Tree | **7456** | +8: the parser instance grew by the numeric-scratch field past an alignment boundary, and the tree path allocates a fresh parser per parse — its documented no-zero-alloc contract; the direct path's reuse hides it |

The fast lane ran on the way (#60/#61, shipped as **v0.7.2** from the v0.7.1 line): the direct
read's primitive-array build (`ArrayStoreException`) and an element-failure infinite loop — both
fixed before the inline form landed.

Correctness: the three-grade matrix and the element-error cells (message + `$.x[i]`
byte-identical across M8 / M8-off / tree) are standing gates.

Reproduce:

```bash
$JAVA_HOME/bin/java -jar jolt-bench/build/libs/*-jmh.jar ".*M8Tracer.*" -prof gc -wi 5 -i 5 -f 3
$JAVA_HOME/bin/java -jar jolt-bench/build/libs/*-jmh.jar ".*GapDissection.*" -prof gc -wi 5 -i 5 -f 3
```

## M9: the scanner floor attack

Gate-adjudicated at final HEAD across two independent runs, in-run A/B.

### The dissection (#63)

M9 opened with the scanner dissection, whose honest headline reframed the target — the compact
corpus's scanner cost is **per token (7.2–8.6 ns/token), not per byte**: string spans cost
0.625 ns/byte, whitespace ~0.2–0.3 ns/byte and is absent from compact fixtures, and the dispatch
family is ≈ 55–60% of drain samples.

### The levers: projections refuted by the measurements, three times

The T0-ranked levers were measured one by one.

| Lever | Projected | Measured | What happened |
|---|---|---|---|
| The per-token shave | −6–9% | **+1.0–2.8%** on the pinned drains (adjacent-pair adjudicated) | C2 had already CSE'd the redundant probe |
| The string-span SWAR | −2–6% | **−8–17%** on the pinned corpus | Built, then retracted per win-to-ship — the per-span word-evaluation tax dominates 2–6-byte spans |
| The number and whitespace levers | — | No-ops | By the dissection's own ledger |

**What landed in `jolt-core`** (#64): the merged fetch (one bounds check + one load per token,
`skipWhitespace` folded away) and the cached container top — behavior byte-identical, 2,709
gates green with zero edits.

### The gate adjudication (#66)

Two independent runs at final HEAD, 3×5×1s, `-prof gc`.

| Pin | Result | Verdict |
|---|---|---|
| **Outright win** — generated direct ≥ 1.0× Jackson afterburner in-run, with error bands disjoint and ahead | POJO generated direct **1.022× / 0.999×** afterburner (3.903M vs 3.819M; 3.858M vs 3.862M) | **NOT MET**, disclosed — parity-to-ahead point estimates, bands overlapping in both runs |
| **Zero allocation** | tokenDrain ≈ 0 B/op (0.001–0.021 across every drain); generated direct **392.004 / 1352.015 B/op** | **MET** |
| **SIMD ship rule** | See [docs/simd.md](simd.md) | **NOT MET** — adjudicated in #65, the module is parked |

Why the outright-win pin is disclosed rather than re-pinned: the pin's evidence (the −25–40 ns
lever projection ≈ 1.08–1.10×) was falsified by T1's measurements, and the one-time
recalibration clause was left unused on purpose — re-pinning after seeing the gate numbers would
discard the disjointness noise guard, so the miss is disclosed with its attribution instead.

**Re-adjudication under the settled platform rule.** The adjudication platform is now the
fixed standard runner (Linux; see Method — Adjudication platform). Under it the outright-win
pin is adjudicated **MET**: 1.34–1.37× afterburner, bands disjoint across three independent
batches, in [the first snapshot's record](benchmarks/c4-c9-post-merge-snapshot.md). The NOT
MET above stands as the gate machine's archive entry; the other two verdicts are unchanged
by the platform (allocation is deterministic; the SIMD park holds on both).

Zero allocation, reproduced: the numbers are byte-identical across both runs and to the
baseline code run the same hour (#64's instrument check). The +0.003/+0.015 last digits vs
M8-day's .001 are machine state, not allocation.

Jackson multiples, disclosure only:

| Fixture | Comparator | Multiple |
|---|---|---|
| POJO | Jackson afterburner | Parity-to-ahead in point estimates — the contested ceiling is now met at parity in both runs' centers |
| Whole-graph | Jackson databind | **1.06× / 1.08×** (the M8 flip holds and widens) |
| Whole-graph | The M8-off reference arm | **1.67× / 1.73×** |

**Method note, inherited from this milestone:** cross-run tokenDrain multiples vs v0.8.0-m8
(8.74M) read 0.96–0.99× — inside the machine's ±10%/2-hour drift, so the levers' real effect
lives in the adjacent-pair ratios, not cross-run multiples. The machine drifts up to ±10% within
two hours, so every cross-config claim in M9 was adjudicated with **adjacent-pair alternation**
wherever no in-run A/B arm exists. The scanner has no dual form — stated honestly, the Utf16
engine stays scalar by design.

Reproduce:

```bash
$JAVA_HOME/bin/java -jar jolt-bench/build/libs/*-jmh.jar ".*M8Tracer.*" -prof gc -wi 5 -i 5 -f 3
$JAVA_HOME/bin/java -jar jolt-bench/build/libs/*-jmh.jar ".*ScannerDissection.*" -prof gc -wi 5 -i 5 -f 3
$JAVA_HOME/bin/java -jar jolt-bench/build/libs/*-jmh.jar ".*SimdDissection.*" -prof gc -wi 5 -i 5 -f 3 --add-modules jdk.incubator.vector
```

## Reproducing the baseline

Every command in this file needs a JDK 21 and the JMH jar:

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew :jolt-bench:jmhJar      # rebuild after any code change
```

For a snapshot that is comparable across time, use the `benchmark-snapshot` workflow
(manual trigger). It runs the ten batches below serially on a standard runner — one JMH at a
time, since parallel runs would pollute each other — with the same flags, and uploads the JSON
results and logs.

| Batch | Benchmark filter | `-prof gc` | Forks |
|---|---|---|---|
| `direct-bind` | `.*DirectBindBenchmark.*` | | 3 |
| `direct-bind-alloc` | `.*DirectBindAlloc.*` | ✓ | 3 |
| `gen-vs-reflective` | `.*GenVsReflective.*` | | 3 |
| `m8-tracer` | `.*M8Tracer.*` | ✓ | 3 |
| `gap-dissection` | `.*GapDissection.*` | | 3 |
| `scanner-dissection` | `.*ScannerDissection.*` | | 3 |
| `competitor` | `CompetitorBenchmark.*` | | 3 |
| `strict-parity` | `.*StrictParity.*` | | 3 |
| `zero-alloc` | `.*ZeroAlloc.*reusedInstance.*` | ✓ | 2 |
| `simd-dissection` | `.*SimdDissection.*` | ✓ | 3 |

The `simd-dissection` batch also needs `--add-modules jdk.incubator.vector`. The
`strict-parity` batch backs the strict-parity claim in the README's correctness section: lenient
switches present but off are statistically indistinguishable from the default strict path.
