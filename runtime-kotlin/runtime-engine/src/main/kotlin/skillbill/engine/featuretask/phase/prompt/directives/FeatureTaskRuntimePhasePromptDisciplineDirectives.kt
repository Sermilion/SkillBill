package skillbill.engine.featuretask.phase.prompt.directives

fun mutatingPhaseIdempotencyDirective(mutating: Boolean): String {
  if (!mutating) {
    return ""
  }
  return """
    ## Mutating-phase idempotency contract
    You are given intended-state plan inputs (the target the repository should reach) plus the
    CURRENT working tree, which may already carry some or all of those changes from a prior
    attempt that was interrupted mid-edit. Converge the tree to the target state; treat any change
    that is already applied as a no-op and NEVER blindly re-apply it (no duplicated edits, appended
    blocks, or re-created files). This phase may be re-entered or resumed after a crash, so it must
    be safe to run again: reconciling to target, not re-applying from scratch. Before finishing,
    verify every changed file is at its intended state and describe that reconciled end-state in
    your final prose.
    """.trimIndent()
}

fun minimalismDisciplineDirective(mutating: Boolean): String {
  if (!mutating) {
    return ""
  }
  return """
    ## Minimalism discipline (reuse before write)
    Understand the problem first,
      then climb the ladder. Trace the real flow end to end — every file and caller the change touches —
      before picking a rung. Laziness that skips comprehension ships a confident wrong fix. Read fully, then be lazy.

    The ladder — stop at the first rung that holds:
    1. Does this need to exist at all? Speculative need = skip it and say so in one line (YAGNI).
    2. Already in this codebase? Reuse the helper, util, type, or pattern that already lives here.
    3. Stdlib does it? Use it.
    4. Native platform feature covers it? Prefer platform primitives over a new dependency or custom layer.
    5. An already-installed dependency solves it? Use it. Never add a new dependency for what a few lines can do.
    6. Can it be one line? One line.
    7. Only then: the minimum code that works.
    Two equal rungs both work → take the higher one and move on.

    Rules:
    - No unrequested abstractions: no interface with one implementation, no factory for one product,
      no config for a value that never changes.
    - No scaffolding "for later"; later can scaffold for itself.
    - Deletion over addition. Boring over clever.
    - Shortest working diff once the problem is understood; the smallest change in the wrong place is a second bug.
    - Between two equal-size options, take the one correct on edge cases.

    Bug fix =
      root cause, not symptom. Before editing,
        grep every caller of the function you are about to touch. Fix once where all callers route through;
        patching only the path the report names leaves sibling callers broken.

    Never simplify away: input validation at trust boundaries, error handling that prevents data loss,
      security measures, accessibility basics, anything the spec explicitly requires,
        and skill-bill's own governed contracts — typed errors, loud-fail seams, contract-version constants,
        parity tests, and validator-backed rules are never over-engineering.

    Deliberate simplifications with a known ceiling get a comment: `shortcut: <ceiling>,
      <upgrade trigger>` (e.g. `// shortcut: global lock,
        per-account locks if throughput matters`). Exception to comments-are-a-last-resort: `shortcut:`
        markers are permitted because they record a non-obvious why (ceiling and upgrade trigger).
    """.trimIndent()
}

fun testValueDisciplineDirective(applies: Boolean): String {
  if (!applies) {
    return ""
  }
  return """
    ## Test-value discipline (every test must earn its cost)
    Tests are a recurring cost: every future change to the code they touch pays for them in
    maintenance and reasoning tokens. Write few, high-value tests; never mirror code 1:1 with tests.
    - Before writing a test, name the realistic bug it would catch — a concrete wrong behavior that
      fails this test while the rest of the suite passes. If you cannot, do not write the test.
    - Concentrate coverage on critical paths: money and quantities, data integrity and persistence
      atomicity, auth and tenant isolation, external contracts and serialization, concurrency and
      recovery, irreversible side effects. Trivial glue on non-critical paths needs no test; say so
      instead of writing one.
    - Assert observable behavior at boundaries, never implementation structure: no mock-interaction
      verification without an outcome assertion, no call-ordering assertions, no implementation
      logic duplicated inside the test.
    - One strong test per rule or branch; no sibling tests re-covering the same branch with
      different literals.
    - When planning, emit test_obligations only for behaviors that pass this bar, each tied to an
      acceptance criterion or a named realistic bug; an empty test_obligations list is a valid
      outcome for a task.
    - Never remove or weaken regression coverage tied to a real past bug, and never treat governed
      parity tests or validator-backed rules as omission candidates — the minimalism carve-outs
      apply to tests too.
    """.trimIndent()
}
