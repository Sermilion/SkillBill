# SKILL-382 Subtask 2 - Checklist operations

Parent spec: [spec.md](spec.md)
Issue key: SKILL-382

## Scope

Moves three in-session rubric skills into operations. No GitHub, no tags, no
feature-verify workflow.

- `operation:unit-test-value-check` — today's `bill-unit-test-value-check` value
  test, same default scope (staged/unstaged). Read-only report. No confirmation.
- `operation:feature-guard` — today's `bill-feature-guard` rollout rules. It edits
  code, and today's skill confirms scope and rollback safety before implementing. The
  proposal step reports the switch point, the scope, and the rollback plan. Edits run
  only on `confirm:<token>` (subtask 1 gate).
- `operation:feature-guard-cleanup` — today's `bill-feature-guard-cleanup`. The
  proposal step reports the flag, the winning path, the dependents found, and today's
  stabilization questions (flag on for everyone, no dependent flags, no pending A/B
  analysis) as a checklist the operator confirms. Removal runs only on
  `confirm:<token>`.

Each has pre/run/post, and every agent step runs through `PhaseRunner` (subtask 1
contract). Authored rules move into runtime-owned prompt fragments the operation loads.
Guard proposals anchor on HEAD and the current branch. Add the three routes to the
`/skill-bill` dispatcher. Do not delete `../../../skills` trees (SKILL-383).

## Acceptance Criteria

1. The three operations are registered and runnable via `skill-bill operation:<name>` and `/skill-bill operation:<name>`. Unknown names still fail as in subtask 1.
2. `operation:unit-test-value-check` with no scope reviews current staged and unstaged changes; with no unit tests in scope it says so and stops. It changes no file.
3. `operation:feature-guard` and `operation:feature-guard-cleanup` apply the same checklists as today's skills (single switch point, rollback when flag off, cleanup removes the flag). Their first invocation changes no file and returns `awaiting_confirmation`. Only `confirm:<token>` edits.
4. Subtask 1 tests and SKILL-380 fixtures still match. The three old skills still work.

## Non-goals

- Deleting the `../../../skills/bill-unit-test-value-check` and guard directories (SKILL-383).
- Remote operations (subtasks 3 and 4).

## Dependency notes

- Depends on subtask 1. Independent of subtask 3.

## Validation strategy

Catch: default scope walking the whole repo; a guard operation skipping the
single-switch-point rule; a guard operation editing before confirmation. Cover with a
no-tests-in-scope test, one fixture per guard operation, a no-edit-before-confirm
assertion per guard operation, and the dispatcher routing test. Run
`cd runtime-kotlin && ./gradlew check` plus CLI. `bill-unit-test-value-check` on
changed tests.

## Next path

Continue to `spec_subtask_3_pr-review-fix-operation.md`.

## Spec Path

.feature-specs/SKILL-382-runtime-operations/spec_subtask_2_checklist-operations.md
