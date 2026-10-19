# SKILL-431 subtask 3 - finish-pipeline status, desktop chips, and docs

Parent spec: `.feature-specs/SKILL-431-run-qa-once-per-goal-after-all-subtasks-and-before-the-pr/spec.md`

## Scope

Show goal-level QA in status/progress output and the desktop, and update the three docs that describe QA placement.

Areas (from the preplan digest):

- `runtime-engine/.../engine/work/IdeStatusService.kt` `GOAL_FINISH_PIPELINE` (around line 1174) and its use as `goalFinishPipeline`.
- `desktop-tray/feature/detail/.../DetailPresentation.kt` and `DetailContent.kt`, if they map step ids to labels.
- `docs/runtime-command-guidance.md` (the goal-child paragraph near line 173), `skills/skill-bill/content.md` Token Forwarding (lines 123-128), `runtime-kotlin/ARCHITECTURE.md` (QA skeleton text near line 1306 and any goal-child placement text).

## Acceptance Criteria

1. `IdeStatusService` lists the goal finish pipeline as `qa, qa_fix, validate, pr, monitor, monitor_fix`, and the goal projection marks the goal QA step current from the goal-finalization progress events the goal QA gate records.
2. The desktop detail view renders labeled chips for the goal-level `qa`, `qa_fix`, and `validate` finish-pipeline steps.
3. `docs/runtime-command-guidance.md` describes goal children as `audit -> review -> validate -> write_history` and goal finalization as `qa -> (qa_fix -> validate -> qa, three attempts) -> commit_push -> pr -> monitor`, with the goal-level selection, the `off` and `unsupported_target` skips, and the in-flight rule, while its `phase qa` and `operation verify` text is unchanged.
4. The Token Forwarding section of `skills/skill-bill/content.md` describes QA running once per goal after all subtasks and before the PR, with findings blocking the goal before `commit_push`.
5. `runtime-kotlin/ARCHITECTURE.md` describes the `goal-qa` skeleton and its loop, `GOAL_CHILD` revision 4 without QA, and revision 3 retained for in-flight children.

## Non-Goals

- Adding `commit_push` as a chip.
- Changing standalone `phase:qa` or `operation:verify` docs.

## Dependency Notes

- Depends on subtasks 1 and 2: the progress events and step ids come from subtask 1, and the child revision documented here comes from subtask 2.
- Assumptions for implement to confirm:
  - The desktop renders whatever pipeline the runtime sends. If it has no per-step-id label map, AC2 is met by the runtime's step labels (the workflow graph already labels `qa`, `qa_fix`, `validate`) and no desktop code change is needed.
  - Grep `ARCHITECTURE.md` for every QA mention; the preplan found only line 1306.
  - If implement finds that full `skill-bill <KEY>` runs still reach `STANDALONE`, the docs state that child-level QA remains for non-goal runs.

## Validation Strategy

Validate runs the full repository gate and the desktop-tray build. Implement and audit only read the tree. Doc criteria are checked by reading the files.

## Test Obligations

