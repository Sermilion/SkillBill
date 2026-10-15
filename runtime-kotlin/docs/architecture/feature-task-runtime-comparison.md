# Feature-Task-Runtime Comparison Procedure

Issue: SKILL-65 (Subtask 5)
Status: Experimental — evaluation procedure only
Date: 2026-06-03

> **ARCHIVED (2026-08-09) — historical decision record. Do not treat as live.**
>
> The promote-vs-prose comparison procedure in this document is **retired** and
> the prose engine it compared against **no longer exists**. SKILL-175 removed
> the retired prose orchestrator and made **runtime the single
> feature execution engine**; there is no prose path to run, so no comparison
> can or should be executed, and this document imposes **no dual-maintenance
> obligation** on the codebase. It is retained only as the archival record of
> the SKILL-65 promote decision it fed. Any references below to
> `feature_implement_workflow_update`, or a prose
> orchestrator describe a retired surface and must not be read as current
> product behavior.

## Purpose

This is the documented, reproducible procedure for evaluating the experimental
`feature-task-runtime` capability against the retired prose orchestrator by
running the **same governed spec** through both and
capturing comparable evidence. It is a **procedure only** — running it does not
promote, retire, or re-route the feature workflow, and it must not weaken any
review, audit, or validation gate to make either side look favorable. The
decision the evidence feeds is the promote/kill criterion recorded
authoritatively in
[`.feature-specs/SKILL-65-experimental-feature-task-runtime/spec.md`](../../../.feature-specs/SKILL-65-experimental-feature-task-runtime/spec.md).

## Why a comparison is needed

`feature-task-runtime` is unproven. Its hypothesis (from the parent spec) is that
moving the phase loop into the runtime delivers **materially better
observability and state reliability** while keeping **output quality on par**
with the in-context prose orchestrator. That hypothesis is only testable by
running the same input through both paths and comparing ground-truth evidence,
not impressions.

## Preconditions

1. A single **governed spec** to use as the fixed input for both runs. Prefer a
   representative MEDIUM single-spec feature (not a decomposed goal), because both
   surfaces accept a single spec path. Record its issue key and spec path; do not
   change the spec between the two runs.
2. A clean starting tree (or a disposable branch per run) so the two runs do not
   contaminate each other's diff. Run each path against an equivalent starting
   state.
3. The same launched agent for both runs where possible, so output-quality
   differences are attributable to orchestration rather than to the model. Record
   the agent id used for each phase.
4. Do not modify any gate (review, audit, validation, schema) for the duration of
   the comparison.

## Run A — retired prose orchestrator baseline

Run the governed spec through the standard feature-task flow. This is the prose
orchestrator: an agent reads the skill and decides phase sequencing, completion,
and downstream context; step transitions and timings are recorded only when the
agent voluntarily calls `feature_implement_workflow_update`.

Capture, for each phase:

- **Phase timings** — the `started`/`finished` timestamps and any duration the
  agent self-reported into the workflow ledger. Note explicitly that these are
  **self-reported**: the runtime did not measure them.
- **Observability completeness** — which of {start time, finish time, duration,
  resolved agent id, status, attempt count} are present, and which are absent or
  only inferable. Note that presence here depends on agent discipline.
- **State reliability and resume** — interrupt the run mid-phase, then resume.
  Record whether resume restarted from the correct step, whether any completed
  work was lost or re-run, and whether the gate would have admitted an
  empty/fabricated artifact (presence-only gate).
- **Token / session cost** — total tokens and session/turn count for the run, read
  from the agent session, since the whole loop runs inside one agent context.
- **Output quality** — preserve the final diff/artifacts for the quality method
  below.

## Run B — `feature-task-runtime` (experimental runtime-driven path)

Run the **same** spec through the runtime-driven phase loop. The runtime owns the
loop, launches one agent per phase, validates each phase output against its
schema gate, and persists per-phase state.

```bash
skill-bill <issue_key>
```

The full run plans through intake, preplan and plan, then executes the one-subtask
bundle.

Read-only status and resume mirror the goal commands:

```bash
skill-bill feature-task status <workflow_id> --format json
skill-bill feature-task resume <workflow_id> <issue_key> <spec_path> --agent <agent>
```

Capture, for each phase, from the **runtime-owned** durable state rather than
from any agent self-report:

- **Phase timings** — `startedAt`, `finishedAt`, and `durationMillis` from each
  `FeatureTaskRuntimePhaseRecord`
  (`runtime-domain/.../taskruntime/model/FeatureTaskRuntimePersistenceModels.kt`).
  These are **runtime-minted**: the application layer stamps every timestamp and
  passes it into the effect-free domain model, so the runtime — not the agent —
  measured them.
