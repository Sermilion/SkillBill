# SKILL-380 - Phase slot strategies

## Mode

decomposed

## Intended outcome

The feature-task runtime runs a fixed skeleton of phase slots, in this order:

1. pre-plan
2. plan
3. implementation
4. audit
5. code review
6. quality gate
7. write history
8. commit/push
9. pull request

Today how a phase runs is decided by comparing phase ids in shared code:

- the run loop's `when (run.phaseId)`
- domain sets such as `MUTATING_PHASES`
- phase-keyed prompt tables

Two real strategy switches exist, but neither is modelled as a strategy. Quality-gate
selection rewrites transitions. Review is a DI-global driver.
[investigation.md](investigation.md) has the census of phase-id references, launch
shapes, the existing switches, and the 2026-09-11 review replacement.

After this bundle the structure is compositional, with one way to run a phase:

- **`PhaseRunner`** is one interface with one implementation. It is not
  phase-specific: there is no review runner, verification runner, or validation
  runner. Every strategy gets its own instance, and any step can be handed to it. It is
  the only launch path for every step.
- **Phase input and output** are the same small shape for every step, written for an
  AI reader, not for the runtime (see "Phase input and output" below).
- **`PhaseStrategy`** is one slot's behaviour. It runs its steps through its
  `PhaseRunner` and reads and writes run state only through `PhaseRunState`.
- **`SkeletonDefinition`** is an ordered subset of the nine canonical slots, in
  canonical order. A slot always runs all of its steps, so every phase is
  self-sufficient. Every run is a definition driven by the one run loop. The full feature run is the nine-slot definition; a phase
  run is a definition with fewer slots.
- **`PhaseRunState`** is the port the run loop and strategies use for all run state.
  The durable implementation writes today's workflow rows, records, ledger, run
  invariants, and checkpoints, and supports resume. The in-memory implementation
  writes none of them and does not resume. Which one a run gets is the only difference
  between a feature run and a phase run.
- **Strategy selection is code.** One `PhaseStrategySelection` binding, next to the
  registry, maps each definition's slots to strategies. A developer swaps a strategy by
  editing that binding. The one operator input that picks a strategy is the review
  mode flag that exists today; there is no config key or profile.
- **Operations** (`operation:<name>`, SKILL-382) and the single skill catalog
  (SKILL-383) build on these parts in follow-up bundles.

Definitions after this bundle (all declared in code in runtime-domain):