- Goal status projection with a goal-QA `PHASE_STARTED` progress event shows `qa` as the current finish-pipeline step and earlier chips incomplete (catches the gate's events not reaching status). AC1.
- Update existing finish-pipeline expectations in `IdeStatusServiceGoalProjectionTest` / `DetailPresentationTest` to the new list; no new desktop test unless a label map changes. AC1, AC2.

## Implementation Details

Ordered tasks. Each names the criteria it serves, what it touches, and its tests.

1. Finish pipeline list (AC1).
   - Touch `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/work/IdeStatusService.kt`: change `GOAL_FINISH_PIPELINE` (around line 1174) from `listOf(PHASE_PR, PHASE_MONITOR, PHASE_MONITOR_FIX)` to `listOf(PHASE_QA, PHASE_QA_FIX, PHASE_VALIDATE, PHASE_PR, PHASE_MONITOR, PHASE_MONITOR_FIX)`. Import `PHASE_QA`, `PHASE_QA_FIX`, `PHASE_VALIDATE` from `FeatureTaskRuntimePhaseWorkflowDefinition` the same way the existing `PHASE_PR`/`PHASE_MONITOR` constants are imported.
   - Do not add `commit_push` (non-goal).
   - Follow `goalFinishPipeline` (around line 616) to the code that picks the current finish step from goal progress events. Confirm it matches on the event `stepId` against the list members with no allowlist that excludes non-monitor ids. If such a filter exists (for example a check that the step is `pr` or `monitor*`), widen it to every member of `GOAL_FINISH_PIPELINE`.
   - Assumption for implement to confirm: the goal QA gate from subtask 1 records `PHASE_STARTED`/`PHASE_COMPLETED` events with `operationKind = "goal_finalization"` and `stepId`/`workflowPhase` set to `qa`, `qa_fix`, or `validate`, mirroring `DefaultGoalRunnerCiMonitor`. If subtask 1 uses a different operation kind, the status projection must accept it too. Adjust the projection, not the gate.
   - Tests: in `runtime-kotlin/runtime-engine/src/test/kotlin/skillbill/engine/work/IdeStatusServiceGoalProjectionTest.kt`, update existing finish-pipeline expectations to the new list. Add one test: a goal with all children completed and a goal-finalization `PHASE_STARTED` event for `qa` projects `qa` as the current finish step, with `pr`/`monitor` not complete. Realistic bug caught: the gate's events never reaching status, so the desktop shows the goal stuck before `pr`. Update `IdeStatusServiceTest.kt` and `runtime-core/src/test/.../di/core/IdeStatusReadSnapshotConcurrencyTest.kt` only where they assert the old three-step list.

2. Desktop chips (AC2).
   - Read `desktop-tray/feature/detail/src/commonMain/kotlin/dev/skillbill/tray/feature/detail/DetailPresentation.kt` and `DetailContent.kt`.
   - If they render the pipeline the runtime sends and take labels from the payload (or the runtime step labels "Phase 4d: Mobile QA", "Phase 4e: QA Fix", and the validate label), make no code change. AC2 is met by task 1.
   - If they hold a per-step-id label or icon map keyed on `pr`/`monitor`/`monitor_fix`, add entries for `qa`, `qa_fix`, `validate` using short labels consistent with the existing ones (for example "QA", "QA fix", "Validate"). Also check that any "known finish steps" filter in the desktop includes the new ids.
   - Tests: update `desktop-tray/feature/detail/src/commonTest/.../DetailPresentationTest.kt` only where it asserts the old finish pipeline. Add a test only if a label map changed: one case that a `qa` finish step renders its label rather than the raw id or nothing.

3. `docs/runtime-command-guidance.md` (AC3).
   - Rewrite the paragraph near line 173 that starts "Every non-skipped goal child runs `audit -> optional qa -> review -> validate -> write_history`". New content:
     - Goal children run `audit -> review -> validate -> write_history` (plus `build` when selected) and no longer run `qa` or `qa_fix`.
     - Goal finalization runs once after every child completes: `qa -> commit_push -> pr -> monitor`, against the goal branch HEAD (the final stack branch for stacked goals), covering the parent spec acceptance criteria.
     - Findings loop: `qa_fix -> validate` (full project validation gate) `-> qa`, three repair attempts. Findings left after the third attempt block the goal before `commit_push`, and nothing is pushed.
     - Selection: `qa:auto|off`, `--qa`, `SKILL_BILL_QA_SELECTION` keep their meaning. The goal records the selection. A resume asking for a different one is refused. `off` settles goal QA as skipped with `disabled_policy` without probing a device or launching an agent. A routed pack with no `qa_capability` settles it as an `unsupported_target` skip.
     - In-flight rule, stated as subtasks 1/2 implement it: recorded children keep their recorded step list (`GOAL_CHILD` revision 3 children still run child-level QA). Goal QA is skipped when every child ran and recorded child-level QA, or when a goal-level QA outcome already passed or skipped on the current tree. Otherwise goal QA runs once.
   - Leave the `phase qa` text (near line 128) and the `operation verify` text (near line 155) unchanged.
   - Assumption for implement to confirm: if full `skill-bill <KEY>` runs still reach the `STANDALONE` skeleton, add one sentence that non-goal runs keep child-level QA.

4. `skills/skill-bill/content.md` Token Forwarding (AC4).
   - Rewrite lines 123-128 ("In the full run, QA runs after audit and before review ... before the runtime blocks the subtask"): in a goal run, QA runs once per goal after all subtasks and before the PR. Findings go through `qa_fix`, full validation, and QA again, up to three attempts. Remaining findings block the goal before `commit_push`. Keep the existing token text for `qa:auto|off` unchanged.
   - Same STANDALONE assumption as task 3.

5. `runtime-kotlin/ARCHITECTURE.md` (AC5).
   - Grep the file for every `qa`/`QA` mention. The preplan found the QA skeleton text near line 1306. Update every place that describes QA placement.
   - Document: the `goal-qa` in-memory skeleton (slots `QA`, `QUALITY_GATE`; steps `qa`, `qa_fix`, `validate`; runs through `QaStrategy`, not the standalone device-QA harness), its loop (`qa --QA_FINDINGS--> qa_fix`, cap 3, block on exhaustion; `qa_fix -> validate -> qa` through the new backward edge, using the loop id subtask 1 chose), and that goal finalization runs it before `commit_push`.
   - Document `GOAL_CHILD` revision 4 without the `QA` slot for new children, and revision 3 kept in `SkeletonDefinition.entries` so recorded in-flight children resolve their recorded step list.
   - Use the exact constant and loop-id names from subtasks 1 and 2 as they landed in code. The names in this plan are the preplan's recommendations.

Constraints.
- Touch only the files named above plus test files that assert the old finish-pipeline list.
- Do not change QA receipt schema, device leasing, `qa_capability` resolution, standalone `phase:qa`, or `operation:verify` docs.
- Leave the unrelated uncommitted edits (`ValidationGateResolver.kt`, `ValidationGateRoutingTest.kt`, `QaCapabilityResolverTest.kt`) alone.
- Do not compile, build, or run tests in implement. Validate owns the full gate and the desktop-tray build.
- Docs must describe the behavior subtasks 1 and 2 shipped. If their code differs from this plan (names, in-flight rule details), the docs follow the code.

## Next Path

```bash
skill-bill goal SKILL-431
```
