# Parse strictly RFC 8259 by default; lenient features are explicit opt-in switches

A performance-first parser lives or dies by trust in its correctness, so Jolt's default semantics are exactly RFC 8259 — nothing more. Extensions (comments, trailing commas) are lenient features: individually enabled via explicit configuration, and implemented so that a disabled feature costs zero on the fast path.

## Consequences

- No JSON5/Jsoniter-style permissiveness out of the box; users who want it must ask.
- Lenient feature checks must be hoisted out of hot loops, not interleaved with strict scanning.
