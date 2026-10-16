# Number promotion: long/double fast paths with BigDecimal upgrade

JSON numbers are parsed on fast paths — integers as `long`, decimals via a hand-written `double` conversion (fast_double_parser-style) — and promoted to `BigDecimal` only when a value overflows or needs more precision. The tree and binding layers retain the original text so conversion can be deferred or redone at a wider type.

## Considered Options

- `BigDecimal` everywhere: correct but disqualifyingly slow for the small-message workload.
- `double` only: fast, but silently corrupts values like `9007199254740993`.
