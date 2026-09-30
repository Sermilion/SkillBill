# SKILL-380 Subtask 2 - Slot skeleton, strategy contract, runner, state port, selection, and dispatch

Parent spec: [spec.md](spec.md)
Issue key: SKILL-380

## Scope

Resolves the structural half of investigation F-001 (dispatch) and F-002 (launch
policy). Defines every part full runs, phase runs, and operations compose.

Build the slot strategies from the feature-task run loop that exists. If that loop is
already step classes, group those classes. If it is still objects and bags, wrap the
cited behaviour in strategy classes here. Do not wait for another issue.

In this subtask a strategy's step bodies may still be today's step code, called from
the strategy. The control flow is final from this subtask on:

- The run loop calls `strategy.runStep` and nothing else step-specific.
- The strategy starts every agent session by calling its own `PhaseRunner`.
- Nothing outside the runner implementation calls `GoalRunnerSubtaskLauncher`.
- Nothing re-resolves a strategy in order to launch; delete today's
  `executeSubtaskLaunch`-style lookup in the loop.
- The shared attempt path (relaunch on invalid output, output gate, retry budget) is
  strategy-side code under `slot` that calls `PhaseRunner` and `PhaseRunState`. It is
  not run-loop code a strategy calls back into. Until subtask 7 it may still write
  records and ledger through today's writers.

Subtasks 3, 4, and 5 move the remaining step code into the strategies. The parent
"Second attempt" section lists what the first run got wrong; do not repeat it.

**Skeleton (runtime-domain, beside `FeatureTaskRuntimePhaseIds`).**
- `PhaseSlot` is a closed enum with `wireValue`, in this order:

  | Slot | Steps |
  | --- | --- |
  | `preplan` | preplan |
  | `plan` | plan |
  | `implementation` | implement, simplify |
  | `audit` | audit |
  | `code_review` | review, verify_findings, implement_fix |
  | `quality_gate` | build, validate |
  | `write_history` | write_history |
  | `commit_push` | commit_push |
  | `pull_request` | pr |

  Each slot exposes its owned step ids, and a step id maps back to its slot. The
  goal-child rule "no pull_request" is expressed on the skeleton; it replaces the
  `takeWhile { it != PHASE_PR }` in `FeatureTaskRuntimeRunnerPolicies`.
- `PhaseStepPolicy` is a domain value with six fields:
  - mutating
  - relaunch on invalid output
  - single agent session
  - read-only idle
  - file mutating (replaces `NON_FILE_MUTATING_PHASES`)
  - generation scoped (replaces `GENERATION_SCOPED_PHASE_IDS`)

  Each strategy class declares the policy of its own steps. There is no shared table
  keyed by step id. Today's values carry over exactly; the investigation lists the
  mismatches and they stay.
- `PhaseSlot.slotForStep` on an unknown step id raises a typed error in the
  `skillbill.error` taxonomy, not `error()`.

**Parts (runtime-engine, `skillbill.engine.featuretask.slot`).** Each interface has
KDoc.

- `PhaseRunner` is one interface with one production implementation. It is not
  phase-specific and has no subclass, variant, or phase-keyed branch. It executes any
  step: prompt composition, agent launch through the `GoalRunnerSubtaskLauncher` port,
  settlement, and the result. Its provider is unscoped, so every strategy (and later
  every operation) receives its own instance; nothing depends on sharing
  one.
  - Input: step name, task directive, the prose values of earlier steps the step
    reads, operator instructions, per-call facts, and `PhaseStepPolicy`. Skeleton steps
    name themselves by step id. Operation steps (SKILL-382) use operation-local names
    that never enter the domain graph.
  - Output: the parent "Phase input and output" shape (status, prose value, summary,
    optional verdict, failure disposition when not completed). In this subtask the
    runner keeps today's channels unchanged: the shared output-contract section, the
    durable settlement directive and MCP tools for preplan, plan, and implement
    (`c42bc4886`), and today's final-object recovery. `PhaseRunState` supplies the
    settlement target (`FeatureTaskRuntimePhaseSettlementTarget`) that the directive
    pins.
    Subtask 5 replaces the output section and generalizes recovery to any step name.
  - The runner never decodes a step-specific envelope. Review, verify_findings,
    implement_fix, validate, write_history, and pr still emit structured envelopes
    today; for those steps the runner returns the agent's output as the value, and the
    owning wrapper strategy decodes it with today's decoding, moved next to the
    strategy unchanged, so stored bytes stay identical. Subtasks 4 and 5 remove that
    decoding for validate, write_history, and pr. The code_review strategies keep
    theirs until the follow-up bundle (parent Non-goals).
  - Collaborators come through the constructor.
  - The interface's input and output are runner-owned types: the step input and the
    uniform output above. It names no type from `skillbill.engine.featuretask.runloop`
    (no `PhaseRun`, no `PreparedLaunch`) and does not return the raw launch outcome.
  - The implementation is today's launch code, moved, not a new layer. On 2026-09-25
    the feature-task classes that depend on `GoalRunnerSubtaskLauncher` are
    `FeatureTaskRuntimeRunLoop`, `FeatureTaskRuntimeRunner`, and
    `FeatureTaskLastCommitReviewDriver`; recount at start. After subtask 5 only the
    `PhaseRunner` implementation and strategy packages may depend on the port.
  - `FeatureTaskRuntimeRunLoopPhaseRunner` is review-specific. Subtask 3 deletes it; it
    is not the public contract.
