# SKILL-380 Subtask 8 - Short definitions, in-memory state, and phase:review / phase:validation

Parent spec: [spec.md](spec.md)
Issue key: SKILL-380

## Scope

Resolves the engine half of F-008. A phase run is a skeleton definition with fewer
slots, driven by the same run loop, strategies, and `PhaseRunner` as a full run. The
only difference is the `PhaseRunState` implementation. There is no separate
executor.

**In-memory `PhaseRunState`.** Implements every operation subtask 7 put behind the
port, for one invocation:

- keeps step outputs, verdicts, loop counters, and checkpoints in memory; writes no
  workflow, session, phase-record, ledger, or run-invariant row, and no git checkpoint
  ref
- answers skeleton-only questions (goal review passes, goal continuation) as absent,
  the same way the durable state answers them for a standalone run
- records a review run through the existing runtime-owned review persistence when a
  review step completes, as standalone `skill-bill code-review` does today; the durable
  state keeps today's behaviour and records none (parent "Review-run recording")
- supports no resume

**Short definitions (runtime-domain) and their selection entries.**
- `review`: `code_review`, all its steps (review, verify_findings, implement_fix), as in
  a full run. Selection by review mode: `inline`
  (default) → `inline`, `delegated` → `delegated`; today's `auto` resolves to `inline`.
- `validation`: `quality_gate`. Selection: `pack-build`.

