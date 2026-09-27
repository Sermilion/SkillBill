# SKILL-380 Subtask 5 - Remaining slots and the no-phase-id guard

Parent spec: [spec.md](spec.md)
Issue key: SKILL-380

## Scope

Resolves the rest of investigation F-001, F-002, and F-005.

Subtask 2 registered a wrapper strategy for every slot. Subtasks 3 and 4 filled
`code_review` and `quality_gate`. The seven other strategies still call shared step
code that branches on phase ids. This subtask moves that code into those strategies,
then enforces that nothing else in the feature-task engine references a phase id.

On 2026-09-25 the census was 283 matching lines in 62 production files under
`skillbill.engine.featuretask`. Subtasks 2–4 remove some of them. Recount at start;
compilation decides the deletions.

**Scope revision (2026-09-26).** Attempt 2 blocked on a scope conflict. Subtasks 2–4
built the strategies on `FeatureTaskRuntimeRunLoopContext`. Making them context-free
needs the `PhaseRunState` port that subtask 7 builds. Two parts of this subtask
therefore move to subtask 7:

- the rule that no class under `slot` references `FeatureTaskRuntimeRunLoopContext`
- the step-identity cleanup in run-loop internals (listed under "Run-loop internals"
  below)

This revision overrides any prepared plan task that conflicts with it. In the
uncommitted tree from earlier attempts:

- Delete the `TREE_FORBIDDEN_STRATEGY_REFERENCES` exemption in
  `RuntimeEngineBoundaryArchitectureTest`. The rule scopes below replace it.
- Delete the unwired `slot/PhaseRunStateRules.kt` scaffold. Subtask 7 builds the port.
- Remove every `ARCHITECTURE.md` claim that describes ownership this commit does not
  land.

**Strategies to fill.** Each strategy's step-specific code moves into its slot package
under `skillbill.engine.featuretask.slot`:

| Slot | Strategy id | What moves in |
| --- | --- | --- |
| preplan | `agent-preplan` | Prose settlement specifics; the ceremony line |
| plan | `agent-plan` | Goal-continuation constraint; `applyPlanningStop` and `FeatureTaskRuntimePlanningStopper` decomposition stop |
| implementation | `implement-then-simplify` | Implement and simplify continuation segments; `implementationContinuationDirective`; simplify scope boundary; `implementation_receipt` / `simplification_receipt` checks |
| audit | `acceptance-audit` | In-phase remaining-criteria retry (`FeatureTaskRuntimeRunLoopAuditRetry`); unchanged-remainder block; audit-to-review checkpoint |
| write_history | `boundary-history` | FINALIZATION briefing field set; `history_result` handling |
| commit_push | `runtime-commit` | `runDeclaredCommitPushCycle`; `FeatureTaskRuntimeCommitPushUpstreamHeadFallback`; commit-push settlement special cases |
| pull_request | `pr-description` | `verifyPrEntryIdentity` readiness gate; `pr_result` handling |

**Uniform output for write_history and pr.** Both stop emitting structured envelopes
and settle with the parent "Phase input and output" shape. The facts they declared
today are measured by the runtime after the step:

- write_history: `changed_paths` from git, whether history was written or skipped from
  whether the history file changed, `decisions_recorded` from whether the decisions
  file changed.
- pr: PR URL, number, and whether a new PR was created from `gh` before and after the
  step. Title rules stay in the directive.
- Delete the `history_result` and `pr_result` envelope decoding. Census their readers
  (commit_push briefing, status service, goal stop reports, telemetry) and point each
  at the measured fact.
- Measured facts are runtime-owned fields of the record or handoff projection, with
  keys from an owning `*Keys` object. They are never spliced into the agent's prose
  `value` as text, and readers never parse them back out of prose.
- If git or `gh` cannot measure a fact, the step emits a record and settles blocked
  or marks the fact unknown. It never records an empty path list or `false`.
