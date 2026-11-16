# The direct read's bodies stay a controlled mirror: one algorithm, two transcriptions, gates hold them level

The direct bind's two implementation grades — the reflective binder (`DirectBinder`, interpreting a `Plan`) and the generated binder (`DirectBodies`, emitting straight-line bytecode) — carry the same read algorithm (the document-order field loop, the name match, the unknown-field note, the seen/path/noteError/pop rhythm, the declaration-order sweep) as two hand-kept transcriptions: Java control flow in one, bytecode instructions and frame declarations in the other. Equivalence is held not by shared code but by the five gate matrices (values, messages, and `$` paths asserted cell-by-cell across the arms).

**Decision: keep the controlled mirror.** Do not build a direct-read IR. The semantic layers are already single-sourced — the error/state/sequence calls (`DirectSupport`), the type-shape classification (`BindingShape`), the error text (`BindErrorText`), and the generated forms' scaffolding (`defineDirectForms`, the carrier/scratch entry) — so what the mirror duplicates is control flow expressed in two different media, not knowledge.

## Considered Options

- **A shared read IR** (`DirectRecipe`, interpreted by the reflective arm and compiled by the emitter): rejected. The IR must describe operations down to "read a long from the parser" granularity — a small instruction set whose interpreter adds a dispatch level to every value read on the reflective arm's hot path. That arm exists to beat the tree path; a scheduling layer risks spending its margin (zero-allocation might hold; latency is the point). The emitter, meanwhile, would compile from the IR where today's bytecode is hand-tuned (the M8 inline loops' frame and stack discipline in particular), with a real chance the generated code gets worse. The payoff — one transcription per new value type — insures against a future that has already arrived: the value-type spectrum is complete and stable since M8.
- **A shared loop-skeleton DSL**: rejected. The skeleton's semantic calls are already shared; what remains is medium difference on a ~30-line loop. The DSL's fixed cost exceeds the duplication it removes.

## Consequences

- A new value type is still transcribed twice (the reflective arm's read + the emitter's arm) and pinned with a gate cell. The transcription discipline stays honest because each PR's golden-bytes harness and the gates make drift loud.
- Re-opening conditions, written down so a future review does not re-litigate from scratch: (1) the value-type spectrum grows again (a wave of new bindable shapes); (2) the reflective arm's performance budget is restructured for another reason and an IR scheduler can be funded from the same work; (3) the gate matrices' maintenance cost is demonstrated to scale with value-type count faster than the two transcriptions do.
- The gates are the contract's enforcement, not a workaround: any change to either transcription runs the full matrix, and a red cell is the drift alarm.