| Definition | Slots | Strategies | State | Entry |
| --- | --- | --- | --- | --- |
| `standalone` | all nine | one per slot; `code_review` → `inline`; `quality_gate` from today's selection value | durable | `FeatureTaskRuntimeRunRequest` |
| `goal-child` | all but `pull_request` | as `standalone`; `quality_gate` from the goal continuation stamp | durable | `FeatureTaskRuntimeRunRequest` |
| `plan` | preplan, plan | `agent-preplan`, `agent-plan` | in-memory | `skill-bill phase plan` |
| `review` | code_review | `mode:inline` (default) → `inline`; `mode:delegated` → `delegated` | in-memory | `skill-bill phase review` |
| `validation` | quality_gate | `pack-build` | in-memory | `skill-bill phase validation` |
| `implement` | implementation | `implement-then-simplify` | in-memory | `skill-bill phase implement` |
| `pr` | pull_request | `pr-description` | in-memory | `skill-bill phase pr` |
| `goal-planning` | preplan, plan | `agent-preplan` once per goal; `goal-plan-fan-out` runs the `agent-plan` step per active subtask | goal-planning (today's planning checkpoints; resumable) | `DefaultGoalPlanningSweep.prepare` |

A definition's transitions are derived from the canonical graph: forward order is its
steps in canonical order, an entry gate applies only when both its steps are in the
definition, and a backward edge only when both ends are. The derived graph of
`standalone` and `goal-child` equals today's exactly.

**Review strategies.**
- `inline` (`InlineReviewStrategy`, default) is today's `last-commit-fix`, renamed. One
  agent reviews a target in its own session with no subagents, fixes Blocker and
  Major findings in that session, and reports what remains with a verdict. The target
  is a per-call fact: the full run passes the subtask's last commit, as today;
  `phase review` passes `HEAD`, `uncommitted`, or a commit.
- `delegated` (`DelegatedReviewStrategy`) is the multi-agent review: the dominant
  pack's specialist subagents review in parallel through `ParallelCodeReviewRunner`.
  Its review step does not edit; verify_findings and implement_fix then repair the
  verified findings.
- **Review is self-sufficient.** The `code_review` slot finds and fixes with either
  strategy, in every definition. `phase review` runs the whole slot, exactly as a full
  run does, and stops after implement_fix.
- **Review-run recording.** Standalone `skill-bill code-review` records each run
  (`review_runs` and the review telemetry `review_stats` reads) through
  `ParallelCodeReviewRunner`'s runtime-owned persistence; the full run's last-commit
  review records nothing. Both behaviours stay. Recording is a `PhaseRunState`
  capability: the in-memory state records the review step's decoded findings through the
  existing review persistence, and the durable state does not. No strategy branches on
  which definition runs it.
- The review mode is the one operator input that selects a strategy, and it reaches
  every definition the same way: `code-review:` / `--code-review-mode` for the full
  run, `mode:` for `phase review`. Goal planning has no review slot.
  - `review`: `mode:inline` (default) or `mode:delegated`. Today's `auto` is still
    accepted and resolves to `inline`.
  - `standalone` and `goal-child`: accept the values they accept today (`inline`,
    `auto`), and the selection resolves every one to `inline`. `delegated` stays a
    usage error there until a developer changes that selection entry.
  - Nothing selects `delegated` by default. It runs only when an operator passes
    `mode:delegated` (to `phase review`, or to an operation such as `operation:verify`
    that forwards it). `auto` resolves to `inline` everywhere, as
    `ReviewExecutionModePolicy` already does.

Also after this bundle:

- The run loop asks the definition, selection, and registry for the strategy and never
  branches on a phase id.
- Adding a strategy means one class, one registry entry, and one selection entry.
  Adding a definition means one declaration and its selection entries.
- Full runs reproduce today's bytes except the planned changes in the fixture ledger
  below.
- `skill-bill code-review` runs the `review` definition, so there is one review path.
- `/skill-bill` is a listed skill beside the old ones, routing the full run and every
  `phase:` definition (subtask 12).
- Main stays usable when this bundle merges: every old listed skill still works, and
  `/bill-code-review` goes through `phase review`.
- Only variants that exist today ship: `pack-build` and `agent-validate`, and `inline`
  and `delegated` review.

Out of scope: IDE plugin UI, a generic workflow framework, new step ids, slots outside
the nine or reordered, and removing the structured review-finding pipeline behind the
review strategies (follow-up bundle, see Non-goals).

## Phase input and output

Every step of every composition goes in and comes out the same way.

**Today.** Every step prompt ends with the shared `outputContract()` section: a final
JSON object checked by a "validated schema gate" against the phase-output contract
(`contract_version`, `phase_id`, `status`, `failure_disposition`, `summary`,
`produced_outputs` with per-step required shapes, `derived_notes`, `verdict`). Since
`c42bc4886` (2026-09-25), preplan, plan, and implement prompts carry a durable
settlement directive (`FeatureTaskRuntimePhasePromptSettlementDirectives`, keyed by its
own `SETTLEMENT_PHASE_IDS`): they pin `workflow_id` and `attempt` from a
`FeatureTaskRuntimePhaseSettlementTarget` and finish through the MCP tools
`feature_task_phase_complete` / `feature_task_phase_block` with one prose value, the
printed envelope demoted to a fallback. Simplify and audit put prose in
`produced_outputs.value` of the printed envelope. The MCP tools accept only those five
prose step ids. `ProsePhaseOutputSynthesizer` recovers their
final object leniently, but only for those five ids, and it hard-codes audit's verdict
rules.

**After this bundle.**

- **Input:** the step's task directive, the prose values of earlier steps the step
  reads, operator instructions, and per-call facts (review target, paths, issue key).
  The runner composes them into one prompt.
- **Output:** the settlement shape (`SettlementEnvelopeRequest`): a status
  (`completed`, `blocked`, `failed`), one prose `value`, a `summary`, an optional
  one-word `verdict`, and `failure_disposition` when not completed.
- **Channel, from `PhaseRunState`:** the state supplies the settlement target.
  - Durable runs have one (workflow id and attempt), so every step that settles with
    the uniform output finishes through the MCP settlement tools, as preplan, plan, and
    implement already do. The printed minimal object stays a fallback.
  - In-memory and goal-planning runs, and operation steps, have no workflow, so they
    get no target and finish with the minimal final object, which the runner reads
    leniently for any step name.
  - The tools' accepted set grows with each step that moves (subtasks 4 and 5), and
    `feature_task_phase_block` gains the optional `verdict` the settlement request
    already carries.
  - `SETTLEMENT_PHASE_IDS` goes: whether a step settles through the tools follows from
    the state's target and the step's strategy, not from a phase-id set.
- **No schema on agent-written content.** The runner checks only the status, a
  non-blank value, and `failure_disposition` when not completed. Phase-output contract
  validation shrinks to that for every step except the three `code_review` steps. The
  next step's agent reads the prior value and interprets it.
- **Branching** reads only the status or the verdict. Step-specific verdict rules live
  in the owning strategy, not in the runner, the synthesizer, or the settlement
  service. Each step that has a verdict documents its words and the default for an
  absent or unknown word, and the default emits a record:
  - validate: `progress` or `no_progress` (default `no_progress`)
  - audit: today's `satisfied`
- **Runtime facts are measured, not declared.** Commit sha, changed paths, whether
  history or decision files changed, gate results, and the PR URL and number are read
  by the runtime from git, the gate, or `gh`, not from the agent's output.
- **Telemetry the old skills emit keeps flowing.** Where a step or program replaces
  work a listed skill did, the runtime emits that skill's event with the same payload
  and `skill` label: `quality_check_started` / `quality_check_finished` for
  `phase validation`, and `pr_description_generated` for the pr step in every definition.
- Stored records written before this bundle stay readable, so in-flight runs resume.

**Which subtask moves what.**

- Subtask 2: the runner returns the uniform output for every step and decodes nothing
  step-specific; each wrapper strategy decodes its own step's value with today's
  decoding, so bytes stay identical.
- Subtask 4: validate moves to the uniform output.
- Subtask 5: write_history and pr move; audit's verdict rules move into
  `acceptance-audit`; the shared output section becomes the minimal instruction for
  every non-review step; the runner's final-object reader accepts any step name.
- The three `code_review` steps keep their structured output, decoded by the review
  strategies. It feeds the goal runner's review reducers, the unaddressed-findings
  ledger, review telemetry, and learnings, which the follow-up bundle replaces with
  prose (Non-goals).

## Findings

| Finding | Priority | Summary | Subtask |
| --- | --- | --- | --- |
| F-001 | High | Phase behaviour dispatched by identity in the run loop (`when (run.phaseId)`, 250 engine references) | 2, 5 |
| F-002 | High | Launch rules in domain phase-id sets, drifted from behaviour | 2, 5 |
| F-003 | High | Quality-gate selection implemented as transition rewriting; unknown values silently become VALIDATE | 4 |
| F-004 | Medium | Review seam global and not durable; `CodeReviewExecutionMode` inert for the wired driver | 3, 6 |
| F-005 | Medium | Phase-keyed prompt tables; review directives likely unreachable | 2, 5 |
| F-006 | Medium | No durable or telemetry record of how a slot ran | Deferred: selection is code plus the existing pinned review mode, so both are already known. Add a record when a slot gains another runtime-visible choice |
| F-007 | Low | `../../../AGENTS.md` build paragraph disagrees with the three repair turns in code | Open question, not changed |
| F-008 | High | No way to run a phase program without opening a skeleton workflow | 4, 7–9 |
| F-009 | High | Many listed skills are prompt files; operators need one `/skill-bill` dispatcher, phases, and runtime operations. `bill-monitor` is unused | 11 here; operations in SKILL-382; catalog retirement in SKILL-383 |

## Acceptance Criteria

1. runtime-domain declares a closed `PhaseSlot` enum with wire values `preplan`, `plan`, `implementation`, `audit`, `code_review`, `quality_gate`, `write_history`, `commit_push`, and `pull_request`, in that order. Each slot names the existing step ids it owns, and every step id belongs to exactly one slot.
2. There is one `PhaseRunner` interface and one production implementation, with no phase-specific subclass, variant, or wrapper. Its provider is unscoped, so each strategy gets its own instance. Every slot has at least one registered strategy, a strategy runs only step ids its slot owns, every strategy executes its steps through `PhaseRunner`, and strategies touch run state only through `PhaseRunState`.
3. runtime-domain declares `SkeletonDefinition` and the eight definitions in the Intended outcome table. A definition is an ordered subset of the canonical slots in canonical order, and a slot runs all its steps; a definition that reorders slots fails with a typed error. The transition declaration derived from `standalone` and `goal-child` equals today's.
4. Every run, full or phase, is driven by the one run loop over a definition and a `PhaseRunState`. The loop selects step behaviour by asking the selection and registry for the strategy of the step's slot. No production file under `skillbill.engine.featuretask` outside `skillbill.engine.featuretask.slot` references a phase-id constant, and an architecture rule enforces this.
5. Every run-loop read and write goes through `PhaseRunState`. The durable implementation is the only production class under `skillbill.engine.featuretask` that depends on the durable stores, writers, and checkpoint git operations, and an architecture rule enforces this.
6. Launch policy per step (mutating, relaunch on invalid output, single session, read-only idle) comes from the running strategy. The phase-id sets in `FeatureTaskRuntimePhaseWorkflowDefinition` and `FeatureTaskRuntimeRunnerPolicies` no longer exist, and `goal-child` replaces the `pull_request` special case.
7. Build and validate are two strategies of the `quality_gate` slot. `FeatureTaskRuntimeQualityGateRouting` no longer exists, and goal children keep today's rule: build for every subtask except the last non-skipped one, validate for the last.
8. `PhaseStrategySelection` is one code-defined binding in runtime-core that maps every definition's slots to strategies and maps the review mode to a `code_review` strategy per definition. Building it fails with a typed error when an entry names an unregistered strategy or a slot outside its definition. Replacing one entry in a test runs the other strategy with no change to the run loop, the definitions, or any other strategy.
9. An unknown quality-gate selection value (`--quality-gate-selection`, `SKILL_BILL_QUALITY_GATE_SELECTION`) fails as a usage error instead of becoming VALIDATE. Unknown review modes keep failing as they do today.
10. Every step launches through `PhaseRunner` (SKILL-382 holds operation steps to the same rule). Sub-agents a step starts (delegated review lanes, gate triage and repair) launch through the shared `GoalRunnerSubtaskLauncher` port that `PhaseRunner` also uses. An architecture rule forbids any class in the run loop or the phase-run entry from depending on that port; only `PhaseRunner`'s implementation and strategy packages may. SKILL-382 extends the rule to operation packages.
11. Every step goes through `PhaseRunner` with the uniform input and output, and the runner decodes no step-specific structure. Outside the three `code_review` steps (decoded by their strategies until the follow-up bundle), no step prompt asks for a structured object beyond the settlement fields, phase-output validation checks only status, value, and failure disposition, branching reads only status and verdict, and the runtime measures commit sha, changed paths, gate results, and PR identity itself. The runtime still emits `quality_check_*` and `pr_description_generated` with today's payloads and labels.
12. Full-run workflow snapshots, phase records, ledger entries, handoff projections, run invariants, telemetry payloads, and composed prompt text are byte-identical to the subtask 1 fixtures for standalone runs and goal children, except the changes the fixture ledger assigns to a named subtask.
13. `InlineReviewStrategy` (`inline`) is today's `last-commit-fix` with a per-call review target and is the default in every definition; full runs always use it for now. The `code_review` slot finds and fixes with either strategy. Nothing selects `delegated` unless the operator passed `mode:delegated`, and a test per definition proves the default is `inline`. `DelegatedReviewStrategy` (`delegated`) reviews through `ParallelCodeReviewRunner` with bounded lane progress, does not edit in its review step, and hands findings to verify_findings and implement_fix when it fills a full run's slot.
14. A phase run enters through `PhaseRunRequest` / `PhaseRunEntry` / `PhaseRunResult`, which build the definition, in-memory state, and facts and call the same run loop. It creates no `feature_task_workflows` row, no runtime session row, no phase records, no ledger, no run invariants, and no git checkpoint ref, and it does not resume.
15. `/skill-bill <intake>` with no `phase:` and no `operation:` is the full run (today's `bill-feature`). `/skill-bill … phase:plan` runs the `plan` definition and writes a spec bundle. Intake is required for plan and implement; optional for review, validation, and pr.
16. `phase commit_push` is refused. `phase implement` requires an existing spec. `phase pr` does not commit uncommitted work. If the current local branch has commits that are not on the remote, it pushes that branch, then opens the PR. Before writing the PR summary it searches the repo for a pull-request template using the same locations `bill-pr-description` uses today; when one exists it fills that template, otherwise it uses the built-in fallback now owned in code.
17. `phase review` runs the whole `code_review` slot of the selected review strategy on a target of `HEAD`, `uncommitted`, or a commit sha/name; omitted, the target is `uncommitted` when the worktree is dirty, otherwise `HEAD`. Every dirty path is owned. With either strategy it finds, verifies, and fixes, creates no commit, and prints what remains. `skill-bill code-review` runs through this definition and still records a review run in both modes. `phase validation` runs the `pack-build` strategy over the dominant pack `validation_gate` and emits `quality_check_started` / `quality_check_finished` as `bill-code-check` does today.
18. Goal planning runs the `goal-planning` definition through the one run loop and its own `PhaseRunState` implementation; no class under `skillbill.engine.goalrunner.planning` launches an agent outside `PhaseRunner`, and its prompts, checkpoints, attempt log, and planning-log output match the subtask 1 fixtures.
19. Custom instructions prepend to the phase prompt, apply to every step in the definition unless the user scopes them, and have no size cap.
20. `/skill-bill` is installed as a listed skill beside every old listed skill and routes the full run and every `phase:` definition. Every old listed skill still works after this bundle merges.
21. `../../../runtime-kotlin/ARCHITECTURE.md` and `AGENTS.md` describe the slots, skeleton definitions, `PhaseStrategy`, the one `PhaseRunner`, the phase input and output shape, `PhaseRunState` and its two implementations, the selection binding, the two review strategies, the `phase` CLI, the `/skill-bill` dispatcher, and how to add a strategy or a definition. `runtime-kotlin/agent/decisions.md` records the compositional contract, skeleton definitions as the one run shape, the uniform AI-facing phase I/O, that strategy selection is code, and the review strategies.

## Executable scope

Twelve subtasks, one commit each, in one PR that leaves main usable.

- A. parts and the full run (1–7)
- B. phase runs, goal planning, skeleton prompt ownership, and the dispatcher (8–12)

Phase definition ids (not slot wire values): `plan`, `review`, `validation`,
`implement`, `pr`.

Follow-up bundles, each its own PR that leaves main usable:

- **SKILL-382 runtime operations** (4 subtasks): the operation contract and
  confirmation gate, and every `operation:` job, with dispatcher routes.
- **SKILL-383 single skill catalog** (2 subtasks): re-parent sidecars to `skill-bill`,
  retire every old listed skill, delete `bill-monitor`. Its spec maps every old skill
  to its replacement.
- A later bundle replaces the structured review-finding pipeline with prose
  (Non-goals).

### Wave A — parts and the full run

1. **Pre-change behaviour fixtures** (`spec_subtask_1_pre-change-fixtures.md`).
   - Captures the byte, prompt, standalone-review, and telemetry fixtures every later
     subtask diffs against. No production change.
   - Split condition: fixtures committed with the refactor cannot prove they predate
     it, and a relaunched attempt could regenerate them from changed code.
2. **Slot skeleton, strategy contract, runner, state port, selection, and dispatch**
   (`spec_subtask_2_slot-skeleton-and-dispatch.md`).
   - `PhaseSlot`, `PhaseStepPolicy`, `PhaseStrategy`, the one `PhaseRunner`,
     `PhaseRunState` for strategy-owned state, the registry, `PhaseStrategySelection`,
     and registry dispatch. Every slot gets a thin strategy that wraps today's step code.
   - Split condition: the contract must exist before any slot's behaviour can move.
3. **`code_review` strategy `inline` (`InlineReviewStrategy`)**
   (`spec_subtask_3_inline-review-strategy.md`).
   - Split condition: the largest single move; its regressions (lost carry-forward,
     lost re-entry) need their own review.
4. **Skeleton definitions, slot traversal, and the `quality_gate` strategies**
   (`spec_subtask_4_skeleton-definitions-and-quality-gate.md`).
   - `SkeletonDefinition` with `standalone` and `goal-child`, the derived graph, slot
     traversal, `pack-build` and `agent-validate`, routing deleted, loud selection
     values, validate on the uniform output.
   - Split condition: replacing transition rewriting and the `pull_request` special
     case is one failure surface (a child running both gates, neither, or a PR).
5. **Remaining slots and the no-phase-id guard**
   (`spec_subtask_5_remaining-slots-and-guard.md`).
   - Split condition: mechanical and large; the guard can only go live at zero
     remaining references.
6. **`code_review` strategy `delegated` (`DelegatedReviewStrategy`)**
   (`spec_subtask_6_delegated-review-strategy.md`).
   - Split condition: the hang fix touches the launcher; it must land and be proven
     before any definition can select the strategy.
7. **Run loop runs over `PhaseRunState`** (`spec_subtask_7_run-loop-over-run-state.md`).
   - Every run-loop read and write moves behind the port; the durable implementation
     stays byte-identical.
   - Split condition: the precondition for runs with no workflow row; its own
     resume-parity review.

### Wave B — phase runs, prompt ownership, dispatcher

8. **Short definitions, in-memory state, and `phase review` / `phase validation`**
   (`spec_subtask_8_phase-review-and-validation.md`).
   - The in-memory state, `PhaseRunEntry`, the `phase` CLI, and `skill-bill
     code-review` routed through the `review` definition.
9. **The `plan`, `implement`, and `pr` definitions**
   (`spec_subtask_9_phase-plan-implement-pr.md`).
10. **Goal planning runs the `goal-planning` definition**
    (`spec_subtask_10_goal-planning-definition.md`).
    - The sweep's own launch path goes; shared preplan and per-subtask plans become the
      preplan and plan slots over a goal-planning state.
    - Split condition: goal-runner code with its own recovery model; its own review.
11. **`pr-description` and `boundary-history` own their rules**
    (`spec_subtask_11_pr-and-history-rules.md`).
    - Split condition: a planned prompt re-baseline; it must not hide inside another
      change.
12. **`/skill-bill` dispatcher for the full run and phases**
    (`spec_subtask_12_skill-bill-dispatcher.md`).

## Fixture ledger

Subtask 1 captures every skeleton fixture. Every later subtask diffs against the
latest baseline. Only the subtasks and authorized follow-up changes below may change a fixture. Each of them commits the
re-baselined fixture in the same commit, and the fixture diff contains only the
listed change.

| Fixture | Re-baselined by | Allowed change |
| --- | --- | --- |
| review output and consuming handoffs in full-run fixtures | 2026-10-03 operator-requested durable schema migration | seed current output version 0.7; historical migration fixtures retain their explicit source versions |
| validate prompt, phase record, and consuming handoffs | 4 | uniform output; the shrink decision reads the `progress` / `no_progress` verdict |
| write_history and pr prompts, phase records, and consuming handoffs | 5 | uniform output; history and PR facts measured by the runtime |
| output-contract section of every non-review step prompt, and those steps' phase records | 5 | the "validated schema gate" JSON contract replaced by the minimal settlement instruction; stored envelopes keep only settlement fields |
| `skill-bill code-review` output | 8 | both modes now run the whole `code_review` slot and fix: inline through `InlineReviewStrategy`, delegated through verify_findings and implement_fix after the multi-agent review |
| pr and write_history step prompts | 11 | the "Invoke bill-pr-description …" and "Invoke bill-boundary-history inline …" directives replaced by strategy-owned rules |
| Phase-run outputs | first captured by the subtask that adds the program | none in this bundle; SKILL-383 replaces retired skill names |
| archived revision-one audit execution descriptors | 2026-10-03 operator-requested audit compatibility mapping | retain snapshots from `8527efaee` as migration rejection and admission fixtures; restore normalized digests from policy identities in tests |
| full-run audit prompts and execution descriptors, archived revision-two descriptors | 2026-10-03 operator-requested prose repair planning | remove mandatory plan headings and labels, retain prose handoffs, record audit strategy revision 3, and preserve revision-two fixtures for checked migration |
| full-run workflow execution descriptors and audit prompts | 2026-10-03 operator-requested audit repair planning | add read-only `audit_plan_fix` before repair, require its saved output, route the audit edge through it, and record acceptance-audit strategy revision 2 |

## Self-sufficient execution

This bundle runs on the current tree. It does not wait for a subtask of another issue.

Build the slot strategies from the feature-task run loop that exists. If that loop is already step classes, group those classes by slot. If it is still objects and bags, turn the cited behaviour into strategy classes in this bundle.

Do the same for git results and typed artifacts: use the typed API when it exists, and the current API when it does not. Learnings delivery uses whatever resolver is on the tree. SKILL-379 is already complete and is not a start gate.

Inside this bundle the order is subtask id order: 1 through 12. Dependencies: 2→1,
3→2, 4→2, 5→3, 5→4, 6→5, 7→5, 8→6, 8→7, 9→8, 10→7, 10→9, 11→5, 11→9, 12→9, 12→11.

## Second attempt (subtasks 2–6)

The first run of subtasks 2–6 is discarded; subtask 1 and its fixtures stay. Its
last commit was `2a8557d26` before the rebase onto SKILL-378. It used this spec's
names without its structure, and was marked complete with 4 of 8 criteria (subtask 4)
and 6 of 9 (subtask 5). What went wrong:

- Strategies called back into the run loop's attempt code. The loop then re-resolved
  the strategy to launch. Strategies owned ids and directive text, not behaviour.
- All eleven strategies shared one `PhaseRunner` instance. An engine object built
  them, and `decisions.md` claimed one instance per strategy.
- Inline review launched through its own driver on `GoalRunnerSubtaskLauncher`,
  outside `PhaseRunner`, still bound globally as `phaseGates.reviewDriver`.
- The phase-id guard matched constant names. 161 comparisons in 47 files stayed behind
  `FeatureTaskRuntimeStepSemantics` aliases (`reviewStepId = PHASE_REVIEW`).
- The phase-output contract moved to `0.7` while
  `goal-planning-preparation-schema.yaml` still pinned `0.6`, which broke goal
  planning.
- `mutatingReconciliationGateReason` was deleted without a ledger entry.
- `./gradlew check` failed at the last commit: detekt, spotless, inline FQNs, package
  ceilings, and about 100 tests.

Reusable from the first run, after rechecking against the current tree:
- `PhaseSlot`
- `SkeletonDefinition` with derived transitions
- the loud `FeatureTaskRuntimeQualityGateSelection.fromWire`
- the review-lane idle bound and its reproduction test (subtask 6)

## Constraints

- **Completion.** A subtask is complete only when every acceptance criterion holds and
  `cd runtime-kotlin && ./gradlew check` passes at its commit. Otherwise it blocks. It
  never settles `complete` with a partial criteria count in its history entry.
- **Meaning over names.** A rule forbids a behaviour, not a spelling. Satisfying a
  guard by renaming, aliasing, re-exporting, or indexing the forbidden thing violates
  it, for example `FeatureTaskRuntimeStepSemantics.reviewStepId` or
  `PhaseSlot.CODE_REVIEW.stepIds[1]`. A guard's synthetic-violation test calls the
  same function the real rule calls, not a copy of its pattern.
- **Proof, not assertion.** A runtime `require` or test that holds by construction
  does not prove a criterion. Examples: comparing a runner to itself, or a test that
  builds its own strategies instead of reading the production provider.
  `ARCHITECTURE.md` and `decisions.md` state only what code and tests show.
- **Contract bumps.** A contract version bump updates, in the same commit, every
  schema `const:`, SQL check, and fixture that pins the old value. One test asserts
  every pin equals the Kotlin constant. Records at the previous version keep decoding.
- **No silent behaviour removal.** Deleting a runtime gate, check, or fallback is a
  behaviour change. It needs a fixture-ledger entry naming it, or it stays.
- **Dependency direction.** Shared feature-task code depends only on the slot
  contract, never on a strategy package (`slot.audit`, `slot.codereview`, …).
  - Shared code: `runloop`, `phase`, `runner`, `review`, `validation`, `lifecycle`.
  - The slot contract: `PhaseStrategy`, `PhaseRunner`, `PhaseRunState`, registry,
    selection, lookup, `PhaseSlot`, `SkeletonDefinition`.
  - Strategies do not call back into the run loop's drive, launch, or attempt code.
  - Enforced by an architecture rule from subtask 5.

- **Read first:**
  - `../../../runtime-kotlin/ARCHITECTURE.md` Design Principles
  - `../../../docs/code-principles.md`
  - `../../../docs/observability-policy.md`
  - `../../../AGENTS.md`
  - `../../../docs/skill-source-generation.md`, only if prose outside runtime-kotlin changes
- **Kotlin style:**
  - no `//` comments
  - KDoc only on interfaces
  - 1,200-line and 40-function ceilings
  - package sibling ceilings
  - no inline FQNs
  - wire keys from owning `*Keys` objects
  - enum wire tokens via `wireValue`
- **SKILL-378.2's and the SKILL-378 step-class follow-up's rules stay in force:**
  - acyclic `FeatureTaskRuntimeRunLoop*` dependency graph
  - no top-level function objects in `featuretask/runloop`
  - no collaborator-carrying `*Args`/`*Inputs`/`*Context` classes
  - at most six parameters per function
  - private inject properties

  Strategies take collaborators through constructors and per-call facts as
  parameters. `PhaseRunState` is one port interface passed per call, not a bag.
- **One execution path.** One run loop, one `PhaseRunner` implementation, no
  phase-specific runner, no definition-specific launch code, no copy of a strategy. A
  behaviour difference between a full run and a phase run comes from the definition,
  the `PhaseRunState` implementation, or the per-call facts, never from a branch on
  which entry started the run.
- **AI-facing I/O.** No new required structure in agent-written output. Anything the
  runtime needs to decide on is a status, a verdict word, or a fact the runtime
  measures.
- **Skeleton limits.** The nine slots and their order are fixed. A definition is an
  ordered subset of them, declared in code; operators cannot supply one. Strategies are
  in-process classes registered by one explicit `@Provides`, and selected by one
  explicit `PhaseStrategySelection` binding. There is no reflection, classpath scan, or
  multibinding.
- **Durable bytes.** No new step id or transition edge. Stored full-run bytes stay
  identical except the fixture ledger's entries; handoff projection contracts change
  only where the ledger says, with a version bump. No stored byte names a definition;
  resume re-derives it. Phase runs add no workflow, session, phase-record, ledger, or
  run-invariant rows and no git checkpoint refs.
- **Full run versus phase run.** Both are definitions on the same loop. Phase runs
  use the in-memory state and do not resume. `commit_push` is not a phase definition.
  Plugin hosts call `PhaseRunEntry`.
- **CLI grammar.** The root `skill-bill` command is a clikt parent with subcommands and
  no positional arguments, so `skill-bill <intake> phase:<name>` cannot parse. The CLI
  form is a subcommand, `skill-bill phase <name> [<intake>] [key:value …]`
  (`mode:`, `target:`), reached from runtime-cli through a `RuntimeComponent` accessor
  to `PhaseRunEntry`.
  `/skill-bill` translates its `phase:<name>` token into that subcommand.
- **Engine inbound API.** runtime-cli and runtime-mcp may reference only engine types in
  `RuntimeEngineInboundApiTest.PINNED_ENGINE_INBOUND_API_TYPES`. Every engine type the
  CLI needs (`PhaseRunRequest`, `PhaseRunResult`, `PhaseRunEntry`) is added to that
  list in the subtask that first needs it.
- **Usable on merge.** No old listed skill is removed or broken. `/skill-bill` is added
  beside them.
- **Review default.** Nothing selects `delegated` unless the operator passed
  `mode:delegated`.
- **No install inside the goal.** No subtask runs `./install.sh` against the real
  home. The goal runs on the installed runtime and skills; reinstalling mid-goal
  would delete skills its remaining phases still name. Subtasks prove install
  behaviour with install tests or an install into a temporary `HOME`.
- **Loud failure.** An unknown strategy, slot, definition, review mode, or gate
  selection fails with a typed error. Every fallback emits a record.
- **No speculation.** No config knobs, speculative strategies, or extension points
  beyond this spec.
- **Anchors.** Recheck every file anchor at the start of each subtask against the current tree.
- Use a local clone, not a linked worktree, for Spotless.

## Non-goals

- Operations and the confirmation gate (SKILL-382). Retiring listed skills, re-parenting
  sidecars, deleting `bill-monitor`, and the single catalog (SKILL-383).
- **The structured review-finding pipeline.** Both review strategies keep decoding
  their structured findings (the inline F-XXX register and verdict line;
  `ParallelCodeReviewRunner`'s citations, claim verification, integration pass, and
  spec adjudication), and the review tables, the `import_review` / `triage_findings` /
  `review_stats` / learnings tools, the goal review reducers, and the
  unaddressed-findings ledger stay. A follow-up bundle replaces them with prose end to
  end. SKILL-380 only puts the review steps on the one runner and the uniform I/O
  boundary.
- Operator-facing strategy selection beyond the existing review mode flag: a
  `workflow_profile` config key, a generic `strategy:` parameter, or a per-slot CLI
  override.
- Freezing the strategy map into run invariants or a per-run strategy telemetry field
  (F-006, deferred). The review mode stays pinned on resume as today.
- Changing goal planning's shared-preplan design, wave policy, provenance, or child
  hydration (subtask 10 only moves it onto the run loop), or giving goal children a
  planning strategy other than the imported records.
- Restructuring goal-runner code that reads phase records by step id (SKILL-378.3
  territory).
- Correcting today's mutation flags for audit, validate, write_history, or review, or
  resolving the build repair-turn doc drift (F-007).
- External or pack-provided strategies, per-subtask strategy switching, slots outside
  the nine, reordered slots, and operator-supplied definitions.
- Resume for phase runs (the durable state would give it later; not now).
- New strategies beyond `delegated` review.
- IntelliJ or VS Code plugin UI, worktree locks, or isolated `commit_push`.
- Turning pack specialist native-agents into operations, or deleting platform-pack
  `content.md` that native-agent generation still reads.
- Renaming telemetry `skill` labels or stored feature-verify `workflow_name` values.

## Validation strategy

- **Behaviour baseline.**
  - The existing run-loop and goal-runner suites over real SQLite: phase order,
    backward edges, checkpoint identity, resume from durable records, review and gate
    settlement, commit finalization, status projection.
  - Subtask 1 captures byte fixtures of snapshots, phase records, ledger, handoff
    projections, run invariants, telemetry, and composed prompt text for a standalone
    run and a goal child. Every subtask diffs against them under the fixture ledger.
  - Resume of a run whose records were written before the I/O change, for each step
    that changes shape.
- **Per subtask:** `cd runtime-kotlin && ./gradlew check`, plus the engine, core,
  CLI, MCP, and infra suites.
- **Guards.** New architecture rules get a synthetic violation that must fail, and
  must assert they read at least one file per scanned root.
- **Contract tests.** Rejection tests for an unknown strategy in the selection, an
  unknown or reordered definition, `phase commit_push`, `phase implement` without a
  spec, `phase review` with an unknown target, and an unknown review mode or quality-gate
  selection value.
- **Test review.** Changed tests go through `bill-unit-test-value-check` (the installed
  skill; the goal does not reinstall). The validate phase runs the pack-declared gate.
- No tests ran during preparation.

## Next path

```bash
skill-bill goal SKILL-380
```

After the goal's PR merges, run `./install.sh` from a local clone. Then
`skill-bill goal SKILL-382` (operations), then `skill-bill goal SKILL-383` (catalog).