- Re-baseline the write_history and pr prompt, phase record, and consuming handoff
  fixtures in this commit (parent fixture ledger). Bump each changed handoff projection
  contract. Records written before this change still decode.
- Add write_history and pr to the steps the MCP settlement tools accept.

**One output instruction, one reader.**
- Replace the shared `outputContract()` section with the minimal settlement instruction
  (status, summary, prose value, optional verdict, failure disposition when not
  completed) for every step except the three `code_review` steps. Drop
  `produced_outputs` shapes and `derived_notes` from those prompts.
- Extend the durable settlement directive `c42bc4886` added for preplan, plan, and
  implement (`settlementDirective`, pinned `workflow_id` and `attempt`) to every step
  that now settles with the uniform output, whenever `PhaseRunState` supplies a
  settlement target. Delete `SETTLEMENT_PHASE_IDS` and `settlesThroughMcp`; the target
  and the strategy decide. With no target the prompt carries only the minimal
  final-object instruction.
- Shrink phase-output contract validation for those steps to status, non-blank value,
  and failure disposition. Follow the governed-contract rules for the phase-output
  contract: bump its version, keep a parity test, and keep older stored envelopes
  readable.
- Generalize `ProsePhaseOutputSynthesizer`'s recovery so the runner reads the minimal
  final object for any step name, including steps with no workflow. Its phase-id set
  becomes "every step that settles with the uniform output".
- Move audit's verdict and failure-disposition rules out of `ProsePhaseOutputSynthesizer`
  and `FeatureTaskPhaseSettlementService` into `acceptance-audit`.
- Re-baseline every affected prompt and phase-record fixture in this commit (parent
  fixture ledger). The fixture diff must show the "validated schema gate" section
  replaced in every non-review step prompt: preplan, plan, implement, simplify, audit,
  build, validate, write_history, and pr. The first attempt changed only the version
  string in most of them.
- After this subtask the only step-specific decoding left in production is inside the
  two `code_review` strategies.

**Prompt tables.**
- Each strategy supplies the task directive for its steps. The composer receives the
  directive as input.
- Directive text moves verbatim. Subtasks 11 and 12 change the pr and write_history
  directives on purpose; this subtask does not.
- Delete `phaseDirectives` and every other phase-keyed directive table or `when`
  under `phase/prompt`.
- Shared sections (header, discipline, output contract, retry) stay in the composer.
  They take facts from the strategy's `PhaseStepPolicy` or from explicit parameters,
  not from phase-id comparisons.

**Step-owned references (this subtask).**
- Every phase-id reference in step-owned code moves into the owning strategy or
  becomes a slot or strategy query. Step-owned code is:
  - `phase` (prompt composition, directives, and step-specific settlement and decoding)
  - briefing assembly
  - continuation outcome projection
  - lifecycle reasons
  - the seven behaviours in the Scope table
- Code that needs "is this the review step" asks the selection for the slot or
  strategy that owns the step.

**Run-loop internals (moved to subtask 7).**
- Step-identity references in these packages stay for now. Subtask 7 removes them when
  it puts the run loop behind `PhaseRunState`:
  - run-state validation and reconstruction
  - output verification and persistence
  - record rejection
  - status-service phase resolution
  - runner launch outcomes and policies
- Don't move code from a step-owned package into one of these to get it out of
  the guard's scope. "Meaning over names" applies to package placement too.

**Goal planning.**
- The goal planning sweep keeps its own flow. It obtains the preplan and plan task
  directives from the default `agent-preplan` and `agent-plan` strategies through the
  registry, not from the deleted table.
- No other goal-runner code changes.

