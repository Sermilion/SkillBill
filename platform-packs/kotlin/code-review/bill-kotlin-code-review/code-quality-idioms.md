# Kotlin code-quality idioms

Use these examples when a rewrite preserves behavior and makes the code easier to follow.

- For a nullable string with an empty fallback, prefer `value.orEmpty()` over `value ?: ""` when both branches mean the same thing.
- Use `emptyList()` for an immutable empty result. Do not allocate a mutable list that callers cannot or should not change.
- Choose `let` when transforming a non-null receiver into a result, `run` when computing a result in a receiver scope, `also` for a visible side effect that returns the receiver, and `apply` for configuring an object that returns the receiver. Replace a chain when nested receivers or implicit return values make data flow hard to trace.
- Use a data class for one coherent state snapshot when its fields are read and updated together. Use a sealed type or enum for mutually exclusive lifecycle states so contradictory combinations cannot be represented.
- Keep validation, error behavior, resource closing, and serialized values intact when applying an idiom.
