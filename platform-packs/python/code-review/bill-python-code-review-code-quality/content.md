---
name: bill-python-code-review-code-quality
description: Python data modelling, context managers, readable comprehensions, focused functions, and detected framework conventions.
internal-for: skill-bill
---

## Focus

Review changed Python code for avoidable maintenance work. Follow the project's Python version, typing policy, packaging, and detected framework conventions. Report a concrete location, the maintenance cost, and a behavior-preserving change; no reachable failure is required.

## Ignore

- Behavioral defects owned by failure specialists; route correctness, security, persistence, reliability, and accessibility findings to those lanes.
- Issues already enforced by the repository's configured formatter, linter, compiler, or static-analysis rules.
- Style preferences without a concrete maintenance cost or a behavior-preserving rewrite.

## Applicability

Use this lane for every routed Python pack review, regardless of whether changed code has a reachable failure. Inspect changed code only and follow repository conventions before applying general language preferences. Zero findings is valid when the code is clear.

## Project-Specific Rules

### Idioms and helper clarity rules

- Require a `dataclass` or repository typed model when callers repeatedly read the same `dict` keys; repeated shape interpretation adds maintenance and allows inconsistent defaults. Preserve accepted input keys.
- Require file, lock, and session ownership to remain in a `with` block; hiding context exit in a helper obscures cleanup and exception tracing. Keep the same exception propagation.
- Require list comprehensions such as `[item for item in items]` to express one clear transformation; nested side effects make mutation ambiguous and add control-flow tracing. Use a loop with named intermediates while preserving order.
- Require Django, FastAPI, Flask, or CLI boundary guidance only when that framework exists in the project, using its matching construct such as `transaction.atomic`, `Depends`, `request`, or `argparse`; a second validation or dependency pattern adds ownership ambiguity. Keep the existing endpoint contract.

### Cohesive state and duplication rules

- Require `None` to remain distinct from empty values where validation or serialization depends on it; collapsing branches hides input distinctions and creates contract maintenance. Preserve every outcome.
- Require `async with` to own async sessions and locks for the full operation; moving awaits outside the context hides resource lifetime and coroutine lifecycle. Preserve cancellation and cleanup order.
- Require repeated conversions such as `datetime.fromisoformat` to share a function only when applicable and after implementations drift; duplicate normalization creates behavior drift and review effort. Keep one-use conversions local and preserve conversion failures and order.
- Require async helpers to expose session ownership through the project's session/context-manager API or existing `transaction.atomic` boundary; hidden scope makes rollback and exception tracing harder. Preserve transaction extent and exception behavior.

### Dead code, responsibility, and naming rules

- Require parsing, policy, and I/O to split only when each has a distinct responsibility, using boundaries such as `json.loads` or `Path.read_text`; generic helpers add indirection without reducing tracing. Keep existing validation and errors.
- Require dead imports, private functions, and feature branches to be removed after checking `__main__` or detected CLI and framework registration; stale branches misstate supported behavior. Retain reachable plugin hooks.
- Require `await` and `asyncio.create_task` ownership to remain visible when moving code out of a coroutine; hidden task boundaries add lifetime tracing. Preserve cancellation propagation and context-manager scope.
- Require variable names to identify domain roles instead of `data` or `obj`; vague names increase mutable dictionary and model-field tracing. Keep public field and serialized names stable.

- For every finding, identify the changed-code location, the concrete maintenance cost, and a behavior-preserving rewrite. Preserve validation, errors, resource closing, and serialized values.
- Report every code-quality finding as Minor; never Blocker or Major.