**Guard.**
- Add a new test class to `runtime-core/src/repoTest/kotlin/skillbill/architecture/RuntimeEngineBoundaryArchitectureTest.kt`
  (the file already holds several engine-boundary test classes). The rule forbids
  deciding behaviour by step identity outside the `slot` package tree, in any
  spelling (parent "Meaning over names"). Its scan scope is an explicit list of the
  step-owned packages under
  `runtime-engine/src/main/kotlin/skillbill/engine/featuretask` named above. It is a
  list of packages the rule covers, not a list of exempt files. Subtask 7 widens it to
  every package outside `slot`. No file in scope may contain:
  1. a `FeatureTaskRuntimePhaseIds` constant or a
     `FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_*` constant
  2. a string literal equal to a step id
  3. element access on `PhaseSlot.<SLOT>.stepIds` (`[…]`, `first`, `last`, `single`,
     `get`)
  4. a reference to a `slot` declaration that re-exports a single step id
- To make form 4 checkable, `slot` declares no non-private property or constant whose
  value is a single step id, except each strategy's own `stepIds` and `entryStepId`.
  `FeatureTaskRuntimeStepSemantics` and anything like it does not exist.
- Put a before-and-after census of step-identity decisions outside `slot` in
  `census_subtask_5.md` (see Verification ownership), counting every form. Report two after counts:
  - the step-owned scope, which is zero
  - the run-loop internals left for subtask 7, per package
- The rule asserts it read at least one file, and has no baseline and no exemption.
  Its synthetic-violation test covers all four forms, including an alias object used
  from `runloop`. It calls the same scanning function as the real rule.
- Add a second rule to the same suite: no class under `skillbill.engine.featuretask`
  other than the `PhaseRunner` implementation depends on `GoalRunnerSubtaskLauncher`,
  by import or by qualified name. Strategy packages launch through their runner
  (subtasks 3 and 4). Same file-count assertion and synthetic violation. Subtask 8
  extends it to the phase-run entry package, and SKILL-382 to the operation packages.
- Add a third rule for the parent "Dependency direction" constraint:
  - Shared feature-task packages import no strategy package.
  - Strategy packages import none of the run loop's drive, launch, attempt, or
    planning-branch objects.
  - No class under `slot` references `FeatureTaskRuntimeRunState`.
  - Same file-count assertion and synthetic violations.
  - Subtask 7 adds the `FeatureTaskRuntimeRunLoopContext` clause to this rule. Don't
    add it here, and don't add it with an exemption.
- Update `FeatureTaskAuditRemainingCriteriaPackIndependenceArchitectureTest` for the
  moved audit retry code.

**Docs.**
- Extend the ARCHITECTURE.md "Phase slots and strategies" section with the strategy
  table and the rules the guard enforces as of this commit, including the step-owned
  scan scope. Don't describe strategies as context-free yet; that lands in subtask 7.
- Add "Adding a phase strategy" steps: write the class in its slot package, add it to
  the registry provider, then name it in `PhaseStrategySelection`.

## Verification ownership

The runtime commits in `commit_push`, and implement and audit can't run builds or tests.
So:
- The census lives in `.feature-specs/SKILL-380-phase-slot-strategies/census_subtask_5.md`,
  not in the commit message. Implement writes it and commits it with the subtask. Audit
  checks it against the tree.
- Criteria 3 and 6 (fixtures match) and the `./gradlew check` part of criterion 11 are
  verified in validate, not audit. Audit checks that the code and tests for them exist
  and doesn't list them as remaining just because nothing has run.
- Validate re-captures the fixtures with
  `cd runtime-kotlin && SKILL_BILL_SLOTBASELINE_CAPTURE=1 ./gradlew :runtime-engine:test --tests skillbill.engine.featuretask.slotbaseline.SlotBaselineCaptureTest`.
  It then reviews the fixture diff against the parent fixture ledger (only the
  write_history, pr, and settlement-instruction changes this subtask names), and runs
  `./gradlew check`. `SlotBaselineFixtureTest` in `check` fails on stale fixtures.

## Acceptance Criteria

