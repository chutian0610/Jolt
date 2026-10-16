# Three layers on a zero-dependency core

Jolt is layered like jackson-core/databind: `jolt-core` (streaming parser + writer, zero dependencies, single jar), a mutable tree layer, and a POJO/record binding layer. Only the core is a milestone-1 deliverable; nothing is built on top until the core meets its performance bars. Optional performance modules (SIMD scanning, bytecode-generated binding) live outside the core and can never become its dependencies.

## Consequences

- The core cannot grow feature dependencies later without breaking its published promise.
- Tree and binding decisions can be revisited cheaply; core API mistakes cannot.