**Entry.** `PhaseRunRequest` (repo root, definition id, optional intake, optional
instructions and per-step scope, review target and mode) and `PhaseRunResult`
(completed, blocked, or failed, with the run's final value). `PhaseRunEntry` resolves
the definition, builds the in-memory state and per-call facts, and calls the same run
loop entry `FeatureTaskRuntimeRunRequest` uses. It contains no step, launch, or
definition-specific code. Unknown definition ids fail loudly; `commit_push` is not a
definition, so `phase commit_push` is a usage error.

**CLI.** A `phase` subcommand, `skill-bill phase <name> [<intake>] [key:value …]`
(`mode:`, `target:`). The root command has subcommands and no positional arguments, so
a root-level `phase:` token cannot parse. runtime-cli reaches `PhaseRunEntry` through a
new `RuntimeComponent` accessor, and `PhaseRunRequest`, `PhaseRunResult`, and
`PhaseRunEntry` are added to `RuntimeEngineInboundApiTest`'s pinned engine inbound API.
Telemetry (decided 2026-09-27): a phase run adds no telemetry event and changes no
payload. It emits today's review events and `quality_check_started` /
`quality_check_finished` unchanged, and no record carries `workflow_id`; the run-loop
events stay dropped (the in-memory facts use the `NONE` event sink). The CLI prints the
invocation id. Phase-run lifecycle events would bump the telemetry `contract_version`,
which every captured fixture payload holds, so they wait until a phase needs them.

**Settlement.** The in-memory state supplies no settlement target, so steps get no
MCP settlement directive and settle through the minimal final object that subtask 5
made readable for any step name (parent "Phase input and output").

**`phase review` (self-sufficient: finds and fixes).** Runs the `review` definition:
the whole `code_review` slot, exactly as a full run does. It edits the worktree, creates
no commit, and prints what remains after the slot finishes. No skill-bill artifacts
beyond today's review-run record. Intake optional.

- `mode:inline` (default): `InlineReviewStrategy` reviews the target in its own
  session and fixes Blocker and Major findings there; verify_findings and implement_fix
  then handle what remains, as in a full run.
- `mode:delegated`: `DelegatedReviewStrategy` runs the multi-agent review (its review
  step edits nothing); verify_findings and implement_fix then fix the verified
  findings.
- The `review_fix` loop and its cap apply per run, as they apply per subtask in a full
  run. Checkpoints stay in the in-memory state; no git checkpoint ref is written.
- Target: `HEAD`, `uncommitted`, or a commit sha/name. Omitted: `uncommitted` if the
  worktree is dirty, else `HEAD`. Every dirty path is owned.
- Mode: `inline|delegated`, default `inline`; `auto` accepted and resolves to `inline`.
  Any other value is a usage error naming `inline` and `delegated`. Unknown targets are
  a usage error.

**`skill-bill code-review` routes through `phase review`.** Compare against its subtask 1
fixture. Make the command build a `PhaseRunRequest` for the `review` definition, keeping
its flags. The old direct call into the review service is deleted, so there is one
review path. This changes behaviour in both modes: today `skill-bill code-review`
reports findings only. Inline (one `bill-code-review-inline` worker) becomes
`InlineReviewStrategy` plus the rest of the slot, and delegated gains verify_findings
and implement_fix after the multi-agent review. Both now fix. Re-baseline the fixture
in this commit (parent fixture ledger) and state the change in `AGENTS.md`, the CLI help,
and the `/bill-code-review` skill text (that skill calls this command and stays until
SKILL-383). Then census production callers of `ParallelCodeReviewRunner`'s inline lane
shape and of `bill-code-review-inline`, and record the result in `agent/decisions.md`
for SKILL-383.

**`phase validation` (mutates the worktree, prints its report).** Runs the `validation`
definition: `pack-build` runs the dominant pack `validation_gate` collect-all, triage,
repair sessions, and the cache-bypassing collect-all. This is the runtime-owned form of
today's `bill-code-check` repair window. It inherits `pack-build`'s triage and
repair-turn cap instead of `bill-code-check`'s single repair session; record that in
`agent/decisions.md`. A missing pack gate fails as today (`MissingValidationGateError`).
Intake optional. It does not commit. The runtime emits `quality_check_started` and
`quality_check_finished` with the payload and `skill` label `bill-code-check` emits
today (subtask 1 fixture).

Extend the subtask 5 launch-port rule and the subtask 7 durable-store rule to the entry
package. Instructions prepend, no size cap, apply to every step unless scoped. No resume.
No listed-skill changes.

**Fixtures.** Capture the printed output and telemetry records of both definitions
under `featuretask/slotbaseline/phase/`.

## Verification ownership

Implement and audit can't run builds, tests, or the fixture capture. So:
- Audit checks that the code and tests behind criteria 1–7 exist and assert the
  behaviour. It does not list a criterion as remaining just because nothing has run.
- Validate re-captures the fixtures with
  `cd runtime-kotlin && SKILL_BILL_SLOTBASELINE_CAPTURE=1 ./gradlew :runtime-engine:test --tests skillbill.engine.featuretask.slotbaseline.SlotBaselineCaptureTest`.
  This re-baselines the `skill-bill code-review` output fixtures and first captures the
  phase-run fixtures. Validate reviews the diff against the parent fixture ledger: the
  code-review diff shows only the ledger's `skill-bill code-review` change, and every
  other skeleton fixture is unchanged. It then runs `./gradlew check`.
  `SlotBaselineFixtureTest` in `check` fails on stale fixtures.

## Acceptance Criteria

1. A `review` or `validation` run inserts no row into `feature_task_workflows` or `feature_task_runtime_sessions`, writes no phase record, ledger entry, run invariant, or git checkpoint ref, and goes through the same run loop entry as a durable run.
2. No production class runs a phase outside the run loop: `PhaseRunEntry` contains no step, launch, or definition-specific code, and the launch-port and durable-store rules cover its package and fail on synthetic violations.
3. `skill-bill phase review` runs the whole `code_review` slot with `InlineReviewStrategy` when `mode` is omitted, `inline`, or `auto`, and with `DelegatedReviewStrategy` with `mode:delegated`. In both modes it finds, verifies, and fixes, changes files but creates no commit and no git checkpoint ref, and applies the `review_fix` cap. It resolves an omitted target to `uncommitted` when dirty and `HEAD` otherwise. Unknown targets and modes are usage errors.
4. `skill-bill code-review` builds a `PhaseRunRequest` for the `review` definition and no longer calls the review service directly. A test asserts that both modes still write a `review_runs` record and review telemetry of the subtask 1 fixture's shape. The re-capture of its output fixtures runs in validate (see Verification ownership).
5. `skill-bill phase validation` runs `pack-build` against the dominant pack gate and emits `quality_check_started` / `quality_check_finished`, and a test asserts both payloads match the subtask 1 payload fixture. A repo whose dominant pack has no `validation_gate` fails with the existing typed error. `skill-bill phase commit_push` is a usage error.
6. The CLI reaches `PhaseRunEntry` only through pinned inbound API types, and `RuntimeEngineInboundApiTest` pins `PhaseRunRequest`, `PhaseRunResult`, and `PhaseRunEntry`.
7. A phase run emits no telemetry event beyond the review and quality-check events, no phase-run record carries `workflow_id`, and the telemetry contract version is unchanged. No listed skills are added or removed.
8. ARCHITECTURE.md documents skeleton definitions, the in-memory state, and the `phase` CLI for review and validation.

## Non-goals

- The `plan`, `implement`, and `pr` definitions (subtask 9).
- The dispatcher (subtask 12). Operations (SKILL-382). Deleting `skills/bill-*` (SKILL-383).
- Resume for short definitions, worktree locks, plugin UI, or a second run loop.
- Changing `skill-bill code-review`'s flags.
- Operator-facing strategy or definition selection.

## Dependency notes

- Depends on subtask 7 (the loop runs over `PhaseRunState`) and subtask 6 (the
  `delegated` strategy). Recheck CLI registration anchors, `CliComponent` /
  `RuntimeComponent` accessors (including generated `InjectCliComponent`), and the
  `CodeReviewCommand` entry at start.

## Validation strategy

Catch: a durable row or checkpoint ref from a phase run; an omitted target picking HEAD
on a dirty tree; review not fixing verified findings in either mode; review committing or writing a checkpoint ref; a mode
selecting the wrong strategy; `skill-bill code-review` losing its review-run record;
definition-specific code in the entry; commit_push accepted. Cover with SQLite and
git-ref assertions, dirty/clean target tests, a mode-to-strategy test, a find-and-fix test
per mode with scripted agents, a no-commit and no-ref assertion, the code-review
fixtures, the guards, and a usage-error test for commit_push. Run
`cd runtime-kotlin && ./gradlew check` plus engine, core, CLI, application,
infra-sqlite. `bill-unit-test-value-check` on changed tests.

## Next path

Continue to `spec_subtask_9_phase-plan-implement-pr.md`.

## Spec Path

.feature-specs/SKILL-380-phase-slot-strategies/spec_subtask_8_phase-review-and-validation.md
