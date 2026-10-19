# SKILL-431 - run-qa-once-per-goal-after-all-subtasks-and-before-the-pr

## Mode

decomposed

## Intended Outcome

Run QA once per goal, after all subtasks and before the PR.

Every goal child currently runs `audit -> optional qa -> review -> validate -> write_history` (docs/runtime-command-guidance.md). Per-subtask QA is the wrong granularity. A subtask is often a slice that is not observable in the running app on its own (data layer, an unwired ViewModel), so QA either finds nothing or flags missing UI a later subtask adds. It also reserves a device once per subtask, and it runs before review and validate, so it tests code those phases may still change.

Operator requirements (authoritative, preserved verbatim from intake):

- Goal children run `audit -> review -> validate -> write_history`. They no longer run qa or qa_fix.
- Goal finalization runs `qa -> commit_push -> pr -> monitor`. QA runs once, after every child has completed, against the goal branch HEAD.
- QA covers the parent spec acceptance criteria across all subtasks.
- QA findings enter a goal-level `qa_fix`, followed by `validate` (full project validation gate), then QA again. Three repair attempts, as today. If findings remain after the third attempt, the goal blocks before `commit_push` and nothing is pushed.
- The dispatcher token `qa:auto|off`, the `--qa` flag and `SKILL_BILL_QA_SELECTION` keep their meaning. The persisted selection moves to the goal level. `off` settles goal-level qa as skipped with `disabled_policy` without probing a device or launching an agent. A resume requesting a different selection than the recorded one is still refused.
- A repository whose routed pack declares no `qa_capability` settles goal-level qa as an `unsupported_target` skip, as today.
- In-flight compatibility: a child that already recorded a qa step keeps its recorded step list (the runtime never switches a recorded child to a different step list). Only newly started children use the new list. A goal resumed with such children must not run QA twice for no reason; define the rule.
- Single-subtask goals behave the same: one child, then goal-level QA before the PR.
- Desktop and status/progress output show goal-level QA as a finish-pipeline step.
- Docs: update docs/runtime-command-guidance.md, skills/skill-bill/content.md (Token Forwarding section describing QA order) and runtime-kotlin/ARCHITECTURE.md where they describe QA placement.

## Settled design decisions

- **Goal QA skeleton.** A new in-memory skeleton (working name `GOAL_QA`, id `goal-qa`) composes the steps `qa`, `qa_fix`, `validate`. It runs through `QaStrategy` (policy AUTO/OFF via `QaExecutionLifecycle`), not the standalone `phase:qa` harness. A goal-level loop `qa -> qa_fix -> validate -> qa` uses a new backward edge from validate to qa that composes only in that skeleton. The existing `qa -> qa_fix` edge keeps cap 3 with BLOCK on exhaustion.
- **Child step list.** `GOAL_CHILD` gains semantic revision 4 without the QA slot. Revision 3 (with QA) stays in `SkeletonDefinition.entries` so recorded children resolve their recorded revision unchanged. New child plans record no QA selection.
- **Selection.** The effective QA selection is persisted on the parent goal. First run stores the request's value, else AUTO. A goal with no recorded parent selection (in-flight goals) inherits the first child's recorded plan selection, then the request, then AUTO. A resume requesting a different value is refused.
- **In-flight rule.** Goal QA is skipped without launching an agent or probing a device when either (a) every subtask with a workflow ran a QA-composed plan (GOAL_CHILD revision 3) and recorded a QA outcome, or (b) a goal-level QA outcome for the parent workflow is already `passed` or `skipped` and its tested tree identity equals the current repository tree identity. Every other goal, including mixed goals, runs goal QA once.
- **Standalone runs.** Non-goal `STANDALONE` feature runs keep revision 3 with child-level QA. The new placement applies to goal runs. Implement confirms whether any full `skill-bill <KEY>` run still reaches `STANDALONE`; docs describe goal-level placement either way.
- **Finish pipeline chips.** Status shows `qa, qa_fix, validate, pr, monitor, monitor_fix`. `commit_push` stays unlisted, as today.
- **PR body.** One goal-level QA line from the parent's recorded outcome, plus per-subtask lines only for legacy children that recorded one.

## Acceptance Criteria

1. Newly started goal children compose `audit -> review -> validate -> write_history` with no qa or qa_fix step, while children that already recorded a QA-composed plan keep their recorded step list.
2. Goal finalization runs one goal-level QA run, after every child has completed and before `commit_push`, on the goal's PR head branch, scoped to the parent spec acceptance criteria, for single-subtask and multi-subtask goals alike.
3. Goal-level QA findings drive `qa_fix -> validate (full gate) -> qa` up to three repair attempts, and remaining findings block the goal before `commit_push` with nothing pushed.
4. The QA selection (`qa:auto|off`, `--qa`, `SKILL_BILL_QA_SELECTION`) is persisted on the goal, `off` and a pack without `qa_capability` settle goal-level qa as `disabled_policy` and `unsupported_target` skips, a resume with a different selection is refused, and the in-flight rule prevents a second QA run.
5. Status/progress output and the desktop show goal-level QA in the finish pipeline, and docs/runtime-command-guidance.md, skills/skill-bill/content.md, and runtime-kotlin/ARCHITECTURE.md describe the goal-level QA placement.

## Constraints

- Supplied requirements are authoritative and need no tracker lookup.
- The working tree carries unrelated uncommitted edits (runtime-engine `validation/ValidationGateResolver.kt`, `ValidationGateRoutingTest.kt`, staged `QaCapabilityResolverTest.kt`). They do not belong to this task. Do not revert them or fold them into this feature's commits.

## Non-Goals

- `operation:verify qa:on` keeps its own report-only QA placement.
- Standalone `phase:qa` (`QaStandalonePhaseRun`, DEVICE_QA harness) is unchanged.
- No change to the QA receipt schema, device leasing, or pack `qa_capability` resolution.
- No new QA skip reason in the receipt vocabulary.

## Subtasks

1. Goal-level QA run in finalization (skeleton, loop, gate, blocking, outcome, PR line, in-flight skip rule).
2. Goal children without QA and goal-level selection persistence.
3. Finish-pipeline status, desktop chips, and docs.

## Validation Strategy

The validate phase runs the repository's full validation gate, including the desktop-tray build if the pack gate does not already cover it, and verifies every acceptance criterion above against the subtask specs.
