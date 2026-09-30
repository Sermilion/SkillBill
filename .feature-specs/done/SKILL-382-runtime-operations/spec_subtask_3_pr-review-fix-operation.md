# SKILL-382 Subtask 3 - operation:pr-review-fix

Parent spec: [spec.md](spec.md)
Issue key: SKILL-382

## Scope

Migrates `bill-pr-review-fix` to `operation:pr-review-fix`, with today's two stages on
the subtask 1 confirmation gate.

**Analysis (first invocation).** Fetch unresolved threads through GraphQL (not flat
`gh pr view --comments`), classify them, propose options per thread, and store the
matrix as the proposal. Print it and return `awaiting_confirmation`. No code change, no
reply, no push. Proposal anchors, measured by the runtime: HEAD sha, current branch, PR
head sha, and the set of unresolved thread ids.

**Execution (second invocation).** `confirm:<token>` plus a selection of today's
shape:

- `select:all-recommended`
- `select:<thread>=<option>,…`
- `select:fix-all-unresolved`

The runtime checks selected thread ids against the measured thread set. Execution
fixes, replies, records learnings, optionally writes a follow-up spec, runs the quality
gate, and pushes only when the operator enabled push (default off, as today).
Protected-branch refusal and `--force-with-lease` rules stay as today.

`scope:analyze-only` prints the matrix and issues no token.

Analysis and execution agent steps run through `PhaseRunner` (subtask 1 contract). The
quality gate in execution runs the `validation` definition as a step.

Inputs stay PR number, URL, or current-branch PR. Pre: resolve PR, refuse if none.
Post: telemetry. No feature-task workflow row. Add the route to the `/skill-bill`
dispatcher, including `select:` forwarding.

## Acceptance Criteria

1. `skill-bill operation pr-review-fix` performs analysis only, returns `awaiting_confirmation`, and does not change the worktree, post replies, or push. With `scope:analyze-only` it issues no token.
2. Execution runs only on `confirm:<token>` with a selection, and touches only the selected threads.
3. A token whose PR head or unresolved-thread set changed since analysis is refused.
4. Unresolved/outdated thread flags come from GraphQL, not the flat comments list.
5. `/skill-bill operation:pr-review-fix` routes to the CLI and forwards `select:`. Subtasks 1–2 operations still register, and SKILL-380 fixtures still match. `../../../skills/bill-pr-review-fix` still works.

## Non-goals

- Deleting `../../../skills/bill-pr-review-fix` (SKILL-383). `operation:verify`. Changing the
  GitHub reply/learnings product.

## Dependency notes

- Depends on subtask 1. Independent of subtask 2.

## Validation strategy

Catch: mutate-before-select; using flat comments; fixing an unselected thread; a stale
token executing. Cover with a selection-gate test, a fixture that unresolved threads
stay unresolved on analysis, a selected-subset test, and a stale-PR-head refusal. Run
`cd runtime-kotlin && ./gradlew check` plus CLI. `bill-unit-test-value-check` on
changed tests.

## Next path

Continue to `spec_subtask_4_verify-operation.md`.

## Spec Path

.feature-specs/SKILL-382-runtime-operations/spec_subtask_3_pr-review-fix-operation.md
