# Platform: Java 21 baseline, HotSpot C2 first, native-image out of scope

Jolt targets Java 21 LTS (records, switch pattern matching, mature VarHandle, wide production coverage) running on HotSpot C2, and its optimization playbook trusts the JIT: narrow inline-friendly methods, exception-table-based cold paths, flat iteration over recursion. GraalVM native-image and Android are explicitly non-goals for now — AOT runtimes invalidate most of that playbook, and supporting them would drag the hot paths toward the lowest common denominator.

## Consequences

- Code may lean on C2 behaviors (devirtualization of the single final node type, escape analysis) that are not guaranteed under AOT.
- Revisiting this for native-image later means re-benchmarking everything, not flipping a flag.