- `PhaseRunState` is the one port a strategy uses to read and write run state it
  owns: prior step outputs it consumes, review-pass reservations and carry-forward,
  checkpoints, gate finding sets, receipts. It is passed per call. This subtask adds
  the skeleton implementation, which delegates to today's durable writers and readers
  unchanged. In this subtask phase records, ledger, run invariants, and the workflow
  snapshot stay outside it; subtask 7 moves every run-loop read and write behind the
  same port, and subtask 8 adds the in-memory implementation.
- `PhaseStrategy`. A strategy declares:
  - its slot
  - its strategy id (a wire string)
  - the steps it runs, all owned by its slot
  - its entry step
  - the `PhaseStepPolicy` and task directive of each step

  It runs one step by calling `PhaseRunner` with that step's description, the per-call
  facts, and the `PhaseRunState` it was handed, and returns the existing phase outcome
  type. Collaborators come through the constructor. It never branches on whether it
  runs in a full run or a phase run.

  Keep the interface to the members above. IDE status projection is a separate
  interface a strategy may also implement, not a member of `PhaseStrategy`.
- `PhaseStrategyRegistry` is built by one explicit `@Provides` in runtime-core that
  lists every strategy and constructs each one itself. Each strategy's runner comes
  from the unscoped `PhaseRunner` provider. No engine-side object assembles strategies
  or runners. The registry fails with a typed error in the `skillbill.error` taxonomy
  in three cases:
  - two strategies share a `(slot, id)` pair
  - a strategy declares a step its slot does not own
  - a lookup names an unknown pair
- `PhaseStrategySelection` is one explicit `@Provides` in runtime-core, next to the
  registry. It is code, not configuration. It maps a skeleton definition's slots to
  strategy ids. Subtask 4 introduces skeleton definitions; in this subtask there is one
  implicit definition, today's step order, with one strategy id per slot. `quality_gate` resolves from today's
  quality-gate selection value to the temporary `routed-quality-gate` wrapper; subtask
  4 splits it. `code_review` resolves from the review mode the skeleton already
  receives (`code-review:` / `--code-review-mode`); every value the skeleton accepts
  today resolves to `inline`. Short definitions arrive in subtasks 8 and 9. Building the selection
  checks every entry against the registry and fails with a typed error on an
  unregistered id. Every slot, `quality_gate` included, resolves through the same
  binding lookup. The selection and the lookup contain no branch on a specific slot.

No multibinding, reflection, or classpath scan. No operator-facing selection.

Strategy ids for the wrappers are the final ids from subtasks 3–5
(`agent-preplan`, `agent-plan`, `implement-then-simplify`, `acceptance-audit`,
`inline`, `boundary-history`, `runtime-commit`, `pr-description`), plus the
temporary `routed-quality-gate`.

**Dispatch.**
- `FeatureTaskRuntimeRunLoopPlanningBranch.runPreparedPhaseReady` and
  `FeatureTaskRuntimeCurrentPhaseExecutionDeriver` stop switching on phase id. They
  ask the selection for the step's slot, look the strategy up in the registry, and
  call it with the skeleton `PhaseRunState`.
- Transitions, gates, and edges stay declared in the domain graph. Ledger and
  transition bytes stay identical.
- Launch-policy reads (retry budgets, relaunch decisions, mutating reconciliation,
  branch setup, idle policy and 30-minute read-only timeout, generation scoping) come
  from the running strategy's `PhaseStepPolicy`.
- Delete from `FeatureTaskRuntimePhaseWorkflowDefinition`:
  - `MUTATING_PHASES` and `isMutatingPhase`
  - `OUTPUT_RETRY_PHASES` and `retriesOnInvalidOutput`
  - `singleAgentSessionOnly`
  - `GENERATION_SCOPED_PHASE_IDS`

  Delete `NON_FILE_MUTATING_PHASES` from `FeatureTaskRuntimeRunnerPolicies`.
  `PROSE_PHASE_IDS` stays: it is the MCP settlement contract.

