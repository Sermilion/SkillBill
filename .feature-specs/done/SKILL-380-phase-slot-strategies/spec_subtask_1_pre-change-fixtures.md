# SKILL-380 Subtask 1 - Pre-change behaviour fixtures

Parent spec: [spec.md](spec.md)
Issue key: SKILL-380

## Scope

Capture, on the unchanged tree, every fixture the rest of the bundle diffs against.
This commit changes no production code.

Build on the existing real-SQLite run-loop test setup (for example
`FeatureTaskRuntimeRunStateReconstructionTest`) and the goal-runner test factories
(`GoalRunnerSharedTestFactory`, `WorkflowGoalRunnerStoreTestFixtures`). There is no
single shared harness today; extract the setup into one engine test fixture if two
fixture captures need it. Pin every
clock, id generator, and random source the harness already pins. If a captured field
is not deterministic across two runs, pin it through the existing test seam or strip
it from the fixture with a named normaliser, and list each normalised field in the
fixture README. Do not add a production seam for this.

**Durable and telemetry fixtures.** For a standalone run and for a goal child (one
non-final child that builds, and the final child that validates):

- workflow snapshot
- phase records
- ledger entries
- handoff projections
- run invariants
- `skillbill_feature_task_runtime_finished` payload

**Prompt fixtures.** The composed prompt text for every step of the standalone run and
the goal child, and the goal planning sweep's preplan and plan prompts for the same
inputs, plus the goal planning records: the shared preplan checkpoint, per-subtask plan
records, the planning attempt log, and `skill-bill goal planning-log` output.

**Standalone review and telemetry fixtures.** The output, `review_runs` rows, and
review telemetry of `skill-bill code-review` for a fixed target in inline and delegated
mode, and the event payloads the MCP `quality_check_started`, `quality_check_finished`, and
`pr_description_generated` tools record for today's documented arguments. Subtasks 8, 10, and 11 compare against them.

**Layout.** Put fixtures under the engine test resources in one
`featuretask/slotbaseline/` directory with a README that names each fixture, the
harness that produces it, the normalised fields, and the fixture ledger in
[spec.md](spec.md). Add one comparison helper that later subtasks call. It fails with
a unified diff.

## Acceptance Criteria

1. The fixtures listed in Scope exist for the standalone run, the non-final goal child, and the final goal child, and the goal planning preplan and plan prompts exist.
2. Two consecutive runs of the capture produce byte-identical fixtures.
3. A test compares the live tree against every fixture and passes on this commit.
4. The README lists every normalised field and links the parent fixture ledger.
5. No file under any `src/main` directory changes.

## Non-goals

- Any production change, including a new test seam.
- Phase-run or operation fixtures (the subtasks that add those definitions or operations capture them).

## Dependency notes

- No dependency. Every later subtask depends on this one through subtask 2.

## Validation strategy

Catch: a fixture that captures nondeterministic bytes, so a later subtask fails for no
behaviour change; a fixture captured from a partial run. Cover with the two-run
determinism check and the comparison test. Run `cd runtime-kotlin && ./gradlew check`
plus the engine suite. Run `bill-unit-test-value-check` on the new tests.

## Next path

Continue to `spec_subtask_2_slot-skeleton-and-dispatch.md`.

## Spec Path

.feature-specs/SKILL-380-phase-slot-strategies/spec_subtask_1_pre-change-fixtures.md
