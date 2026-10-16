# No JDK internal APIs — VarHandle over Unsafe

Jolt does not touch `sun.misc.Unsafe`, `String.value` reflection, or any other JDK-internal API, even though compact-strings surgery can buy a few percent. `VarHandle` on `byte[]`/`long[]` provides the same wide reads through a supported surface. This keeps Jolt runnable across JDK updates (internals get fenced off release by release) and avoids the maintenance tax of carrying an Unsafe fallback path.