- **Observability completeness** — the same field set
  {`startedAt`, `finishedAt`, `durationMillis`, `resolvedAgentId`, `status`,
  `attemptCount`} is structurally present on every per-phase record, plus the
  append-only phase ledger
  (`FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY`) of
  `FeatureTaskRuntimePhaseLedgerEntry` rows (start / resume / retry /
  fix-loop-iteration / blocked / complete actions with monotonic sequence
  numbers). This ledger and the per-phase records are the **runtime-owned
  observability source** for Run B.
- **State reliability and resume** — interrupt the run mid-phase, then `resume`.
  Record that resume deterministically skips already-complete phases from the
  durable per-phase records, restores upstream outputs into downstream briefings,
  and that a missing required upstream output blocks loudly instead of launching a
  phase blind. Record that an invalid (schema-failing) phase output blocks the run
  rather than advancing — the gate is **validity**, not just presence.
- **Token / session cost** — total tokens and per-phase session/turn count.
  Because each phase is launched as its own agent subprocess, cost is the sum over
  phase launches; record per-phase as well as total so the per-phase fan-out cost
  is visible against Run A's single context.
- **Output quality** — preserve the final diff/artifacts for the quality method
  below.

## Observability: runtime-owned vs self-reported

The central comparison. For each captured field, classify the source:

| Field | Run A (retired prose baseline) | Run B (`feature-task-runtime`) |
|-------|------------------------------|-------------------------------|
| start / finish timestamps | agent self-reported via workflow update | runtime-minted on `FeatureTaskRuntimePhaseRecord` |
| duration | inferred from self-reported times | `durationMillis` measured by the runtime |
| resolved agent id | implicit (one agent context) | `resolvedAgentId` recorded per phase |
| status / attempt count | agent-declared | runtime-recorded per phase + per-phase ledger |
| phase advance gate | presence of artifact | schema validity of phase output |
| attempt/event audit trail | none beyond workflow updates | append-only `FeatureTaskRuntimePhaseLedgerEntry` |

The claim under test is that every Run B field is **ground truth** (runtime
measured/owned) whereas the corresponding Run A field is **self-reported** (agent
told the ledger). Record any field where this distinction does not hold.

## State-reliability and resume comparison

For both runs, perform an identical interruption (interrupt during the same phase)
and resume. Record:

- whether resume restarted from the correct phase/step;
- whether any completed work was lost or silently re-run;
- whether a corrupt/partial state was detected loudly (Run B loud-fails on a
  present-but-malformed records artifact and on a missing required upstream
  output) or silently tolerated;
- whether an invalid artifact could advance the pipeline (Run A presence gate vs
  Run B schema gate).

## Token / session cost comparison

Record total tokens and session/turn counts for each run. For Run B also record
the per-phase breakdown, since the runtime launches one agent per phase and the
fan-out cost (re-establishing context per phase) is the expected cost trade-off
against Run A's single shared context. State the measured delta plainly; do not
adjust either side.

## Output-quality comparison method

Schema validation gates *validity*, not *quality* — it cannot judge whether the
produced code is good. Quality is therefore compared **out of band** with a fixed,
pre-registered method so the comparison is not gamed:

1. Hold the launched model constant across both runs so quality deltas are
   attributable to orchestration, not model strength.
2. Compare the two final diffs against the **same** governed spec's acceptance
   criteria: for each criterion, mark satisfied / partial / missing for each run.
   This criterion-to-evidence mapping is the primary quality measure.
3. Run the identical project gate (`(cd runtime-kotlin && ./gradlew check)`,
   `skill-bill validate`, and the rest of the maintainer set) against each run's
   result; a run that only passes by weakening a gate is disqualified, not
   credited.
4. Record reviewer findings (severity-tagged) from an unmodified code review of
   each run's diff. Do not merge or cross-review the two runs (out of scope).
5. Summarize quality as a per-criterion table plus the gate outcomes; do not
   reduce it to a single subjective score.

## Recording the result

Capture the evidence as a dated comparison record (per-phase timing tables, the
observability source classification, the resume outcomes, the cost deltas, and
the per-criterion quality table). This evidence is the input the promote/kill
criterion consumes; the decision rule itself and who/what decides live
authoritatively in the parent spec
([`spec.md`](../../../.feature-specs/SKILL-65-experimental-feature-task-runtime/spec.md)),
not here.

## Reproducibility checklist

- [ ] Same governed spec (issue key + path) used for both runs, unchanged between.
- [ ] Equivalent clean starting state per run.
- [ ] Same launched model/agent across runs (record any per-phase agent).
- [ ] No gate (review/audit/validation/schema) modified during the comparison.
- [ ] Run B evidence read from `FeatureTaskRuntimePhaseRecord` + the phase ledger,
      not from agent self-report.
- [ ] Identical interruption point used for both resume tests.
- [ ] Quality compared via the fixed per-criterion method above, model held
      constant.
