# Slot baseline fixtures

These files record the runtime's behaviour before the SKILL-380 phase-slot
refactor. Later subtasks diff against them. Every difference must match an
entry in the
[fixture ledger](../../../../../../../.feature-specs/done/SKILL-380-phase-slot-strategies/spec.md#fixture-ledger).

Harness sources live in
`runtime-engine/src/test/kotlin/skillbill/engine/featuretask/slotbaseline/`.

## Bundles and the harness entry behind each

| Directory | Entry | What runs |
| --- | --- | --- |
| `standalone/` | `SlotBaselineFullRunCapture.standalone()` | A full feature-task runtime run with no goal continuation |
| `goal-child-build/` | `SlotBaselineFullRunCapture.goalChildBuild()` | Goal child for subtask 1 at the BUILD gate (`kotlinPackWithBuildGate`) |
| `goal-child-validate/` | `SlotBaselineFullRunCapture.goalChildValidate()` | Goal child for subtask 2 at the VALIDATE gate (`kotlinPackWithValidationGate`) |
| `goal-planning/` | `SlotBaselineGoalPlanningCapture.encodedFiles()` | `DefaultGoalPlanningSweep` preplan and plan over a two-subtask manifest, then `GoalPlanningLogService.log` |
| `code-review/` | `SlotBaselineCodeReviewCapture.encodedFiles()` | `PhaseRunEntry` over the review definition with the request `skill-bill code-review` builds (a scoped `HEAD^..HEAD` target, the pinned run and session ids), in INLINE and DELEGATED mode |
| `mcp-lifecycle/` | `SlotBaselineMcpLifecycleCapture.encodedFiles()` | `LifecycleTelemetryService`, the service the MCP tools call |
| `phase/` | `SlotBaselinePhaseRunCapture.encodedFiles()` | `skill-bill phase review` in INLINE and DELEGATED mode with no target, `skill-bill phase validation` over a gate that fails once and then passes, and `skill-bill phase plan`, `implement` and `pr` over the fixture agent outputs |

Full-run bundles run through `telemetryRunnerHarness` against real SQLite.
The phase launcher is `satisfiedAuditLauncher()`. The review step runs
`InlineReviewStrategy` with a `DefaultPhaseRunner` over a recording launcher. Each bundle
contains:

- `workflow-snapshot.json`: the `workflow_states` row, with `steps_json` and `artifacts_json` parsed
- `phase-records.json` and `ledger-entries.json`
- `handoff-projections.json` and `run-invariants.json`: the matching artifact families
- `feature-task-runtime-finished.json`: the last `feature_task_runtime_finished` outbox payload
- `prompts/<step>.txt`: every prompt the agents received, keyed by `phaseIdFromPrompt`. The review prompt goes in `prompts/review.txt`.

`goal-planning/` holds:

- `preplan-prompt.txt`
- `plan-prompts.json`, keyed by subtask id
- `shared-preplan-checkpoint.json`: `goal_shared_preplans` rows
- `plan-records.json`: `goal_subtask_plans` rows
- `planning-attempt-log.json`: progress events in persistence wire form
- `planning-log.json`

Both `code-review/` and the review captures in `phase/` run
`SlotBaselinePhaseRunHarness`. The review finds one Blocker in `src/Foo.kt`.
`verify_findings` confirms it, `implement_fix` rewrites the file, and the
re-review approves. Delegated mode runs `scriptedDelegatedReviewRunner`.

`code-review/` holds, for each mode:

- `inline-output.json` and `delegated-output.json`: the phase-run result fields the CLI prints, with the review result under `review_result`
- `review-runs-*.json`: every `review_*` table
- `review-telemetry-*.json`: `telemetry_outbox` rows

`mcp-lifecycle/` holds the outbox payloads for `quality_check_started`,
`quality_check_finished` and `pr_description_generated`.

`phase/` holds:

- `review-inline-output.json`, `review-delegated-output.json` and `validation-output.json`: the phase-run result fields the CLI prints, including the invocation id
- `review-inline-telemetry.json`, `review-delegated-telemetry.json` and `validation-telemetry.json`: `telemetry_outbox` rows
- `plan/`, `implement/` and `pr/`: for each phase, `output.json` (the printed result fields), `telemetry.json`
  (`telemetry_outbox` rows) and `prompts/<step>.txt`. The plan agent returns a decomposition package, so
  `plan/output.json` carries `spec_bundle` and `plan/spec-bundle.json` holds every bundle file by repo-relative
  path. The implement run reads a governed spec written under `.feature-specs/SKILL-380-phase-implement/`.
  These captures use the existing normaliser tokens; the manifest's absolute paths fall under
  `__NORMALIZED_REPO_ROOT__`.

## Pinned ids and clock

- Workflow id: `wftr-20260602-test-0001` (`WORKFLOW_ID`)
- Parent workflow id: `wfl-skill-380-parent`
- Review run ids: `rvw-20260602-120000-inln` and `rvw-20260602-120000-dlgt`
- Review session ids: `rvs-slot-baseline-inln` and `rvs-slot-baseline-dlgt`
- SQLite clock: fixed at `2026-06-02T12:00:00Z` (`SlotBaselineFullRunCapture.sqliteClock`)

Every capture gets a fresh repo root, `skillbill-slot-baseline-repo-*`, and a
fresh home, `skillbill-slot-baseline-home-*`, both under `java.io.tmpdir`.
They are deleted afterwards.

## Normaliser tokens

`SlotBaselineNormalizer` replaces values that differ between runs.

By map key:

- `__NORMALIZED_TIMESTAMP__` replaces non-null values of `started_at`, `updated_at`, `finished_at`, `first_started_at`, `timestamp`, `heartbeat_at`, `expires_at` and `recorded_at`
- `__NORMALIZED_DURATION__` replaces non-null values of `duration_millis`, `duration_ms` and `duration_seconds`
- `__NORMALIZED_SESSION_ID__` replaces a non-blank string `session_id`
- `__NORMALIZED_REVIEW_RUN_ID__` replaces a string `review_run_id`
- `__NORMALIZED_EVENT_UUID__` replaces a string `event_uuid`

In any string, in this order:

1. `__NORMALIZED_REPO_ROOT__` replaces `<tmpdir>/skillbill-slot-baseline-repo-<digits>`. Both the absolute and the real-path form of tmpdir match.
2. `__NORMALIZED_TEMP_PATH__` replaces any other first-level path under tmpdir.
3. `__NORMALIZED_TIMESTAMP__` replaces ISO instants (`2026-06-02T12:00:00Z`, with optional fractional seconds).
4. `__NORMALIZED_TIMESTAMP__` replaces SQLite datetimes (`2026-06-02 12:00:00`).
5. `__NORMALIZED_SHA64__` replaces 64-hex-digit hashes.
6. `__NORMALIZED_SHA40__` replaces 40-hex-digit hashes.
7. `__NORMALIZED_REVIEW_RUN_ID__` replaces `rvw-<8 digits>-<6 digits>-<suffix>`.
8. `__NORMALIZED_INVOCATION_ID__` replaces a phase-run invocation id, `phr-<uuid>`.
9. `__NORMALIZED_REVIEW_SESSION_ID__` replaces a generated review session id, `rvs-<uuid>`. `skill-bill phase review`
   pins no session id, so the delegated phase capture carries a generated one.

## Canonicalisations

- Map keys are sorted.
- Enums are written as their `wireValue`, or as `name` when they have none.
- JSON strings stored in SQLite columns are parsed and embedded as JSON.
- Structured values are pretty-printed JSON with a trailing newline.
- Text files are normalised, trailing whitespace is trimmed, and one final newline is added.
- If a step prompts the agent more than once, the prompts are joined with `\n---\n` in launch order.

## Steps with no prompt

Some steps have no file under `prompts/`:

- `commit_push` runs inside the runtime and launches no agent.
- A gate that passes on the first try launches no repair agent.

Only steps that sent a prompt to an agent get a file.

## Output format

The CLI renderers `renderPlanningLog` and `writeParallelReviewResult` belong
to runtime-cli, and runtime-engine tests cannot reach them. So instead of
rendered CLI text, these fixtures record:

- `planning-log.json`: the payload `GoalPlanningLogCommand` prints under JSON output
- `*-output.json`: the `PhaseRunResult` fields the CLI prints, and the `ParallelCodeReviewResult` fields that `writeParallelReviewResult` prints

## Regenerating

Run the capture twice, from `runtime-kotlin`, each time in a fresh Gradle
invocation:

```
SKILL_BILL_SLOTBASELINE_CAPTURE=1 ./gradlew :runtime-engine:test --tests skillbill.engine.featuretask.slotbaseline.SlotBaselineCaptureTest
```

After the second run, `git status --porcelain` on this directory should match
the first. The writer skips this README and replaces every bundle directory.
When the variable is not set, the capture test is skipped. Two tests always
run:

- `SlotBaselineCaptureTest.consecutiveCapturesProduceByteIdenticalTree` checks that the capture is deterministic.
- `SlotBaselineFixtureTest` checks that the committed files match a live capture byte for byte.

## Using the fixtures in later subtasks

To compare a live value with a committed file, encode the value with
`SlotBaselineJson.encode` and call:

```kotlin
SlotBaselineFixtureCompare.assertBytesEqual("featuretask/slotbaseline/standalone/phase-records.json", actual)
```

If the bytes differ, the call fails and prints a line diff. Any change the
ledger expects must be regenerated in the subtask that the ledger names.

## Re-baselined changes

- Subtask 4 (skeleton definitions and quality gate): `standalone/prompts/validate.txt`
  and `goal-child-validate/prompts/validate.txt` carry the validate uniform-output
  directive (settle completed, or blocked with a progress or no_progress verdict) in
  place of the `validation_passed` directive. This is the ledger's allowed validate
  prompt change. Every other file is unchanged.
- Subtask 8 (phase review and validation): `code-review/` routes through
  `PhaseRunEntry` over the review definition, so both modes find, verify and fix.
  `phase/` is new.
- Subtask 9 (phase plan, implement and pr): `phase/plan/`, `phase/implement/` and
  `phase/pr/` are new. Every existing file is unchanged.
- Standalone implementation removal: `phase/implement/` and its capture are
  removed. Workflow implementation and simplification fixtures remain.
- Subtask 12 validation: the re-captures from subtasks 5 to 11 never reached a commit, so the
  tree still held the subtask 4 files and `SlotBaselineFixtureTest` failed. Those subtasks' drift
  (the phase prompt output-contract text, `code-review/` routed through `PhaseRunEntry`, and the
  new `phase/` bundle) is committed here. Subtask 12's own diff changes no captured behaviour.
- SKILL-383 subtask 2 (retire listed skills): prompt, spec-writer and operation fixtures
  carry the retired-skill name replacements (`skill-bill phase review`,
  `skill-bill phase validation`, `operation:<name>`) and the skill text moved verbatim
  into directive resources. Goal-child plan and preplan prompts do not load the
  feature-spec directive and carry only the name replacements. Telemetry, lifecycle,
  ledger and workflow JSON files are unchanged.
- SKILL-385 subtask 1 (project authoring discipline) changes only prompt-policy text: the
  authoring discipline section in implement, simplify, audit_implement_fix, implement_fix and
  inline review prompts; the removed audit compile exception; the reconciled
  compile/build/test ownership lines; and the validate independent-discovery sentence.
  Telemetry, lifecycle, ledger, phase-record, handoff and workflow JSON files are unchanged.
- SKILL-387 (prose phase output and spec handoff): every phase prompt ends with the plain-prose
  final-output section in place of the JSON envelope contract, preplan asks for prose instead of a
  stuffed digest, and plan authors its bundle on disk. Phase records, handoff projections and
  workflow snapshots carry the prose value and summary. The `phase/plan/` capture runs in a repo
  without the seeded SKILL-380 spec, because plan blocks rather than overwrite an existing parent spec.