1. Each of the seven strategies in the Scope table owns the listed behaviour in its `slot` package, and the shared step code it called in subtask 2 no longer contains that behaviour.
2. No phase-keyed directive table or phase-id `when` remains under `skillbill.engine.featuretask.phase.prompt`, and every composed prompt receives its task directive from the running strategy.
3. The goal planning sweep composes preplan and plan prompts from the registered default strategies, and its composed prompt text matches the subtask 1 fixture (verified in validate).
4. No production file in the step-owned packages decides behaviour by step identity in any of the four guarded forms. `census_subtask_5.md` shows zero for that scope and lists the run-loop-internal remainder per package for subtask 7.
5. The step-identity rule scans an explicit list of step-owned packages, with no exempt-file list or baseline. It fails on a synthetic violation in each of its four forms (constant, literal, `stepIds` element, alias object), reports the number of files it read, and passes on the tree. The launch-port rule fails on a synthetic run-loop file that depends on `GoalRunnerSubtaskLauncher` and passes on the tree. The dependency-direction rule fails on a synthetic shared file importing a strategy package and on a synthetic strategy referencing `FeatureTaskRuntimeRunState`, and passes on the tree. Each synthetic test calls its rule's own scanning function. `TREE_FORBIDDEN_STRATEGY_REFERENCES` and `slot/PhaseRunStateRules.kt` no longer exist.
6. Every subtask 1 fixture, including composed prompt text for every step, matches its latest baseline, except the write_history and pr fixtures this subtask re-baselines (re-captured and verified in validate).
7. ARCHITECTURE.md lists every slot's strategies and states the guard rule and the steps for adding a strategy.
8. write_history and pr settle with the uniform output; changed paths, history and decision changes, and PR identity are measured by the runtime; no production code decodes `history_result` or `pr_result` from agent output; and a run whose records predate the change resumes.
9. Every non-review step prompt carries only the minimal settlement instruction, phase-output validation for those steps checks only status, value, and failure disposition, the runner reads a minimal final object for any step name, and audit's verdict rules live in `acceptance-audit`.
10. Measured write_history and pr facts live in runtime-owned fields with owned keys, not in the agent's prose value. A measurement failure emits a record.
11. Every schema pin of the phase-output contract version equals the Kotlin constant (one test). Goal planning runs end to end, and `cd runtime-kotlin && ./gradlew check` passes at this subtask's commit (verified in validate).

## Non-goals

- Goal-runner code outside the planning-directive call (SKILL-378.3 owns goal-runner shape).
- runtime-domain skeleton data. The graph, projections, transitions, and `PROSE_PHASE_IDS` stay keyed by step id.
- Prompt wording changes, policy value changes, or merging strategies with their step code beyond moving it.
- Operator-facing strategy selection.
- Context-free strategies, the `FeatureTaskRuntimeRunLoopContext` clause, and the
  run-loop-internal step-identity cleanup (subtask 7).

## Dependency notes

- Depends on subtasks 3 and 4. It does not wait for another issue.
- Apply the engine visibility rule that is in force: strategies are `internal` except what the runtime-core registry provider needs.
- Add the no-phase-id rule to `RuntimeEngineBoundaryArchitectureTest` where that class lives.

## Validation strategy

The regressions to catch:

- a prompt that loses or reorders a section
- the plan decomposition stop firing for a goal child
- the audit retry losing its unchanged-remainder block
- the commit/push upstream-head fallback not running

What covers them:

- The subtask 1 prompt and byte fixtures.
- The existing audit, commit/push, and PR suites.
- The guard's synthetic violation.

Run `cd runtime-kotlin && ./gradlew check`, plus the engine and core suites. Run
`bill-unit-test-value-check` on changed tests.

## Next path

Continue to `spec_subtask_6_delegated-review-strategy.md`.

## Spec Path

.feature-specs/SKILL-380-phase-slot-strategies/spec_subtask_5_remaining-slots-and-guard.md