**Docs and decisions.**
- Add a "Phase slots and strategies" section to `../../../runtime-kotlin/ARCHITECTURE.md`: the
  parts, the skeleton table, how full runs, phase runs (short definitions, subtasks 4 and 8), and operations compose
  them, and how dispatch and policy work.
- Record the decision in `../../../runtime-kotlin/agent/decisions.md`:
  - a fixed slot skeleton with in-process strategies
  - one generic `PhaseRunner` implementation, instantiated per strategy, and one
    `PhaseRunState` port shared by every composition
  - one AI-facing input and output shape for every step
  - selection is a code binding, not operator config
  - explicit provider registration
- Census the architecture and unit tests that pin the deleted sets (grep the
  `repoTest` and `test` trees for each name) and update them. There is no
  `FeatureTaskRuntimeBoundaryOwnershipArchitectureTest` on the tree.

## Acceptance Criteria

1. `PhaseSlot` exists in runtime-domain with the nine wire values in skeleton order. A test proves that every id in `FeatureTaskRuntimePhaseIds.all` belongs to exactly one slot and that every slot owns at least one step.
2. `PhaseRunner`, `PhaseRunState`, `PhaseStrategy`, `PhaseStrategyRegistry`, and `PhaseStrategySelection` exist. There is one `PhaseRunner` implementation with an unscoped provider and no phase-keyed branch. The registry provider lists one strategy for every slot, and every registered strategy executes its steps through its own `PhaseRunner` instance. A test reads the registry from the production providers, not strategies it builds itself, and finds a distinct runner instance per strategy.
3. The runner returns the uniform output for every step. The five prose steps store it with bytes unchanged, and the six structured steps' strategies decode their own value with bytes unchanged.
4. Constructing the registry with a duplicate `(slot, id)` pair, or with a strategy that declares a step outside its slot, raises the typed error. Looking up an unknown pair raises the typed error. Building the selection with an unregistered id raises the typed error. Each case has a test.
5. `runPreparedPhaseReady` and `FeatureTaskRuntimeCurrentPhaseExecutionDeriver` contain no branch on a phase id, and every step launch goes through a selection and registry lookup.
6. `MUTATING_PHASES`, `isMutatingPhase`, `OUTPUT_RETRY_PHASES`, `retriesOnInvalidOutput`, `singleAgentSessionOnly`, `GENERATION_SCOPED_PHASE_IDS`, and `NON_FILE_MUTATING_PHASES` no longer exist, and each strategy's declared `PhaseStepPolicy` equals today's value for its steps (one table test).
7. Every subtask 1 fixture matches unchanged.
8. `ARCHITECTURE.md` has the "Phase slots and strategies" section, and `../../../agent/decisions.md` records the decision.
9. Nothing outside the `PhaseRunner` implementation depends on `GoalRunnerSubtaskLauncher` or re-resolves a strategy in order to launch. `PhaseRunner`'s interface references no `runloop` type.
10. `cd runtime-kotlin && ./gradlew check` passes at this subtask's commit.

## Non-goals

- Moving review, gate, or other step code into strategies (subtasks 3, 4, 5).
- Deleting `FeatureTaskRuntimeQualityGateRouting` or adding slot entry-step resolution (subtask 4).
- Skeleton definitions (subtask 4), moving run-loop writes behind the port (subtask 7), the in-memory `PhaseRunState`, phase runs, CLI, and the dispatcher (subtasks 8–12). Operations (SKILL-382) and listed-skill retirement (SKILL-383).
- Operator-facing strategy selection of any kind.
- Changing any policy value, loop cap, retry budget, prompt text, or stored byte.
- Goal-runner and goal-planning code.

## Dependency notes

- Depends on subtask 1. Subtasks 3 and 4 depend on this subtask.
- Use typed git results, typed artifacts, and the current call shape, whichever is on the tree.

## Validation strategy

The regressions to catch:

- a step launched with the wrong policy (idle timeout, relaunch, or mutating
  reconciliation)
- a resumed run whose reconstructed state differs from live state
- the skeleton `PhaseRunState` writing different bytes from today's writers

What covers them:

- The existing run-loop suites over real SQLite cover the loop, gate, and resume
  paths.
- The subtask 1 fixture comparison covers bytes and prompts.
- New tests: registry and selection construction and lookup failures; the slot-to-step
  partition; the policy table, which compares each strategy's declared policy with a
  test-local copy of today's values; one runner instance per strategy, read from the
  production providers.
- No structural tests beyond these.

Run `cd runtime-kotlin && ./gradlew check`, plus the engine, core, and infra-sqlite
suites. Run `bill-unit-test-value-check` on changed tests.

## Next path

Continue to `spec_subtask_3_inline-review-strategy.md`.

## Spec Path

.feature-specs/SKILL-380-phase-slot-strategies/spec_subtask_2_slot-skeleton-and-dispatch.md
