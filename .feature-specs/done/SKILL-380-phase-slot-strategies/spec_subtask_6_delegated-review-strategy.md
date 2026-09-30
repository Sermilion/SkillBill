# SKILL-380 Subtask 6 - code_review strategy delegated (DelegatedReviewStrategy)

Parent spec: [spec.md](spec.md)
Issue key: SKILL-380

## Scope

Resolves the behavioural half of investigation F-004. Adds the second `code_review`
strategy: the multi-agent review. The dominant pack's specialist subagents review in
parallel through `ParallelCodeReviewRunner`, and their findings are merged. This is the
delegated path of today's `skill-bill code-review`, and the review feature-task used
before commit `d6651d94b` (2026-09-11).

`phase:review mode:delegated` (subtask 8) selects it. The skeleton keeps `inline` for
every mode for now; enabling `delegated` there later is one selection change.

**Reproduce first.** `d6651d94b` removed this path from feature-task because "isolated
findings-only review buffered until exit and hung".

- Before writing the strategy, reproduce that behaviour with a test. Drive
  `ParallelCodeReviewRunner` from the feature-task launch path with a lane agent that
  writes nothing until it exits.
- Record the cause in `../../../runtime-kotlin/agent/decisions.md`.
- Every lane launch must use a bounded progress policy, meaning the read-only idle
  policy with the progress timeout that verify_findings uses, or an equivalent strategy
  on `AgentRunProcessRequest`. A lane that emits nothing before exit must settle as a
  lane failure within that bound.
- If the cause is outside the review runner (for example in the launcher's output
  capture), fix it at its owner. Do not work around it inside the strategy.
- If the hang cannot be reproduced or fixed, block this subtask. Subtask 8 depends on
  it.

**Strategy `DelegatedReviewStrategy` (id `delegated`, slot `code_review`).** In the
`code_review` slot package, beside `InlineReviewStrategy`.

- **review step:** runs through the strategy's own `PhaseRunner` instance. The step
  drives `ParallelCodeReviewRunner` in its delegated lane shape; lane and parent
  launches go through the shared `GoalRunnerSubtaskLauncher` port, as today.
  - The review target is a per-call fact: a commit range in the skeleton, or `HEAD`,
    `uncommitted`, or a commit for `phase:review`. The strategy does not branch on the
    composition.
  - Specialists are routed by the dominant pack; learnings are resolved as SKILL-379
    delivers them.
  - The step returns the uniform output. Its value carries the merged findings; the
    strategy decodes them for the existing `FINDINGS_VERIFICATION_INPUT` handoff, as
    `InlineReviewStrategy` does. The structured finding pipeline behind the runner is
    unchanged (parent Non-goals).
  - It does not edit files. Its review step policy is read-only and not mutating.
- **verify_findings and implement_fix:** composes the step classes subtask 3 extracted.
  Same `review_fix` edge, same cap, same remediation checkpoint. No duplication.
- **No copy of the inline strategy.** The two strategies differ only in the review
  step. Everything else the `code_review` slot shares comes from one composed
  collaborator both strategies hold, not from two copies. That covers step ids,
  verify and fix directives, carry-forward preparation, and status projection.
  Delegated-specific code lives in delegated-named classes, not in `InlineReview*`
  files.
- **Reusable from the first attempt:** the hang reproduction and the read-only idle
  bound on every lane launch (`READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES` in
  `runtime-ports`, `readOnlyPhase = true` on parent, coverage continuation,
  verification, integration, and adjudication lanes). Recheck them against the tree
  and keep the `decisions.md` entry if it still holds.
- **State:** review-pass reservation, carry-forward, and caps go through
  `PhaseRunState`.
- **Mode:** the DELEGATED `commit_focused_accounting` gate applies unchanged. The mode
  stays pinned on resume as today.
- **Selection:** register the strategy. Add the `phase:review` mode mapping
  (`delegated` → `delegated`) in subtask 8, not here. The skeleton mapping stays
  `inline` for every accepted mode.

**Docs.** In the ARCHITECTURE.md strategy table, add both review strategies, what each
does, and how the review mode selects them per composition.

## Acceptance Criteria

1. A test reproduces the findings-only hang against the pre-fix code path, and with the fix a lane that writes nothing until exit settles as a lane failure within the configured progress bound.
2. With the skeleton `code_review` selection entry replaced by `delegated` in a test binding, a feature-task run reviews through `ParallelCodeReviewRunner` in the delegated lane shape, the review step edits no files, and verified findings reach implement_fix through the existing `review_fix` edge. The run loop, skeleton, and other strategies are unchanged.
3. `DelegatedReviewStrategy` composes the same verify_findings and implement_fix step classes as `InlineReviewStrategy`.
4. The production skeleton selection still resolves every accepted mode to `inline`, and every subtask 1 fixture still matches.
5. `../../../agent/decisions.md` records the hang's cause and fix. ARCHITECTURE.md documents both review strategies and the mode mapping.
6. The two review strategy classes share their common behaviour through one composed collaborator, and neither restates the other's step list, directives, or projection.
7. `cd runtime-kotlin && ./gradlew check` passes at this subtask's commit.

## Non-goals

- Enabling `delegated` in the skeleton's production selection.
- The `review` definition and routing `skill-bill code-review` (subtask 8).
- Replacing `ParallelCodeReviewRunner`'s structured findings with prose (follow-up bundle).
- Re-review after implement_fix. The `review_fix` cap stays 1.
- New specialist areas, rubric changes, or learnings behaviour beyond SKILL-379.
- Changing `ParallelCodeReviewRunner` beyond the hang fix and the inputs this strategy needs.

## Dependency notes

- Depends on subtask 5 (the `code_review` package, shared step classes, and `PhaseRunState` are in place).
- Use the learnings delivery that is on the tree.
- Use the review-preparation type and `AgentRunLaunchFacts` termination that exist now.

## Validation strategy

The regressions to catch:

- the review hanging on a silent lane
- delegated review editing files
- findings not reaching implement_fix
- the skeleton selection changing

What covers them:

- the reproduction test
- a real-SQLite run-loop test with scripted lane agents under a test selection binding
- the subtask 1 fixtures under the production binding

Run `cd runtime-kotlin && ./gradlew check`, plus the engine, application, core, and
launcher suites. Run `bill-unit-test-value-check` on changed tests.

## Next path

Continue to `spec_subtask_7_run-loop-over-run-state.md`.

## Spec Path

.feature-specs/SKILL-380-phase-slot-strategies/spec_subtask_6_delegated-review-strategy.md
