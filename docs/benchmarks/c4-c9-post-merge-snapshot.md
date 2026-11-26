# Performance snapshot: post-C4–C9 (the architecture deepening series)

The full JMH baseline taken after the architecture deepening series merged
(#71 `BindingShape`, #72 `BindErrorText` + the long-width fix, #73 `ParseErrors`
+ the UTF-8↔UTF-16 parity matrix, #74 the specializer twins + the record write's
guard, #75 `BindPath` public + the carrier's direct calls, #76 `DirectBinder`'s
public door, #77 ADR-0007 the controlled mirror).

- **Environment**: GitHub Actions standard runner (`ubuntu-latest`, 4 vCPU), Zulu JDK 21,
  single-threaded JMH forks. **Absolute numbers are runner-specific** — comparable only to
  other runs of this workflow, not to the README's gate numbers taken elsewhere.
- **Run**: [benchmark-snapshot #45771518709](https://github.com/chutian0610/Jolt/actions/runs/45771518709),
  2026-11-26, README conventions (`-wi 5 -i 5 -f 3`; `-prof gc` where noted), ten serial batches,
  all successful.
- Re-run via `gh workflow run benchmark-snapshot.yml` — snapshots are only meaningful as
  same-workflow comparisons.


### Direct bind (the facade's two read paths vs the tree path)

| benchmark | score (ops/s) | error | gc.alloc.rate.norm (B/op) |
|---|---|---|---|
| facadeParseReflective | 1,305,845 | ±28,230 | — |
| graphDirectParse | 634,962 | ±9,303 | — |
| graphJacksonParse | 595,364 | ±9,771 | — |
| graphReflectiveDirectParse | 626,808 | ±7,001 | — |
| graphReflectiveParse | 390,601 | ±3,121 | — |
| graphSpecializedParse | 396,585 | ±7,564 | — |
| pojoDirectParse | 3,511,712 | ±32,015 | — |
| pojoJacksonParse | 2,609,978 | ±37,220 | — |
| pojoReflectiveDirectParse | 3,485,309 | ±33,186 | — |
| pojoReflectiveParse | 1,273,682 | ±10,355 | — |
| pojoSpecializedParse | 1,805,832 | ±30,619 | — |

### Direct bind allocation (-prof gc)

| benchmark | score (ops/s) | error | gc.alloc.rate.norm (B/op) |
|---|---|---|---|
| baselineConstructOrder | 3,554,100 | ±84,289 | 960.00 |
| generatedDirectParseOrder | 651,316 | ±5,074 | 1,352.00 |
| reflectiveDirectParseOrder | 628,099 | ±9,523 | 1,552.00 |
| treeParseOrder | 360,211 | ±6,366 | 7,456.00 |

### Generated vs reflective (parse + write)

| benchmark | score (ops/s) | error | gc.alloc.rate.norm (B/op) |
|---|---|---|---|
| facadeParseReflective | 1,290,872 | ±20,839 | — |
| pojoPlanCachedParse | 3,137,135 | ±67,841 | — |
| pojoPlanCachedWrite | 2,755,100 | ±28,901 | — |
| pojoReflectiveParse | 3,160,234 | ±89,583 | — |
| pojoReflectiveWrite | 2,915,614 | ±54,708 | — |
| pojoSpecializedParse | 9,282,093 | ±248,134 | — |
| pojoSpecializedWrite | 4,543,927 | ±60,263 | — |
| recordPlanCachedParse | 2,401,105 | ±47,369 | — |
| recordPlanCachedWrite | 1,449,257 | ±15,398 | — |
| recordReflectiveParse | 2,349,164 | ±128,739 | — |
| recordReflectiveWrite | 1,448,040 | ±18,200 | — |
| recordSpecializedParse | 3,441,345 | ±58,044 | — |
| recordSpecializedWrite | 1,673,318 | ±41,217 | — |

### M8 tracer (the inline-collection A/B, -prof gc)

| benchmark | score (ops/s) | error | gc.alloc.rate.norm (B/op) |
|---|---|---|---|
| graphJacksonDatabind | 594,088 | ±9,228 | 2,952.20 |
| graphM8Off | 424,921 | ±2,826 | 2,832.04 |
| graphM8On | 648,691 | ±5,951 | 1,352.02 |
| graphM8OnFacade | 619,425 | ±10,161 | 1,352.02 |
| graphTreeReflective | 392,112 | ±3,283 | 6,992.04 |
| pojoJacksonAfterburner | 2,592,030 | ±46,600 | 1,160.06 |
| pojoM8Off | 2,469,277 | ±32,873 | 728.01 |
| pojoM8On | 3,537,779 | ±58,814 | 392.00 |
| pojoM8OnFacade | 3,480,079 | ±60,329 | 392.00 |
| pojoTreeReflective | 1,293,205 | ±43,232 | 2,840.01 |

### M8 gap dissection (the match chain vs text vs the doors)

| benchmark | score (ops/s) | error | gc.alloc.rate.norm (B/op) |
|---|---|---|---|
| graphBaselineConstruct | 3,155,481 | ±53,639 | — |
| graphFieldNameChain | 854,709 | ±10,294 | — |
| graphGeneratedDirect | 633,843 | ±10,399 | — |
| graphHandIdeal | 500,649 | ±16,973 | — |
| graphJacksonDatabind | 596,801 | ±10,399 | — |
| graphTokenDrain | 1,998,500 | ±18,584 | — |
| graphTreeReflective | 387,091 | ±6,296 | — |
| pojoBaselineConstruct | 45,202,721 | ±508,927 | — |
| pojoFieldNameChain | 4,048,452 | ±41,784 | — |
| pojoGeneratedDirect | 3,493,094 | ±57,474 | — |
| pojoHandIdeal | 2,977,432 | ±55,274 | — |
| pojoJacksonAfterburner | 2,614,774 | ±49,482 | — |
| pojoTokenDrain | 6,861,085 | ±346,506 | — |
| pojoTreeReflective | 1,291,855 | ±19,069 | — |

### M9 scanner dissection (per-token shapes)

| benchmark | score (ops/s) | error | gc.alloc.rate.norm (B/op) |
|---|---|---|---|
| graphDrain | 1,990,406 | ±15,168 | — |
| longNumbersDrain | 5,266,113 | ±67,653 | — |
| longStringsDrain | 931,298 | ±9,747 | — |
| pojoDrain | 6,929,227 | ±77,529 | — |
| pojoDrainUtf16 | 6,724,428 | ±125,835 | — |
| structuralDrain | 1,263,886 | ±22,628 | — |
| whitespaceHeavyDrain | 858,709 | ±8,640 | — |

### Competitors (the M1 bar)

| benchmark | score (ops/s) | error | gc.alloc.rate.norm (B/op) |
|---|---|---|---|
| dslJsonReadAny | 3,674,132 | ±68,456 | — |
| jacksonAfterburnerReadTree | 2,444,303 | ±31,607 | — |
| joltTokenDrain | 6,530,008 | ±131,178 | — |

### Strict parity (the lenient-features-off fast path)

| benchmark | score (ops/s) | error | gc.alloc.rate.norm (B/op) |
|---|---|---|---|
| strictDefault | 6,524,018 | ±85,630 | — |
| strictViaConfigSwitchesOff | 6,418,479 | ±48,629 | — |

### Zero-allocation contract (reused instance)

| benchmark | score (ops/s) | error | gc.alloc.rate.norm (B/op) |
|---|---|---|---|
| reusedInstanceTokenDrain | 6,173,078 | ±60,434 | 0.00 |

### jolt-simd (parked; --add-modules jdk.incubator.vector)

| benchmark | score (ops/s) | error | gc.alloc.rate.norm (B/op) |
|---|---|---|---|
| graphDrainScalar | 1,993,144 | ±14,753 | 0.00 |
| graphDrainSimd | 1,938,681 | ±28,384 | 0.00 |
| longStringsDrainScalar | 934,900 | ±10,363 | 0.01 |
| longStringsDrainSimd | 5,390,604 | ±71,372 | 0.00 |
| pojoDrainScalar | 6,907,493 | ±105,665 | 0.00 |
| pojoDrainSimd | 6,509,437 | ±37,810 | 0.00 |
