# Skill Bill Runtime Architecture

This document defines architecture requirements and enforcement boundaries for `runtime-kotlin`.

## Design Principles

Apply these principles when designing, implementing, or reviewing runtime changes.
They are requirements for new and changed code. Existing violations remain tracked
work, not examples to copy. [SKILL-239](../.feature-specs/done/SKILL-239-runtime-architecture-ownership-and-simplicity/spec.md)
owns the implementation gaps identified below; publishing this document does not
mean those fixes or checks have landed. Kotlin coding patterns remain in
[Code Principles](../docs/code-principles.md).

### Dependencies And Responsibilities

Keep domain rules independent of entry frameworks and concrete adapters. Application
and engine code coordinate use cases through ports; adapters handle filesystem,
process, HTTP, and SQLite operations. The composition root wires implementations.
Use the declared module graph and package ownership below rather than introducing
another layer to satisfy an architecture label.

A component owns a responsibility whose changes can be understood together. A port
describes operations its consumer needs, not getters for another object's entire
dependency graph. An implementation must preserve the port's success, failure,
cancellation, and transaction semantics so a caller can substitute it without
changing its assumptions. Extend manifest-driven packs and injected process
strategies instead of adding identity branches to shared runners.

### State Ownership

Give each run one owner for coupled state transitions. Expose named transitions
and read-only results; do not expose mutable collections or session fields to
helper objects. Model mutually exclusive outcomes as alternatives in a closed
type, not independently nullable reports with accidental precedence.

Pass helpers the facts or capabilities they use. Moving an all-access run-loop
parameter into a context, callback bag, or role interface does not narrow access.
Reconstruction from durable records must preserve the same invariants as live
execution, including retry consumption, checkpoint ownership, and phase order.

Finding observations are shared by review, verification and remediation. Verification checkpoint
and boundary writes belong to `PhaseFindingVerificationState`; repair receipts belong to
`PhaseRepairReceiptState`. These roles check the active binding and accepted writer phase.
Planning briefing writes exist only on preplan/plan bindings and check the briefing phase.

Strategies receive `PhaseAcceptedStepExecution` and a private implementation for the accepted
step. Agent and review bindings implement `PhaseAgentExecution` for current-step launch. Gate and commit
bindings expose their owned cycle operations and do not implement the agent launch capability.
All bindings provide detached observations and admission checks. Planning,
review, quality-gate, commit and PR bindings add only their role operations. The coordinator
checks request identity, selected step and policy, authorizes dispatch, and closes each binding
when the strategy returns. Planning unit bindings require an authorized wave and keep separate
progress, session and records.

`PhaseStepCall` carries accepted metadata and its bound target. It has no runner callback or
prepared-launch operation. `PhaseStrategyRegistration` pairs each strategy with its runner in the
registry. The accepted attempt host resolves that runner only after required persistence. Ordinary
and gate strategies receive no raw runner. Goal planning resolves a runner for its accepted
planning call after required start persistence; its phase-bound launch state rejects another step.
Review strategies retain their review launch dependency inside the review slot. Strategies cannot
recover the host, records, gate context or finalization context from a binding. Runtime gate cycles and commit cycles live under `runloop.qualitygate`
and `runloop.finalization`; bindings invoke the selected operation rather than return its context.

`PhaseAttemptEnvironment` contains request facts only. Strategy hooks receive detached
progress/session observations and private role views. Audit settlement, planning stop, commit
upstream recovery and PR pre-launch push are named runtime operations. PR push resolves the
owned branch internally. Loop rules receive a readonly `PhaseLoopContext`; review and PR reads
use `PhaseRepositoryObservations`, whose private adapter exposes no Git writes. PR measurement
receives a single telemetry emitter instead of lifecycle terminal authority.

`FeatureTaskRuntimeRunTransitionOwner` owns coupled progress, session, accounting,
completion, re-entry, evidence and checkpoint state transitions. Each `FeatureTaskRuntimeRunState`
stores one owner paired with one session; a second session is rejected. Durable completion and
review tombstone writes precede corresponding in-memory changes. Required phase-start writes
precede attempt accounting and review reservations. Runtime checkpoint machinery retains its
storage and Git collaborators behind the owner operations. Durable and ephemeral records keep
their storage policies, including ephemeral audit briefings.

Progress and session getters return private wrappers over detached copies. Phase and loop reads
return `PhaseProgressObservation` and `LoopProgressObservation` values. Their collections,
buffers and terminal reports do not alias live storage, and casting an observation cannot recover
the live owner. Settlement coupling receives the owner directly from runtime context; it does
not reconstruct mutation authority from observations.

Selected strategies declare their execution binding role, and selected hooks declare their context role.
`FeatureTaskRuntimeRunLoopStepBindings` and `FeatureTaskRuntimeRunLoopHookViews` create private views for
that accepted step. Shared code does not select those views through phase-name constants. Review writes
recheck the active binding and selected review role. These role declarations do not change selection,
step identity, execution-plan digests or durable formats.

SQLite worker acquisition participates in the caller's admission transaction when present. Its
standalone adapter still owns a write transaction. A rejected admission rolls back the workflow
advance and worker lease together.

`StrategyCapabilityBoundaryArchitectureTest` uses Kotlin PSI to build a declaration graph across
all engine source. It follows consumer roots through helpers, extensions, aliases, constructors,
properties, factories and used parameter/return types. Same-name overload declarations are
combined conservatively so each candidate edge remains reachable. Extension functions with an
implicit run-state receiver are included in primitive-writer checks. Attempt and state folders
remain in the transitive catalog. Skeleton composition wiring is excluded as a consumer root.
Runtime runner implementations, registry, lookup and selection are also excluded as consumer roots,
and remain traversable when a consumer reaches them. Package-qualified helper calls, wildcard
imports and companion members contribute declaration edges. Wildcard imports retain all indexed
candidates, and value receivers shadow imported names.
Unresolved governed edges fail. Raw state and raw `PhaseRunner` authority are forbidden to
ordinary and gate strategy consumers; review authority is forbidden to non-review consumers.
Review consumers may reach the review launch runner through their review slot. Runtime registrations
retain other runners outside strategy objects, and the attempt host resolves the selected runner
through run state after admission. A typed primitive-writer inventory also checks helpers outside the consumer graph. Synthetic
allowed and violating cases prove these boundaries, and runtime binding checks prove accepted-step
admission. The rule is registered in
`PrincipleEnforcementInventory.enforceableRules`.

The operation-level inventory and retained runtime collaborator dispositions are in
`../.feature-specs/done/SKILL-384-workflow-skeleton-execution-contracts-and-state-ownership`.

#### Feature-task run-loop helper inputs (SKILL-247 subtask 3)

Investigation F-005 counted 122 `FeatureTaskRuntimeRunLoopContext` extension
functions across 15 files under `skillbill.engine.featuretask`, backed by a
17-field `FeatureTaskRuntimeRunLoopContext`. Before narrowing, extension counts
per helper family in that package were: PlanningBranch 0 (only a context wrapper
around `runPhase`), Drive 13, ValidationGate 21, AttemptSettlement 13, Review 8,
PhaseAttempts 4 (carried-forward adjacency). PlanningBranch also duplicated many
public APIs that differed only by accepting `FeatureTaskRuntimeRunLoop` versus
narrowed request/state/recorder/session parameters; PhaseRunner and
ValidationGate exposed companion entry points that took the run loop wholesale.

After narrowing (same census rules): PlanningBranch stays at 0 context extensions;
`runPhase`, `runPreparedPhase`, `buildPhaseRun`, and `phaseDeclarationForRun` take
`RunPhaseArgs` and/or `FeatureTaskRuntimeRunLoopContext` without constructing
`FeatureTaskRuntimeRunLoop`. Drive retains 2 context extensions —
`invalidateReviewGenerationIfNeeded` and `runPhaseDriveLoop` — because the drive
orchestrator still owns session transition wiring and the forward phase loop;
resume, entry-gate, carried-forward review, advance routing, and cap exhaustion
call sites use object functions with explicit request/state/recorder/session (or
context passed as a value, not as an all-access receiver). ValidationGate keeps
context extensions only at the gate-cycle and phase-attempt orchestration seams:
the gate coordinator's agent-turn callbacks and the generic fix-loop launcher
still need the launch, activity, diagnostics, clock, transition, and session
ports together. Build/validation settlement, pack-command routing, and
repository-checkpoint calculation use explicit arguments. Carried-forward goal
review settlement uses `CarriedForwardGoalReviewArgs`;
PhaseRunner and PlanningBranch enter through the phase-boundary state and
observability values. AttemptSettlement moves
`gateOutput` / `settleValidatedOutput` / prose settlement off the context
receiver; `GateOutput` and `SettleValidatedOutput` carry the
request/state/recorder/phaseGates/clock/diagnostics/
goalContinuationRecorder/phaseSettlementService ports those paths use.
`settlementContext` on those args remains only for the not-yet-peeled
audit/checkpoint and accepted-output persistence tail inside
`settleValidatedOutputAfterFingerprint`; implement-fix repair-receipt settlement
and commit finalisation now receive their request/state/recorder/goal-recorder/
diagnostics ports directly. Review runs in `InlineReviewStrategy` and reaches review-owned persistence
through the accepted step binding (`PhaseReviewStepBinding` on the active
`PhaseStepCall`), not by reopening step state from `PhaseRunState.step`.
SKILL-384 closes the review boundary with private bindings for each step role.
`GateOutput` retains a runtime-only `PhaseOutputSettlementContext`; strategy hooks receive
separate detached views.
PhaseAttempts keeps `blockAndPersist` context and top-level overloads; governed
block paths prefer the top-level `blockAndPersist(request, state, recorder,
goalContinuationRecorder, args)` seam. `FeatureTaskRuntimeRunLoop` exposes only
`drive()`, `report()`, and `applyOperatorDecision()` publicly; collaborator
fields are internal to `FeatureTaskRuntimeRunLoopContext`.

The named-family census is now PlanningBranch 0, Drive 2, ValidationGate 9,
AttemptSettlement 3, Review 6, PhaseAttempts 4, and CommitPush 9 context
extensions, down from 0, 13, 21, 13, 8, and 4 respectively for the pre-existing
families. The remaining groups have these
inputs:

- PlanningBranch pure declarations and cap reasons take request facts, values,
  recorder reads, or explicit state/session ports; `runPhase` and
  `runPreparedPhase` retain the phase-boundary context for launch preparation.
- Drive resume and routing calculations take request/state/recorder/
  goal-recorder/transition values; carried-forward review takes
  `CarriedForwardGoalReviewArgs`; only the forward drive loop and review
  generation invalidation retain context.
- ValidationGate settlement takes request/state/recorder/goal-recorder,
  output-validator, phase-gates, observability, and session only for the
  validation checkpoint lookup; gate-cycle and fix-loop orchestration retains
  context for the launch callback graph.
- AttemptSettlement gate output takes `GateOutput`; validated output
  settlement takes `SettleValidatedOutput`; implement-fix receipt
  settlement takes request/state/recorder/goal-recorder/diagnostics; the
  audit/checkpoint and accepted-output persistence tail retains
  `settlementContext`.
- Review preparation and the review step live in the `code_review` slot
  (`slot.codereview`). Their binding admits each review mutation only while
  the accepted step is active. `PhaseReviewExecutionContext` supplies repository
  observations, the output validator, and the clock; it has no git writer or
  attempt scope. Review launch and persistence pass through the accepted-step
  binding and its owning recorder operations. Since SKILL-380 subtask 7, no
  slot class takes the run-loop context.
- PhaseAttempts exposes top-level block/pause seams with request/state/
  recorder/goal-recorder/observability arguments; its context overloads remain
  only for the generic attempt-loop adjacency.

New helpers must not reintroduce run-loop or context-all-access parameters when
a narrowed overload already exists; retained broad inputs require a concrete,
current orchestration requirement documented here or in the owning area
`agent/decisions.md`.

### Resource Lifetime And Failure

Successful acquisition immediately establishes one cleanup owner for a child
process, stream, drain, endpoint, or lease. Every exit goes through that owner's
cleanup, including callback exceptions and cancellation. A shutdown hook is a
last resort, not the normal release path. Only terminate resources the invocation
owns, with the existing identity and fencing checks.

Bound cleanup and drain settlement. Do not publish mutable or incomplete capture
as settled evidence. Preserve the primary failure if teardown also fails, retain
cancellation signals, and record secondary failures through an independent,
bounded diagnostic path. Follow the [observability policy](../docs/observability-policy.md)
for degradation and fallback; failure must not silently become normal absence.

### Durable State And Projections

Name the authoritative store and the transaction owner for each mutation. Keep
related database changes atomic. SQLite and a filesystem projection do not share
a transaction: distinguish committed state from projection success or failure.
Regenerate a failed projection from authoritative state without replaying an
already committed workflow mutation.

Keep absent, completed, and failed operations distinguishable in boundary results.
An adapter callback inside a transaction must preserve that transaction's snapshot
and ownership. Do not move it outside merely to simplify a dependency diagram.

### Database Readiness And Routine Work

Separate database readiness from opening a connection for ordinary work. Once
readiness succeeds, routine writes must not rerun historical backfills or full-table
repair scans. Keep required connection setup and the requested transaction.

Failed initialization must not publish readiness. Concurrent initialization,
database replacement at the same path, and explicit reset must preserve recovery.
Run compatibility repair at a documented initialization or recovery boundary;
do not remove required repair or cache success forever by pathname alone.
Measure repeated work before adding a cache, connection pool, or replacement library.

Write readiness is keyed by ledger-stamped `PRAGMA user_version` plus stable file
identity (`DatabaseIdentity`). `DatabaseMigrations.apply` sets `user_version` to the
highest applied migration version in the same transaction that records ledger rows.
`DatabaseRuntime.establishSchemaReadiness` runs only `DatabaseSchema.createBaseSchema`
and `DatabaseMigrations.apply`; column ensure, diagnostic-evidence heal, work-list
heal, and one-time legacy goal-runner and telemetry repairs are named ledger migrations.

`DatabaseWriteReadinessGate` compares the `DatabaseIdentity` snapshot already read
for each cache decision (`DatabaseIdentity.matches`) instead of rereading the file
through `matchesFile`. Unreadable database files raise a `DatabaseFailureCode.ACCESS` failure for `READ` from
identity read and do not trigger migrate-on-access re-establishment. The synchronized
initialization path still performs a second identity observation after acquiring the lock.
`DatabaseWriteReadinessTest` exercises the gate through `sqliteSessionFactoryForTests`
and recording diagnostics instead of production-only counters.

`:runtime-infra:sqlite` `testFixtures` own cross-module database evidence:
`establishTemporarySchemaReadiness` returns a temp directory, database path, and
connection; `ensureTestDatabase` applies schema readiness only; `sqliteSessionFactoryForTests`
builds the production session factory with `SqliteTestDiagnostics`. Consumer modules
(`runtime-cli`, `runtime-core`, `runtime-mcp`, `runtime-engine`) depend on those
fixtures and must not import `skillbill.infrastructure.sqlite.core` from tests.
`withGoalRunnerControlRepository` hands a caller a `GoalRunnerControlRepository` over a
reopenable database path so engine-owned control coordination can be regressed against
real SQLite without importing the adapter.

Public SQLite DI surface stays limited to `SQLiteDatabaseSessionFactory`,
`SqliteFeatureTaskPhaseSettlementRepository`, and `SqliteExperimentPairOwnerStore`.
Goal-runner coordination is owned by `runtime-engine`, not the adapter, so
`runtime-infra/sqlite` production code declares no public top-level function.
Other adapters are `internal`; repositories mutate only inside
`SQLiteDatabaseSessionFactory` write/read transaction callbacks.

### Contract Ownership And Enforcement

Canonical schemas own wire shape. Kotlin contract owners declare keys and versions;
consumers reference those declarations. Keep open extension payloads distinct from
governed envelopes. Type state whose invariants need compiler protection without
closing manifest-authored extension vocabularies.

A check for missing key ownership needs authority independent of existing key
declarations. A key absent from the owner inventory must not escape enforcement
because the scanner only searches that inventory. Test newly introduced schema
fields and undeclared literals, as well as valid references and allowed extensions.
State exactly which boundaries a scanner covers.

### Simplicity And Change Cost

Keep an abstraction only when a current consumer, adapter boundary, or useful test
substitute needs it. One production adapter can justify a hexagonal port. Repeating
its dependencies in a same-module interface and implementation usually cannot.
Delete dead helpers and pure forwarding layers before adding another abstraction.

Use the existing modules and tooling unless a concrete requirement justifies a
change. Do not add speculative extensibility, a generic workflow framework, or a
blanket identifier-wrapper migration. Required schema validation, typed failures,
compatibility recovery, lease fencing, and durable evidence remain requirements.

File size and constructor arity are signals, not definitions of cohesion. Do not
split by count, merge unrelated responsibilities, or hide dependencies in bags to
satisfy a threshold. Numeric limits and exemptions belong to their existing
enforcement owners, not another handwritten table in this document.

### Tests And Evidence

Before adding a test, name the concrete regression it catches. Prefer observable
boundaries: a child is gone after failure, a write rolls back, a projection recovers,
or resumed execution agrees with durable state. Exercise a scanner through its
real entry point instead of reproducing its algorithm in a test.

Keep schema, compatibility, transaction, and lease tests. Remove assertions that
only pin incidental prose, trivial forwarding, or implementation structure without
protecting a contract. Do not pin exact SQL counts or use timing assertions where
the required property is absence of repeated maintenance.

### Enforcement Status

Review applies these requirements now. Mechanical checks prove only their tested
scope. `PrincipleEnforcementInventory.enforceableRules` is the record of what is
mechanically checked: each entry pairs a rule with the test class that proves it,
so a rule without a surviving test cannot stay listed.
`PrincipleEnforcementInventory.reviewOnlyRules` records the requirements that stay
review-only. Do not expand an exemption to make a change pass.

These requirements are reviewed by hand and have no mechanical guard:

- Cleanup after callback failure and incomplete drain settlement.
- Redundant role interfaces and application forwarders.

Neither a green source scan nor an archived spec establishes universal compliance
with Clean Architecture, SOLID, or YAGNI.

## DB-first feature-task continuation

Feature-task continuation is repository-scoped and database-authoritative. At workflow creation, an immutable identity row binds the workflow id to a normalized issue key, canonical real-path Git-root identity, repository-relative governed spec path, persisted mode, and standalone/goal-child route scope. Read-only lookup never chooses among multiple eligible rows by timestamp.

The feature `spec.md` remains the governed product contract; it is not a mutable workflow ledger. A sibling `decomposition-manifest.yaml` is the sole prepared-feature authority marker and always contains one or more executable subtasks; a bare `spec.md` is preparation intake. Continuation lookup remains authoritative and precedes artifact discovery. Pre-planning, planning, phase outputs, and the phase ledger remain durable database artifacts. Initial implementation continuation is hydrated from the completed `plan`. Audit uses three steps in the acceptance-audit slot. The read-only `audit` step uses the configured reasoning model. Its first pass inspects the production behavior of every planned acceptance criterion against current code and reports remaining production gaps. Later passes inspect only the unresolved criteria in the last accepted report. Previously satisfied criteria stay closed. Audit excludes all test requirements, including explicit test-only criteria and the test portions of mixed criteria, without changing the spec. A nonempty list enters the `audit_repair` loop at read-only `audit_plan_fix`, which uses the reasoning model to plan each reported production gap. Each plan item names its criterion, missing behavior, production path, ordered changes, and closure evidence. Repair plans are prose; the runtime does not parse their headings, labels, or criterion coverage. Ordinary phase records persist the accepted repair plan before `audit_implement_fix` uses the implementation model to execute it. Repair receives the saved repair plan, latest audit findings, and original feature plan. The repair step reconciles production behavior in the current tree, excludes test requests from persisted findings, saves its output, and returns to an audit of the unresolved criteria. Only an explicit empty list completes audit and allows review. Each step has separate attempt attribution. Repair continues incomplete output and retryable failures before another audit, carrying saved repair reports and preserving applied edits. Retryable terminal failures stop after the three-attempt retry budget, while incomplete completed output follows the existing continuation and wall-clock limits. A concrete needs_user_action or non_retryable_policy_conflict output still blocks for operator intervention. A remaining list that shrinks after repair is progress. At most two non-shrinking rounds (an unchanged, replaced, or grown list) relaunch repair, each recorded as an `audit_non_shrinking_round` ledger continuation; the next non-shrinking round blocks and retains the latest normalized audit findings. An operator-authorized audit retry may establish one fresh baseline after identity validation. The completion ledger consumes that authorization, and the non-shrinking budget is per subtask workflow, so that retry does not reset it. The loop checkpoints before repair, retains interrupted repairs for resume, and warns after three repair rounds without imposing a round cap while progress continues. Audit, repair planning, and repair do not run builds or tests. Validation owns execution. Ordinary phase records and the phase ledger own the findings handoff and resume state; no per-criterion gap store is introduced. Historical `audit_gap` state still normalizes through the existing compatibility readers. Uniform prose steps report reconciliation in their value; the legacy top-level reconciliation gate applies only to structured mutating outputs. Review remediation is a single bounded round: `review` runs once, `changes_requested` may launch one `implement_fix` pass (`review_fix` cap 1, then advance to `validate`), and review does not run again after that fix.

Decomposed goals execute discovery and preplan once at the parent, then persist a distinct immutable plan checkpoint for each ordered subtask. Normalized checkpoint tables are the continuation authority. Status reads only bounded fields: shared-preplan readiness, planned and total counts, first missing subtask, and a concise reason. Resume reuses compatible checkpoints. The bounded preparation 0.2 and phase-output 0.6-to-0.7 migration runs before spec-drift recovery, readiness reads, and child execution. One immediate transaction validates source schemas and exact digests, converts payloads, validates targets, and updates coupled checkpoint and import fields. It preserves descriptors, ledger history, completed and skipped subtasks, commits, and checkpoint ownership. Unsupported or corrupt saved records block without deletion or regeneration. Explicit hard reset remains a separate operator action.

Packaged CLI and MCP candidates check their own bundled schema pins through a database-free composition root. The installer runs `--check-packaged-contracts` on both staged images before promoting either image. This check runs before normal runtime composition can open the durable database.

Child creation hydrates the shared preplan and child's plan as completed dependencies with goal-planning provenance. They add no child execution duration, tokens, or agent attribution. Standalone feature-task workflows retain directly executed and attributed preplan and plan phases.

Runtime worker ownership is mutable state kept separately from immutable execution identity. A worker lease records a random owner token, monotonic fencing generation, host and boot identity, PID plus process-birth evidence, heartbeat/expiry, and the incomplete phase attempt. Every heartbeat, phase write, takeover reservation, transfer, and release must match both token and generation. Process liveness is exact only when host, boot, PID, and birth evidence agree; unverifiable or mismatched ownership must fail loudly instead of terminating a process or creating a replacement workflow. Confirmed takeover first reserves ownership with compare-and-set, then requests graceful shutdown and escalates only if the same process identity remains live.

A non-terminal row orphaned by a killed child process self-heals: when its worker lease has expired and the injected supervisor confirms the process dead (only `NotRunning`; a live process, an active lease, or ambiguous evidence is left untouched), the row transitions to the resumable `pending` state at its existing step and the lease is released under owner-token/generation fencing. The pass runs unconditionally at runner startup and in the goal parent's child-supervision seam, before the resume point is resolved, and is idempotent and concurrent-safe through the lease machinery. Manual lease clearing and out-of-band row deletion remain the corruption fallback only.

The runtime uses a hexagonal JVM graph with entry adapters at the outside,
application use cases in the orchestration layer, ports as the dependency
boundary, domain models and rules below the ports, and concrete infrastructure
behind those ports. `runtime-core` is only the composition root and runtime
metadata module.

```text
runtime-cli / runtime-mcp data gateways
  -> runtime-application use cases and the pinned runtime-engine inbound API
    -> runtime-ports
      -> runtime-domain models and rules
      -> runtime-contracts helpers for port-owned boundary payload contracts

runtime-engine
  -> runtime-ports + runtime-domain + runtime-contracts (api)
  -> runtime-application (implementation)

runtime-infra/host / runtime-infra/contracts / runtime-infra/skills /
  runtime-infra/launcher / runtime-infra/workflow / runtime-infra/http /
  runtime-infra/sqlite
  -> runtime-ports + runtime-domain + runtime-contracts
  (skills, launcher, and workflow also depend on sibling infrastructure modules)

runtime-core
  -> application services, engine services, ports, domain, and concrete adapters for DI wiring
```

## Gradle Modules

- `runtime-contracts`: the shared kernel. It holds only declarations that two or
  more production modules read or that a `runtime-ports` signature exposes:
  contract DTOs, JSON/ordered-map helpers, runtime surface contracts,
  `*_CONTRACT_VERSION` constants (plus the two top-level `*_SCHEMA_ID` constants
  read by ports, engine, and sqlite), and the `skillbill.error` failure codes (owner
  `RuntimeFailureCode` enums) and the single runtime failure type `SkillBillRuntimeException`. `*SchemaPaths` locators and `logSchemaLoadFailure` are **not** here:
  they live in `skillbill.infrastructure.contracts.locator` in
  `runtime-infra/contracts`, the module that stages the canonical YAML. A
  declaration with a single owner lives in that owner's module (SKILL-374). It no longer owns the JSON-Schema
  validators or their schema-resource copy tasks; those moved to
  `runtime-infra/contracts`, except the platform-pack and native-agent composition
  validators, which moved to `runtime-infra/skills` (see below). It also owns `skillbill.contracts.time.JvmSystemClock`,
  the single ambient wall-clock seam (UTC default zone, millisecond precision, and live
  JDK-clock delegation in `instant()` and `withZone`), which
  cannot live in `runtime-domain` because domain effect purity forbids ambient time reads.
  `skillbill.error.featuretask.FeatureTaskRuntimePhaseOutputFailureCode` owns the eleven
  phase-output failure wire tokens and their coarse `FeatureTaskRuntimePhaseOutputFailureKind`
  mapping; `coarseFailureKindForPhaseOutputWireCode` sits beside it and delegates to that
  enum. The `skillbill.error.*` packages are acyclic: `SkillBillRuntimeException`,
  `RuntimeFailureCode`, the transitional `LegacyFailureCode`, `ShellContentContractException`, and
  the `FailureWireCode` contract live in `skillbill.error.core`, feature-task failure
  vocabulary in `skillbill.error.featuretask`, and per-surface shell-content errors in
  `skillbill.error.shellcontent`, which depends on both.
- `runtime-domain`: pure agent-add-on, learning, review, telemetry, workflow,
  install-plan, scaffold, and skill-remove models/rules. Public domain data
  types live in area-owned `model` packages, including the
  `skillbill.model.FileLocation` value type that carries repo paths through domain
  signatures without a `java.nio` dependency.
- `runtime-ports`: holds only contracts that cross a module boundary: interfaces
  and DTOs implemented or consumed in more than one module, plus derived
  extensions on its own types. That covers `skillbill.model.EnvironmentContext`, the
  `skillbill.model.RuntimeVersion` packaged-version value type, persistence sessions,
  repositories, gateway interfaces, telemetry port interfaces, workflow git
  operations, decomposition-manifest file-store and validator ports, port-owned
  model types, and shared payload projection for
  boundary events that must be consumed by both application and infrastructure
  adapters. Repository-driving behaviour — decomposition manifest and parent
  discovery, projection-failure persistence, goal-parent artifact projection —
  lives in `runtime-application` or `runtime-engine`, not in ports.
  `java.nio.file.Path` is the path type in port signatures; the
  `skillbill.model.toPath` and `skillbill.ports.repository.toFileLocation` bridges
  convert between that `Path` and the `FileLocation` domain value.
- `runtime-application`: CLI/MCP/shared use cases outside the engine run loop,
  workflow orchestration, telemetry lifecycle orchestration,
  presenter-to-contract mapping, and validated decomposition-manifest file/artifact projection through workflow ports.
- `runtime-engine`: feature-task run loop, goal runner, goal planning, and
  planning projection use cases. Goal-runner coordination that only composes
  ports — control state, manifest projection, outcome reconciliation, block
  writes, scoped replan, and child repair — lives here rather than in the SQLite
  adapter, which supplies only the session factory and the repositories it opens.
  It also owns the agent-output helpers
  (`skillbill.engine.agentoutput`) and the worktree edit journal writer
  (`skillbill.engine.worktreeedit`). It depends on `runtime-application` through
  `implementation` for the shared services those loops call today and exposes a
  pinned inbound API through `RuntimeComponent`.
- `runtime-infra/sqlite` (`:runtime-infra:sqlite`): SQLite schema, migrations, connection/session
  behavior, SQL-backed repositories, review persistence, review stats, and
  telemetry outbox persistence.
- `runtime-infra/http` (`:runtime-infra:http`): telemetry HTTP client/requester implementation and
  telemetry proxy payload mapping.
- `runtime-infra/host` (`:runtime-infra:host`): JDK and host-environment ports,
  repository-root resolution, telemetry config file storage and paths, filesystem
  primitives, and one-shot process execution — the bounded external process
  runner, git process invocation, gate JVM resolution, git-tracked-file listing,
  and `InstallerProcessAdapter`.
- `runtime-infra/contracts` (`:runtime-infra:contracts`): the concrete JSON-Schema
  validators (`InstallPlanSchemaValidator`, `WorkflowStateSchemaValidator`,
  `DecompositionManifestSchemaValidator` with its
  `DecompositionManifestCoherenceValidator`, `GoalProgressEventSchemaValidator`,
  and `IdeStatusSchemaValidator`) plus their schema-resource copy tasks
  (`copyInstallPlanSchema`, `copyWorkflowStateSchema`,
  `copyDecompositionManifestSchema`, `copyDecompositionManifestBundleJournalSchema`),
  phase-output repair engines, and workflow wire mappers. All are reached only
  through domain-neutral ports.
- `runtime-infra/skills` (`:runtime-infra:skills`): install plan/apply, install
  staging, governed scaffold load/render, repo validation, agent-add-on and
  native-agent discovery, rendering, and linking, skill-remove filesystem
  cascades, and the pack-side validators `AgentAddonSchemaValidator`,
  `PlatformPackSchemaValidator`, and `NativeAgentCompositionSchemaValidator`.
- `runtime-infra/launcher` (`:runtime-infra:launcher`): long-running agent and
  review child-process launch, launcher MCP registration, and the governed review
  evidence JSON-RPC codec objects internal to
  `skillbill.infrastructure.launcher.review` (not `runtime-ports`). The injected
  `FileSystemAgentRunLauncher` constructor takes the composition-selected
  `ExecutableLookup` (explicit callback override, else default PATH discovery);
  availability policy is not a process sandbox.
- `runtime-infra/workflow` (`:runtime-infra:workflow`): git workflow operations,
  review evidence adapters, feature-task stores, goal-planning discovery,
  validation gates, and decomposition-manifest file storage including the bundle
  journal.
- `runtime-core`: Kotlin-Inject component definitions and DI
  providers. It may know concrete adapters only inside composition code.
  `runtime-core` publishes only the generated Kotlin-Inject ABI edges that its
  public `RuntimeComponent` exposes today: `runtime-application` service types,
  the pinned `runtime-engine` inbound API, and `runtime-ports` context/port types.
  It does not publish `runtime-domain`, `runtime-contracts`, or concrete
  infrastructure modules as API dependencies. Because those generated service,
  engine, and port types have their own public signatures, the transitive public
  ABI closure is currently runtime-application, runtime-engine, runtime-ports,
  runtime-domain, and runtime-contracts; that closure is tested and must not grow
  into infrastructure or entrypoint modules.
  Downstream entry adapters and tests still declare the modules they use
  directly instead of treating `runtime-core` as a broad dependency umbrella.
  If Kotlin-Inject ever requires another generated ABI edge to be public, the
  exact edge and generated type must be documented here and mirrored by an
  architecture test. SKILL-52.2 subtask 5 adds
  `RuntimeCoreCompositionOnlyTest` as a no-regression guard: the exact
  `api(project(...))` and `implementation(project(...))` edge sets on
  `runtime-core/build.gradle.kts` are pinned, and the test fails if any
  infrastructure (`runtime-infra-*`) or entrypoint (`runtime-cli`, `runtime-mcp`)
  module ever appears as `api(...)`.
- `runtime-cli`: Clikt command tree, option validation, terminal rendering,
  JSON output, help, completion surfaces, and CLI runtime context creation.
  SKILL-52.2 subtask 5 narrows the main-source project dependency allow-list to
  `runtime-application`, `runtime-contracts`, `runtime-core`, `runtime-domain`,
  `runtime-engine`, and `runtime-ports`. Every `runtime-infra` module is dropped
  — runtime-cli has no concrete `skillbill.infrastructure.*` imports outside
  test sources; the infrastructure adapters are resolved through
  `RuntimeComponent` (kotlin-inject). The allow-list is enforced by
  `RuntimeAdapterDependencyAllowlistTest`.
- `runtime-mcp`: MCP adapter surface, MCP-specific payload shaping, stdio
  server, MCP telemetry schema validation, and MCP runtime context creation.
  SKILL-52.2 subtask 5 narrows the main-source project dependency allow-list to
  `runtime-application`, `runtime-contracts`, `runtime-core`, `runtime-domain`,
  `runtime-engine`, and `runtime-ports`. Every `runtime-infra` module is dropped
  — runtime-mcp has no concrete `skillbill.infrastructure.*` imports outside
  test sources; the infrastructure adapters are resolved through
  `RuntimeComponent`. The allow-list is enforced by
  `RuntimeAdapterDependencyAllowlistTest`.

Inside `runtime-mcp` the advertised surface has a single shape. `McpToolRegistry`
holds one ordered `List<McpTool>`; each entry names the tool once and carries its
description, handler, optional argument normalizer, runtime-owned argument keys,
and optional advertised enum subset. `tools/list`, schema projection
(`McpInputSchemaProjection`), envelope validation, and dispatch all read that one
list, so a tool name appears only in its declaration. `McpStdioServer.handleLine`
is the only request entry point and `McpToolDispatcher.dispatch` the only tool
entry point; both take the `McpComponent` explicitly, so there is no reflective
component lookup and no `Any`-typed component parameter. Handlers live in
per-family files (`core`, `review`, `lifecycle`, `telemetry`, `workflow`,
`featuretask`, `scaffold`, `system`), take `(McpToolArguments, McpComponent)`, and
call their application service directly through the component rather than through
a forwarding runtime object. `McpComponent` declares only the services those
handlers and `Main` read. There is no feature-task open/continue surface and no
decomposition result mapping: the decomposition arms of
`WorkflowContinueResult` are unreachable for verify workflows and throw
`UnsupportedOperationException`. Tests reach tools only through `handleLine` or
`dispatch`, and test sources live in the same family packages as the main code
they exercise.

The Gradle module set is:

```text
runtime-application
runtime-contracts
runtime-core
runtime-domain
runtime-engine
runtime-infra
runtime-infra:host
runtime-infra:contracts
runtime-infra:skills
runtime-infra:launcher
runtime-infra:workflow
runtime-infra:http
runtime-infra:sqlite
runtime-cli
runtime-mcp
runtime-ports
```

Nested infrastructure Gradle project ids are `:runtime-infra:host`, `:runtime-infra:contracts`,
`:runtime-infra:skills`, `:runtime-infra:launcher`, `:runtime-infra:workflow`, `:runtime-infra:http`,
and `:runtime-infra:sqlite`.

## Package Ownership

- `skillbill`: runtime metadata that is safe for all runtime modules to read.
- `skillbill.di`: Kotlin-Inject composition root, owned by `runtime-core`.
  `RuntimeComponent` mixes in one `Runtime<Area>Provides` interface per area
  (install, telemetry, goal planning, goal runner, review, feature task,
  workflow, validators, scaffold, diagnostics); an area with more than ten
  provides splits by its own sub-area (`RuntimeFeatureSpecProvides`,
  `RuntimeReviewAddonCatalogProvides`, `RuntimeScaffoldValidationProvides`,
  `RuntimeGoalPlanningSweepProvides`), never by pairing two areas. Each
  `@Provides` is declared once, and `RuntimeBootstrapBindings` holds only the
  ambient construction seam. `runtime-core` is the single composition root; the
  abstract properties on `RuntimeComponent` are the export list that generated
  Kotlin-Inject child components read, and every accessor has a reader. Providers
  bind one port to one `@Inject` implementation; there are no parameter-bag
  classes. The composition inputs `RuntimeContext`, `TransportContext`,
  `WorkflowOpsContext`, and `OptionalCallbacks` are declared in
  `skillbill.di.core`, while `EnvironmentContext` stays in `runtime-ports`.
  `@Provides` methods such as `runtimeContext` and `databaseSessionFactory` are
  the integration surface and are not duplicated
  in a second signature table. Any other public function on `RuntimeComponent`
  or a `Runtime*Provides` mixin is rejected even when abstract properties are
  unchanged. `RuntimeComponent` memoizes the first
  `RuntimeBootstrapBindings.runtimeContext` result for the component instance;
  later `runtimeContext()` calls and generated CLI or MCP parent access reuse that
  snapshot, so database, telemetry config, and transport requester selection
  cannot drift when ambient `user.home` or PATH changes mid-invocation. A new
  component may resolve fresh ambient facts; there is no process-global context
  cache.
- `skillbill.application`: use cases, workflow orchestration, lifecycle
  telemetry orchestration, repository-port coordination, and application-owned
  mappers. Public inputs and results live in area-owned `skillbill.application.<area>.model` packages.
- area-owned `skillbill.application.<area>.model` packages: public application input/result models.
- Runtime production packages group cohesive noun families beneath their area
  package. Non-model packages contain at most 12 sibling Kotlin files; an
  area-owned model package may contain at most 20 because public inputs and
  results share that boundary. When a package has multiple noun families,
  place each family in a child package rather than growing the parent.
- `skillbill.model`: shared runtime model types that are not owned by a
  narrower area: `EnvironmentContext`, `RepositoryRoot`, and `RuntimeVersion`.
  The composition inputs `RuntimeContext`, `TransportContext`,
  `WorkflowOpsContext`, and `OptionalCallbacks` belong to the composition root
  and live in `skillbill.di.core`.
- `skillbill.config.*`: repo-local configuration domain models and resolution
  policy owned by `runtime-domain`.
- `skillbill.ports.*`: port contracts for persistence, install, scaffold,
  validation, telemetry, workflow git operations, and decomposition-manifest
  file storage. Public port DTOs and results live in
  `skillbill.ports.*.model`; shared adapter-facing payload projection may live
  there when both application and infrastructure need the same boundary
  contract.
- `skillbill.contracts.*`: contract DTOs, JSON helpers, runtime surface
  contracts, and `*_CONTRACT_VERSION` / `*_SCHEMA_ID` constants, each read by two
  or more production modules or exposed by a `runtime-ports` signature. The
  package compiles only in `runtime-contracts`.
  That module loads nothing: it declares only `kotlinx-serialization-json`, stages
  no resources, and an architecture guard bans `org.yaml.`, `java.io.`, and
  `getResourceAsStream` from its sources. Values that used to be read out of
  packaged YAML are now compile-time constants owned by their reading layer —
  verification caps and reporting bounds in `skillbill.ports…GoalPlanningContext`,
  the discovery exclusion list in `skillbill.goalrunner.planning.GoalPlanningExcludedPaths`,
  issue-key shape in `skillbill.contracts.issuekey.IssueKeys` — and a repo test in
  `runtime-infra/contracts` asserts `issue-key-schema.yaml` still agrees with
  `MAX_ISSUE_KEY_LENGTH`.
  `JsonCodec` exposes strict array
  parsing for callers that must distinguish malformed text, wrong roots, and empty
  arrays, while tolerant object probes remain for optional external text. Numeric
  conversion preserves exact `BigInteger`/`BigDecimal` values; unsupported map keys
  and value types fail explicitly. Telemetry and update-check callers choose failure
  or bounded degradation when durable JSON arrays are corrupt.
  Mapping from application/domain/port models into contract DTOs belongs in
  application or adapter-owned packages.
- `skillbill.infrastructure.contracts.locator`: every `*SchemaPaths` locator and
  `logSchemaLoadFailure`, owned by `runtime-infra/contracts` because that module
  stages the canonical schema resources. `RuntimeArchitectureTest` fails any
  `*SchemaPaths` object declared outside an `skillbill.infrastructure.*` package.
- `skillbill.infrastructure.contracts.*`: the schema validator classes, compiled
  into `runtime-infra/contracts` under
  `skillbill.infrastructure.contracts` and its subpackages
  (`SchemaValidatorLocale`, `install.InstallPlanSchemaValidator`,
  `review.ReviewContextSchemaValidator` and `ReviewContextSchemaLocator`, and
  the workflow, feature-task, goal, and decomposition schema validators plus
  validator-only helpers such as `IssueKeySchemaRefInlining`). Phase-output
  structural repair and strict parsing live in `skillbill.infrastructure.contracts.phaseoutput`,
  not under `skillbill.infrastructure.contracts`, because they are
  adapter-owned parse/repair engines rather than schema validators.
- `skillbill.error`: failure codes (`RuntimeFailureCode` enums) and the single runtime failure type `SkillBillRuntimeException`.
- `skillbill.agent.model`: phase handoff inputs and the prose `PhaseOutput` (`value`, optional `prompt`) owned by `runtime-domain`.
- `skillbill.infrastructure.skills.agentaddon`: governed agent-add-on filesystem
  discovery and schema validation owned by `runtime-infra/skills`;
  `skillbill.agentaddon.model` holds the typed declaration models owned by
  `runtime-domain`.
- `skillbill.workflow.engine` and `skillbill.workflow.engine.model`: workflow
  engine, snapshot codec, continuation assembly, and engine models owned by
  `runtime-domain`.
- `skillbill.workflow.decomposition` and
  `skillbill.workflow.decomposition.model`: decomposition manifest codec,
  wire-map conversion, and decomposition models owned by `runtime-domain`.
- `skillbill.workflow.model.goalobservability`: goal observability models and
  parsing owned by `runtime-domain`.
- `skillbill.workflow.taskruntime.*` and
  `skillbill.workflow.taskruntime.model.*`: feature-task runtime phase workflow,
  handoff projections, phase records, and taskruntime models owned by
  `runtime-domain`. The declared packages are `taskruntime.artifact`,
  `.handoff`, `.phase.task`, `.phaseartifacts`, `.validation`, and
  `taskruntime.model.{audit, core, feature, handoff, handoff.assembly,
  handoff.task, persistence, phase, repair, review, skeleton, validation}`.
- `skillbill.workflow.model.goalreview` and
  `skillbill.workflow.model.persistence.artifact`: shared goal-review vocabulary
  and durable artifact-map access owned by `runtime-domain`. These lower model
  packages are the common vocabulary below their workflow consumers.
- `skillbill.review.parsing`: review finding and lane parsing owned by
  `runtime-domain`; review model types remain under `skillbill.review.model`.
- `skillbill.workflow.specsource`: spec-source reading owned by
  `runtime-domain`.
- `skillbill.workflow.verify`: Feature Verify workflow definition
  (`FeatureVerifyWorkflowDefinition`) owned by `runtime-domain`.
- `skillbill.goalrunner`, `skillbill.goalrunner.model`, and
  `skillbill.goalrunner.ledger`: pure goal-runner liveness policy,
  worker-subtask parsing, status projection, accounting, and attempt-ledger
  models and decoding owned by `runtime-domain`.
- `skillbill.idestatus.model`: agent activity label
  and stamp types for IDE status presentation owned by `runtime-domain`.
- `skillbill.engine`: feature-task run loop, goal runner, goal planning, and
  planning projection use cases owned by `runtime-engine`.

Package-cycle enforcement uses exact declared-package strongly connected
components for `runtime-domain` (including nested model packages),
`runtime-contracts` and `runtime-cli`. Other module scan cases retain the
existing first-segment mutual-pair algorithm and their recorded baselines; the
scanner does not infer package nodes from imported symbol suffixes.

### Goal-runner execution lifetime (`DefaultGoalRunnerExecutionCoordinator`)

Owned foreground goal runs acquire the parent execution lease first, then start
the worker heartbeat and register the shutdown hook. Heartbeat start failure
after a successful acquire releases the exact acquired owner token and
generation before the goal body runs. Shutdown-hook registration failure stops
the heartbeat and releases the lease before surfacing the registration error.

Teardown runs in order: unregister the shutdown hook, stop the heartbeat, release
the execution lease. Each step uses `runCatching` so a secondary failure does
not skip later cleanup. A goal-body or cancellation failure remains primary;
secondary teardown failures attach as suppressed exceptions. A body that
completed normally still fails the run when required teardown fails.

`GoalRunnerProgressEventEmitter` and `GoalRunnerProgressReader` propagate
`CancellationException` and `InterruptedException` from workflow identity
resolution instead of treating them as absent workflow identity. Optional
progress, ledger, and observability store write failures emit bounded
`RuntimeDiagnostics.warning` text; diagnostic port failures are wrapped in
`runCatching` so they cannot mask the primary outcome or block coordinator
teardown.

`GoalRunnerLedgerRecorder` seeds sequence numbers from persisted ledger
watermarks. A failed watermark read fails construction rather than silently
starting at sequence zero; empty watermarks still legitimately start at zero.

`RuntimeArchitectureProbeTest` and `GoalRunnerExecutionCoordinatorTest` regress
startup rollback, per-teardown-step failure, primary-error preservation,
cooperative cancellation on progress emit, and healthy versus failed watermark
reads through the production coordinator and recorders.
- `skillbill.infrastructure.workflow.goalplanning`: filesystem discovery of shared
  repository and validation context owned by `runtime-infra/workflow` (`:runtime-infra:workflow`), plus
  headings-first boundary memory: a programmatic parse of governed
  `## [<date>] <title>` entries into a bounded heading catalog (no model call in
  the indexer), and a separate body resolver that materializes entry bodies only
  for the heading ids preplanning selected.
- `skillbill.featurespec` and `skillbill.featurespec.model`: feature-spec
  preparation policy and typed preparation/write models owned by
  `runtime-domain`.
- `skillbill.install.model`: install-plan and install-apply domain models plus
  install-plan wire-map conversion owned by `runtime-domain`.
- `skillbill.scaffold.model`: platform manifest, scaffold result, skill-class,
  routing, add-on, and review-composition models owned by `runtime-domain`.
- `skillbill.scaffold.policy` and `skillbill.scaffold.policy.model`: pure
  scaffold policy rules and their policy models owned by `runtime-domain`.
- `skillbill.skillremove` and `skillbill.skillremove.model`: pure
  skill-remove service, target validation, rollback/refusal types, and removal
  models owned by `runtime-domain`.
- `skillbill.learnings` and `skillbill.learnings.model`: learning scope/source
  validation rules, learning payload helpers, and learning models owned by
  `runtime-domain`.
- `skillbill.review` and `skillbill.review.model`: pure review parsing, triage
  decision normalization, and review models owned by `runtime-domain`.
- `skillbill.review.context.model.claim` and
  `skillbill.workflow.taskruntime.model.persistence`:
  claim-admission and task-runtime persistence vocabulary (checkpoint, run,
  implementation, prior-gap, goal, and store models) owned by
  `runtime-domain`.
- `skillbill.telemetry.model`: telemetry settings normalization and lifecycle
  telemetry records owned by `runtime-domain`.
- `skillbill.application.telemetry`: telemetry sync orchestration, config
  mutation rules, and port-backed runtime surfaces owned by `runtime-application`.
- `skillbill.text`: UTF-8 truncation and size helpers owned by `runtime-domain`.
- `skillbill.infrastructure.host`: JDK and host-environment ports, repository-root
  resolution, telemetry config storage and paths, filesystem primitives, and
  one-shot process execution.
- `skillbill.infrastructure.contracts`: JSON-Schema validators, phase-output
  repair engines, and workflow wire mappers.
- `skillbill.infrastructure.skills`: agent-addon, native-agent, scaffold, install,
  and skill-remove filesystem adapters (`agentaddon` → `nativeagent` → `scaffold`
  → `install` → `skillremove` → module root import order is architecture-tested).
- `skillbill.infrastructure.launcher`: long-running agent-run and review
  child-process launch, and governed review MCP config.
- `skillbill.infrastructure.workflow`: git workflow operations, review evidence
  adapters, feature-task stores, goal-planning discovery, and validation gates.
- `skillbill.infrastructure.http`: the injected remote transport, telemetry
  client, installer-script fetch adapter, and telemetry proxy payload mapping.
- `skillbill.infrastructure.sqlite`: SQLite session factory, schema, migrations,
  SQL statement bindings, repositories, review stores, stats, and telemetry
  outbox persistence owned by `runtime-infra/sqlite` (`:runtime-infra:sqlite`).
- `skillbill.cli`: CLI adapter code. It validates CLI input, formats terminal
  output, maps typed results to contract payloads, and delegates behavior to
  application services or ports.
- `skillbill.mcp`: MCP adapter code. It validates MCP input, shapes MCP
  payloads, owns MCP-specific schema seams, and delegates shared behavior to
  application services or ports.

### Telemetry outbox delivery ownership

Claim lease duration is five minutes (`CLAIM_LEASE_MINUTES` in
`TelemetryOutboxDrain`). Each drain run holds one random `claimToken`; every
batch claim reads the injected clock at claim time so a slow earlier HTTP
request does not backdate a later batch lease. Settlement through
`markSynced`, `markFailed`, and `markUnconfirmed` requires the active
`claimToken` and `synced_at IS NULL`; zero updated rows set
`TelemetryOutboxSettlementResult.lostClaim`, and a partial update also reports
the lost portion so the drain does not count another owner's row as synced. The
drain stops without altering another owner's row.

`JdkHttpRequester` is the named default remote transport. Non-default
`TransportContext.connectTimeout` or `requestTimeout` values go through the
single `JdkHttpRemoteTransport.create` factory; the bootstrap supplies the
resolved `RemoteTransportPort` to adapters. The shared JDK client keeps its
default `NEVER` redirect policy. Default connect timeout is ten seconds and
per-request timeout is four minutes, both below the claim lease. Cooperative
cancellation and `InterruptedException` propagate through manual sync, auto
sync, drain, and stale-session reconciliation; cancellation does not consume
delivery attempts or become an UNKNOWN delivery report. Ordinary auto-sync
failure stays non-fatal to callers and records the payload-free
`telemetry background sync failed` diagnostic; the same signature is emitted
again when the follow-up outbox exception enqueue fails.
`HttpTelemetryClientTest` covers the capability fallback, stats request,
deduplication handshake, rejected batch detail, and explicit null metrics;
`HttpTelemetryTypedErrorsTest` covers peer and response failures.

SQLite integration tests prove stale-owner settlement rejection. A loopback
HTTP peer that accepts a connection but never completes a response proves
request deadlines and server teardown. Fakes at claim, transport, and
acknowledgement prove cancellation propagation and durable row state. Those
tests do not prove exactly-once remote delivery, protection against
indefinite JVM pause beyond lease expiry, or behavior when the remote proxy
ignores deduplication keys.

### Transaction rollback and agent-run cleanup boundaries (SKILL-247)

SQLite write and read session transactions share
`Connection.rollbackAfterFailedTransaction`: a failed body or commit still
throws its primary failure, a failed `ROLLBACK` is attached with
`addSuppressed` when a primary exists, and a bounded `java.util.logging`
record is emitted on rollback failure only. Successful rollback stays silent.

`JvmAgentRunProcessRunner.runStartedProcess` always runs
`ProcessRunLifetime.release` in `finally`, exports the run-local
`ProcessRunDegradationRecorder` snapshot to stderr through the output sink
before rethrowing, and keeps `InterruptedException` / cancellation as the
primary failure over cleanup suppresseds. `ProcessLifecycleEmitter` records
progress publication failures into the same recorder without replacing callback
or cancellation failures.

Governed review endpoint teardown reports close failure to stderr once; when
the sink also fails, a bounded `skillbill.agent.run.teardown` logger record is
emitted and the sink is not invoked again.

Per-run process cleanup waits are bounded: forced destroy waits up to
`DESTROY_WAIT_TIMEOUT_MILLIS` (1s), and each stdout/stderr drain join uses up
to two `DRAIN_JOIN_TIMEOUT_MILLIS` (1s) joins after `input.close` when the
worker remains alive, before the owner thread closes process streams. A single
run's drain and destroy cleanup is therefore capped at destroy wait plus up to
four drain-join windows for the two streams; stdin and process-stream closes
occur in the existing ordered cleanup path and are not used as a total bound
for a live drain worker.

### Installer update fetch and process I/O (SKILL-348 subtask 1)

`SkillBillUpdateService` in runtime-application owns update planning, release
skip/check_failed handling, installer script fetch, and post-download execution.
It downloads `install.sh` through `InstallerScriptFetchPort` (production adapter
`HttpInstallerScriptFetchAdapter` in runtime-infra/http) through the injected
`RemoteTransportPort` and only calls `InstallerProcessPort` after a complete
2xx body is persisted. Failed or interrupted fetch deletes partial staging bytes
through one non-Ready teardown path and never executes a script path. The fetch
port owns its temporary staging directory and removes it after the installer
process settles. `HttpInstallerScriptFetchAdapterTest` covers non-2xx, I/O,
interruption, atomic promotion, and foreign-directory refusal.

`runtime-infra/host` owns one-shot process execution: a process started for a
single bounded command, run to completion or killed, with captured output.
`runtime-infra/launcher` owns long-running agent processes: interactive or
streaming child agents with progress probes, idle policy, and heartbeat
supervision. `InstallerProcessAdapter` is one-shot execution and therefore lives
in `runtime-infra/host`.

`InstallerProcessAdapter` in `runtime-infra/host` starts an argv vector with an
explicit environment map, closes child stdin immediately after start, captures
merged stdout/stderr with a 1 MiB cap and `INSTALLER_OUTPUT_TRUNCATION_SENTINEL`
(both `internal` to `skillbill.infrastructure.host.process`),
and applies `DEFAULT_INSTALLER_PROCESS_DEADLINE_SECONDS` (600s) from the request
object. Tests inject shorter deadlines through that field; the CLI exposes no
public timeout flag. Post-failure teardown uses `GIT_PROCESS_CLEANUP_BUDGET_SECONDS`
(5s), `destroyOwnedProcessTree`, and `DESTROY_WAIT_TIMEOUT_MILLIS` (1s) over the
owned process handle and its descendants only. `RuntimeOptionalCallbackProvides`
wires production adapters from `RuntimeComponent`; `OptionalCallbacks` supplies test
substitutes for both ports.

`SkillBillUninstallService` owns uninstall plan construction and mutation
sequencing; the CLI keeps confirmation, dry-run rendering, and goal-continuation
refusal. Cooperative cancellation and interruption rethrow at each mutation seam
before later agent, MCP, launcher, desktop, or state-root work continues.

Checked CLI scope for this subtask: command-area import isolation plus a
production-source ban on `ProcessBuilder` under `skillbill.cli`, enforced by
`RuntimeCliAreaIsolationArchitectureTest` and the ProcessBuilder scan beside it.
This is not a universal SOLID certification claim.

### Git workflow process I/O (SKILL-248 subtask 2)

`runGitProcess` in `runtime-infra/workflow` delegates to `invokeGitProcess`,
which runs the command through `BoundedExternalProcessRunner` in
`runtime-infra/host` — the single one-shot process owner. That runner registers
the child process, input writer, stdout drain worker, and stream handles before
any blocking stdin delivery, wait, or join; `runtime-infra/workflow` maps its
result back to git semantics (trimmed output, exit `-1` on timeout,
`IOException` as `readFailure`). Stdin writes and
stdout draining run concurrently so a full pipe cannot deadlock ordinary
NUL-delimited staging input. One operation deadline derived from
`gitTimeoutSeconds` covers stdin delivery, `Process.waitFor`, and output
settlement; a separate
`GIT_PROCESS_CLEANUP_BUDGET_SECONDS` window bounds post-failure teardown
(drain join after closing the process input stream, stream closure, and
`destroyOwnedProcessTree` over the started process handle and its descendants).

Cooperative `Thread.interrupt` during wait or I/O destroys only processes this
invocation started (via `ProcessHandle` descendants from the git child),
rethrows `InterruptedException`, and runs the same cleanup owner in `finally`.
Secondary cleanup failures attach with `addSuppressed` and do not replace the
primary `IOException`, timeout, or interruption. Unsettled stdout after the
deadline becomes `readFailure` or timeout semantics, never
`WorkflowGitOperationResult.Ok` with unfinished capture. Behavior tests in
`GitProcessLifetimeBehaviorTest` cover interruption, pipe backpressure,
inherited stdout handles, timeout, and ordinary completion.

Decomposition manifest bundle journals (`DecompositionManifestBundleJournal` in
`runtime-infra/workflow`) persist a governed `0.1` envelope
(`orchestration/contracts/decomposition-manifest-bundle-journal-schema.yaml`,
`copyDecompositionManifestBundleJournalSchema`). Recovery validates the full marker,
transaction-owned staging directory (real-path containment, marker name binding),
unique targets, and every staged or already-applied digest before applying pending
moves or deleting staging evidence. Rejected journals raise
`InvalidDecompositionManifestBundleJournalError`, retain the marker and staging
artifacts, and do not replay SQLite mutations — operators back up evidence and
remove the marker manually after review. Valid interrupted `0.1` journals still
roll forward through `recoverPending`. `DecompositionManifestStore` declares
`writeBundleAtomically`, `readTextWithoutRecovery`, and
`isRegularFileWithoutRecovery` as abstract adapter operations so callers cannot
silently bypass the journal boundary.

## Boundary Rules

1. CLI and MCP data gateways are entry adapters. They validate and
   translate input, then delegate to application use cases or ports.
2. Application owns workflow and use-case orchestration. It must not depend on
   Clikt, Compose, MCP adapter types, JDBC, Java HTTP clients, or concrete
   infrastructure packages.
3. Domain packages must not depend on CLI, MCP, JDBC, Java HTTP
   clients, filesystem APIs, process environment APIs, infrastructure packages,
   or application services.
4. Port packages must not depend on application, infrastructure, entry
   adapters, or composition roots. `runtime-ports/src/main` must not declare
   top-level objects, non-DTO top-level classes, `(this as` casts, interface
   default bodies that `error` or `throw`, or — outside a `fun interface` —
   interface default bodies that return a bare constant, or top-level functions
   with a `*Repository` receiver or with a `UnitOfWork`,
   `GoalRunnerPersistenceSession`, `DatabaseSessionFactory`, `WorkflowEngine`,
   `*Repository`, or `*Store` parameter. Derived extensions on a `*Store` receiver
   with plain parameters stay allowed;
   `PortsDeclarationArchitectureTest`
   enforces this beside `RuntimeContractModuleImportRulesTest`.
5. Contracts packages must not depend on application, domain area packages,
   ports, infrastructure, entry adapters, or composition roots. `runtime-contracts`
   main source is a pure DTO/constants/exceptions leaf: it MUST NOT contain any
   JSON-Schema validator, any `com.networknt.*` or `com.fasterxml.jackson.*`
   reference, or any `java.nio.file.Files` filesystem call. The concrete schema
   validators and their schema-resource copy tasks live in
   `runtime-infra/contracts` (pack and native-agent validators in
   `runtime-infra/skills`),
   and `runtime-domain` / `runtime-application` reach schema validation only
   through the ports `InstallPlanWireValidator`,
   `DecompositionManifestValidator`, and `WorkflowSnapshotValidator` — never by
   importing a concrete `*SchemaValidator` / `*CoherenceValidator`.
   The install validator port is `skillbill.ports.install.InstallPlanWireValidator`.
   `runtime-contracts` is also a *shared* kernel, not a dumping ground: a
   declaration belongs there only when two or more production modules read it or
   a `runtime-ports` signature exposes it. Otherwise it belongs to its one owner
   — adapter-bound DTOs to the adapter module, single-owner `*Keys` objects to
   the owning module, and every `*SchemaPaths` locator to
   `skillbill.infrastructure.contracts.locator` in `runtime-infra/contracts`
   (SKILL-374).
6. Infrastructure packages implement ports and may depend on domain,
   contracts, ports, and JVM APIs. They must not depend on runtime-core or
   entry adapters.
7. `runtime-core` is the composition layer. Its source packages are limited to
   `skillbill` and `skillbill.di`; only composition code may import concrete
   infrastructure implementations.
8. Entry adapters must not bypass application services and ports by importing
   concrete implementation packages such as filesystem install/scaffold,
   native-agent, launcher, skill-remove, SQLite, or HTTP adapter internals.
9. Application use cases access SQLite through repository and unit-of-work
   ports. Read use cases call a read session; write use cases call an explicit
   transaction session.
10. Telemetry application use cases depend on `TelemetrySettingsProvider`,
    `TelemetryConfigStore`, `TelemetryClient`, and
    `TelemetryOutboxRepository`. HTTP request mechanics belong in
    `skillbill.infrastructure.http`; config file IO belongs in
    `skillbill.infrastructure.host`; telemetry ports expose typed domain result
    models from `skillbill.telemetry.model`; the telemetry proxy wire DTOs and
    their payload mapping belong with the HTTP adapter in
    `skillbill.infrastructure.http`, which is their single owner (SKILL-374).
    `TelemetryProxyPayloadKeys` stays in `skillbill.contracts.telemetry`, where
    domain, CLI, MCP, and the adapter all read it.
11. JSON maps, YAML maps, MCP payloads, CLI JSON payloads, and terminal strings
    are boundary concerns. Internal use cases expose typed models.

    **Raw Map Boundary Rule (SKILL-52.1, zero-tolerance as of SKILL-52.5):**
    public declarations on `runtime-application`, `runtime-domain`, and
    `runtime-ports` MUST NOT return or accept `Map<String, Any?>`,
    `Map<String, Any>`, `Map<String, *>`, string-keyed `MutableMap`,
    `HashMap`, or `LinkedHashMap` variants, or type aliases to those
    shapes. Public declarations in those modules also MUST NOT be typed
    exactly `Any`: a public `Any` return or property hides a raw map behind a
    type the scanner cannot see, so it is rejected the same way. Use a typed
    carrier (for example `FeatureTaskRuntimeWorkflowArtifactMap`,
    `DurableWorkflowArtifacts`, or `WorkflowArtifactPatch`) instead.

    The only allow-listed raw-map members are the four
    `DurableWorkflowArtifactFamily` members `contains`, `value`, `putInto`,
    and `removeFrom`. The family's `key` is private, so these four are the
    single typed gate for reading or writing a durable artifact family, and
    they must accept `Map<String, Any?>`. There is no other FQN allow-list and
    no production annotation escape hatch. `RuntimeRawMapArchitectureTest.runtime
    architecture forbids public raw map shapes in inner layers` fails on
    any new public raw-map or exact-`Any` surface in those modules.

    The domain accepts no validators. Schema validators live in
    `runtime-ports` (`FeatureTaskRuntimeWireArtifactValidator`,
    `InstallPlanWireValidator`); callers validate the wire map before or after
    the domain builds it.

    Contain wire maps in `private` or `internal` adapter serializers, or
    replace them with typed models at the port or application boundary.
    The scanner treats declarations inside non-public scopes and certain
    adapter-local enclosing types (`*Payload`, `*Artifacts`, `*Patch`, and
    related workflow patch carriers) as implementation detail when they stay
    non-public. `runtime-ports/src/main` has no name-suffix exemption: a
    public `*Map` wrapper there is still a raw-map violation.

    Inner-layer test sources in `runtime-application`, `runtime-domain`, and
    `runtime-ports` are also part of this boundary: their `src/test/kotlin`,
    `src/jvmTest/kotlin`, and `src/commonTest/kotlin` roots must not import
    `skillbill.infrastructure.*`, `skillbill.cli.*`, or `skillbill.mcp.*`.
    Adapter and infrastructure test trees are outside that inner-layer scan.
12. `java.nio.file.Path` is allowed in application, domain, and port public
    models and contracts only as an inert value type: callers may carry,
    compare, resolve, normalize, and render path values as data. Filesystem IO,
    home-directory expansion, `System.getProperty`, and process environment
    reads are adapter or composition concerns. Application/domain/port code must
    not call `Files`, `kotlin.io.path` IO helpers, `System.getenv`, or
    `System.getProperty`, and domain review parsing must stay limited to pure
    string and regex parsing.
13. Public data, enum, and sealed declarations in application, domain, and port
    modules live under explicit `model` packages. Services, runtimes, and port
    interfaces import those models instead of declaring public models inline.
14. SQLite schema changes are append-only versioned migrations recorded in
    `schema_migrations`, keyed by migration name. Version numbers order the list
    but do not identify a migration: branches assign them independently, so two
    lineages can ship different migrations under the same number. One-time legacy
    repairs run as named migrations (`ensure-schema-columns-and-heals`,
    `migrate-legacy-goal-runner-controls`, `migrate-legacy-telemetry-outbox`) instead
    of on every establishment or reconcile call.

The subsystem package set is:

```text
skillbill.agent.model
skillbill.agentaddon.model
skillbill.application
skillbill.cli
skillbill.config
skillbill.contracts
skillbill.di
skillbill.skillremove
skillbill.engine
skillbill.error
skillbill.featurespec
skillbill.goalrunner
skillbill.idestatus
skillbill.install
skillbill.infrastructure
skillbill.learnings
skillbill.mcp
skillbill.model
skillbill.ports
skillbill.review
skillbill.scaffold
skillbill.telemetry
skillbill.text
skillbill.workflow
skillbill.workflow.verify
```

## Feature-Task Workflow Family

- `bill-feature-task` is the public workflow identity for the runtime-backed
  feature-task engine. Feature-verify remains a distinct workflow family and
  store.
- The Kotlin runtime definition is
  `skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition`
  (id prefix `wftr`, contract version `FEATURE_TASK_RUNTIME_CONTRACT_VERSION`);
  persisted rows use `workflow_name=bill-feature-task` and `mode=runtime`.
- `skill-bill feature-task` and `feature-task-stats` are the CLI surfaces for
  this workflow family.

## Listed skill catalog

- `skill-bill` is the only listed skill (SKILL-383). Its full-run form
  launches `skill-bill goal` after one confirmation gate. `phase:<name>`
  translates to `skill-bill phase <name>` and `operation:<name>` to
  `skill-bill operation <name>`; the dispatcher relays one operator
  confirmation for operations that exit `awaiting_confirmation`.
  `skill-bill goal status` stays CLI-only.
- The text of the retired listed skills moved verbatim into
  `skills/skill-bill/content.md` and into directive resources under
  `runtime-engine/src/main/resources/skillbill/engine/<owner package path>/`,
  each loaded by its owning strategy or operation.
- Pack specialists install as unlisted `internal-for: skill-bill` sidecars in
  the installed `skill-bill` directory. `InstallLegacySkillNames` lists every
  retired name, so an install over an old home removes their links and copies.
- Wire labels keep retired names: telemetry `skill` values, the verify
  `workflow_name` default (`bill-feature-verify`) and its migration, the
  workflow skill label `WorkflowStateWrites` writes, and the quality-check
  `routed_skill` (`bill-code-check`). See `agent/decisions.md`.

## Runtime operations

An operation (`skillbill.engine.operation`) is a runtime command outside the
feature-task workflow family. Only `verify` opens a workflow row, in its own
verify family. Every invocation id has the `opr-` prefix.

- `Operation` runs `pre`, `run`, and `post`. Agent steps launch only through
  `OperationStepRunner` over the generic `PhaseRunner`. The launch-port rule in
  `FeatureTaskLaunchPortScan` covers `operation/`, so no operation reaches
  `GoalRunnerSubtaskLauncher` directly.
- `OperationRegistry` is the explicit list in `RuntimeOperationProvides`
  (runtime-core). `OperationExecutor` is the inbound API. runtime-cli reaches
  it through `RuntimeComponent.operationExecutor` for `skill-bill operation`.
- `OperationConfirmationGate` owns the two-invocation confirmation. Proposals
  persist through `OperationProposalRepository` (runtime-ports) in the
  `operation_proposals` table. `SqliteOperationProposalStore` owns the
  `anchors_json` wire keys (`OperationProposalPayloadKeys`).
- Operations: `update-check` shares the text formatter with
  `skill-bill update-check`. `release` tags through
  `WorkflowGitReleaseTagOperations`.
- Checklist operations load the text of the skills they replace from their
  directive resources (SKILL-383). Kotlin prompt objects keep only the
  runtime output-contract text.
  - `unit-test-value-check` is read-only and needs no confirmation. It reviews
    the unit tests in the current staged, unstaged, and untracked changes, or
    in `scope:<path|sha|ref>`. With no unit test in scope it says so and
    launches no agent.
  - `feature-guard` and `feature-guard-cleanup` confirm in two invocations. A
    read-only step proposes the plan. Guard proposals anchor on HEAD and the
    current branch only. On `confirm:<token>` an editing step gets the stored
    proposal verbatim as its prior value.
  - After the cleanup edits, `feature-guard-cleanup` runs the in-memory
    `validation` definition through `PhaseRunEntry`. A blocked validation
    leaves the edits in place and reports the verdict.
- `OperationStepRunner.runReadOnly` compares the repository fingerprint before
  and after each read-only step. A step that changes it fails, and the gate
  stores no proposal. Only a confirmed `execute` calls `runEditing`.
- `ConfirmableOperation.admit` checks a confirm invocation against the stored
  proposal before the gate consumes the token, so a bad selection leaves the
  token valid.
- `pr-review-fix` (`operation.prreviewfix`) reads review threads through the
  `PullRequestReviewThreadOperations` port (runtime-ports
  `review.pullrequest`; adapter `GhPullRequestReviewThreads` over
  `gh api graphql`, paged, never `gh pr view --comments`). The port has no
  resolve member. Analysis is one read-only step over a runtime-rendered
  thread digest. It proposes a matrix whose actionable (unresolved, not
  outdated) threads carry runtime ordinals `T1..Tn`, anchored on the PR number,
  PR head sha, and the actionable thread-id set. `scope:analyze-only` stores
  nothing. `confirm:<token>` with `select:` runs one editing step per selected
  thread, then `validation`, then posts (or, with `replies:draft`, prints) the
  replies, and commits and pushes only with `push:on`. A failed step or gate
  stops before any reply.
- `verify` (`operation.verify`) is the one operation with a workflow row: a
  `SelfConfirmingOperation` on the `bill-feature-verify` family. It is
  report-only and never edits, fixes, or posts. The first invocation opens the
  row, extracts the criteria read-only, and parks the row at
  `extract_criteria`. The `confirm:` token is the workflow id, so no
  `operation_proposals` row is written, and a newer run in the same repo root
  abandons older parked rows. Confirm runs `gather_diff` through `finish` on
  that row. Each evaluator's prior values are only its declared launch
  projection. The rubrics come from the `operation/verify` directive
  resource. `mode:delegated` runs the multi-agent
  review as the `code_review` step's `PhaseStepSession`. Confirming an
  interrupted row resumes it at `continueWorkflow`'s step.

## Phase slots and strategies

The feature-task runtime runs a fixed skeleton of slots. A slot is a stage of a
run. A strategy is one in-process way to run the steps of a slot. The graph,
the ledger, and the durable phase ids stay the same whichever strategy runs.

Parts (`skillbill.engine.featuretask.slot`, with `PhaseSlot` and
`PhaseStepPolicy` in `runtime-domain`):

- `PhaseSlot` partitions every phase id into exactly one slot.
- `PhaseStrategy` declares a strategy's slot, id, steps, entry step, and the
  policy and task directive of each step. It runs a step through its own
  `PhaseRunner`.
- `PhaseRunner` is the one AI-facing seam. It takes a `PhaseStepInput`
  (step name, directive, prior step values, operator instructions, launch
  facts, policy) and returns a `PhaseStepOutput` (status, value, summary,
  verdict, failure disposition, stdout, stderr, termination, file manifest,
  settled envelope, typed launch failure). `DefaultPhaseRunner` is the only
  implementation, and it is the only featuretask type that depends on
  `GoalRunnerSubtaskLauncher`.
- `PhaseRunState` exposes progress, session, attempt execution, strategy
  selection, records, goal continuation, settlements, checkpoints, and phase
  gates. Ordinary steps receive `PhaseAgentStepBinding` at dispatch; review steps
  receive `PhaseReviewStepBinding` (review interfaces in `PhaseStepState.kt`, not
  a `PhaseRunState` inheritance chain). `FeatureTaskRuntimeRunLoopStepState`
  implements those review interfaces only on review bindings. `FeatureTaskRuntimeRunLoopDurableState`
  (`runloop.durable`) adapts durable services to `FeatureTaskRuntimeRunLoopStepState`
  (`runloop.state`), while `InMemoryPhaseRunState` and
  `GoalPlanningPhaseRunState` use the same step-state adapter with in-memory
  records. Goal planning fan-out units run on `GoalPlanningUnitRunState` with
  isolated progress, session, records, telemetry, and step binding. Durable
  and in-memory storage remain distinct.
- `PhaseAttemptEnvironment` supplies request facts only. Runtime scopes retain storage and effect
  collaborators privately. Strategy hooks receive detached observations and bound role operations;
  review/PR/loop helpers receive readonly repository inspection. Step factories require coordinator
  dispatch, request identity, selected membership and policy. Attempt launch selects the admitted
  owner's runner after required persistence. The transition owner coordinates progress, session,
  evidence, retry and checkpoint state, with durable acknowledgement before in-memory advancement.
- Execution lookup requires membership in `ResolvedPhaseExecutionPlan` and
  checks the selected strategy revision, step policy identity, and resume
  interpretation identity before returning a strategy. Durable run state reads
  traversal from that same plan. `PhaseStrategyLookup.resumeRules(plan)` uses
  selected strategy rules for execution and the explicit revision-one history
  policy for unselected records.
- `FeatureTaskRuntimeExecutionPlanCodec` maps resolved plans to the bounded
  artifact through `FeatureTaskRuntimeExecutionPlanValidator`. Decoding restores
  immutable domain data and checks policy digests. It does not select strategies
  or launch runners. `FeatureTaskRuntimeExecutionPlanCompatibility` compares
  recorded composition with supported definitions, registrations, and policies.
  It returns the supported plan and reports distinct missing, corrupt,
  unsupported, and incompatible failures with payload-free recovery guidance.
  This composition check does not establish durable execution admission.
  `encodeExecution` adds revision-one descriptors for gate commands, receipt
  interpretation, retry and resume budgets, audit behavior, review invalidation,
  checkpoint ownership, and finalization. `requireSupportedExecution` requires
  the complete supported policy set and compares its effective inputs. Command
  identity uses argv after wrapper resolution, command family and role, cache
  mode, findings settings, validation depth, pack identity, and phase timeout.
  It preserves argv order and absent values. Artifact bodies contain digests,
  not command payloads. Policy input encoding and the artifact each have a
  65536-byte limit. The combined policy families have a 256-descriptor limit.
  Semantic changes require a revision bump; cosmetic source changes do not.
  Durable traversal compatibility currently accepts the definition's exact
  traversal. `AuditPlanningExecutionPlanMapping` permits only the exact
  acceptance-audit revision 1 or 2 composition to map to revision 3. It checks the
  original step identities, traversal, and retry/resume policy digests, then
  maps to prose repair planning and derives the two affected effective-policy digests.
  Other effective policies must still match admission inputs. The durable
  descriptor stays unchanged, admission records a diagnostic, and existing-child
  creation returns the original descriptor to preserve raw identity checks.
  Resume routes through an unfinished loop-only predecessor before its successor.
  Phase records, ledger entries, checkpoint evidence, and loop budgets stay intact.
  There are no traversal-override mappings.
  In-memory override validation does not grant durable compatibility.
  The execution encoder rejects traversal overrides before producing a durable
  descriptor. `FeatureTaskContinuationLookupService.claim` re-reads the row,
  route identity, worker ownership, and descriptor in its claim transaction.
  It checks effective policies before changing status and returns the immutable
  recorded plan. Refusal diagnostics contain a bounded workflow id and reason
  code. Diagnostic failure does not replace the refusal.
  `FeatureTaskRuntimeExecutionPlanResolver` resolves the repository-owned path
  inventory through installed pack routing, reads the repository wrapper setting,
  and encodes the chosen command family, validation depth, and phase timeout.
  Standalone CLI creation and goal-child launch preparation supply this descriptor
  to their existing creation transactions. Build selection refuses a missing
  discovery or verification build command before child creation.
  `FeatureTaskRuntimeExecutionAdmission` checks the authoritative identity and
  descriptor inside worker acquisition, takeover reservation, and ownership
  transfer transactions. Transfer checks the identity admitted at reservation.
  Admission failure retains workflow and lease evidence and emits a bounded
  diagnostic. The worker hands the admitted immutable plan to the CLI's runner
  request. Preparation and execution use that plan when supplied.
  Direct runner entry still permits requests without admission. Creation adapters
  still accept omitted descriptors, goal-child reuse still compares raw values,
  and crash recovery and receipt regeneration still need transactional admission.
  Gate execution must also consume the checked effective inputs rather than
  resolving commands again from current configuration.
- `PhaseHistoricalInterpreter` owns revision-one record interpretation and
  status metadata. It has no strategy registry, runner, or persistence port.
  Unknown steps retain their raw records during status inspection and cannot
  acquire resume rules. Recognizing a historical build step never selects its
  build strategy. History inspection does not grant durable resume admission.
- `ReviewTarget` is a per-call fact on `PhaseRun`: `LastCommit` (the full-run
  default), `Uncommitted`, `Commit(revision)` (a sha, branch, or tag), or
  `Scoped` (the base and head `skill-bill code-review` resolves). It composes
  the opening lines of the review prompt.
- `SkeletonDefinition` (`runtime-domain`) lists a run's slots in order.
  `STANDALONE` has every slot; `GOAL_CHILD` omits `pull_request`.
  `forRun(goalContinuation)` picks one. The declaration derived from a
  definition equals the phase workflow's, and a reorder raises a typed error.
  `REVIEW` (the `code_review` slot), `VALIDATION` (the `quality_gate` slot),
  `PLAN` (`preplan` and `plan`), `IMPLEMENT` (the `implementation` slot) and
  `PR` (the `pull_request` slot) are in-memory definitions (`runStateKind`
  `IN_MEMORY`) that a phase run drives on its own. Each carries a
  `PhaseIntakeRequirement`: `plan` needs an issue key, `implement` needs an
  existing governed spec, and the rest take an optional intake.
  `GOAL_PLANNING` (`preplan` and `plan`) is the goal planning sweep's
  definition. It selects `agent-preplan` for the shared preplan and
  `goal-plan-fan-out` for the plans. `GoalPlanFanOutStrategy` runs
  `agent-plan` once per active subtask, in waves capped by the burst
  schedule, over the run state's `PhaseRunFanOut`. The sweep drives it through
  `FeatureTaskRuntimeRunLoopEntry` over an in-memory
  `GoalPlanningPhaseRunState`, whose attempt loop keeps the planning attempt
  gate, budget, and checkpoints, and writes no feature-task workflow row. The
  selected `goal-plan-fan-out` strategy authorizes `agent-plan` at the attempt
  boundary while preserving the selected step policy and request checks.
  `ResolvedPhaseExecutionPlan.unselectedStepIds` counts every canonical step a run
  does not select, including steps outside a short definition, so a step
  drops its projections from producers the definition never runs.
- A phase run (`skillbill.engine.featuretask.phaserun`) drives one in-memory
  definition through the same `FeatureTaskRuntimeRunLoopEntry` as a full run.
  `PhaseRunEntry` builds `InMemoryPhaseRunState` over `InMemoryPhaseRunRecords`
  and the in-memory goal, settlement, and checkpoint adapters. It writes no
  workflow row, session, phase record, ledger entry, run invariant, or
  checkpoint ref, creates or switches no branch, and skips checkpoint commits.
  `InMemoryPhaseRunRecords` answers the resolved branch with the checkout's
  current branch (none when detached), read once at entry. A
  checkpoint commit or a durable definition raises a typed error. The state
  has no settlement target, so every step settles through the minimal final
  object its agent prints, and a phase run has no resume. `PhaseRunState` adds
  four no-op capabilities that the in-memory state implements:
  `recordReviewRun` (review-pass claims, plus stage telemetry unless the
  `CodeReviewPass` already recorded it), `qualityCheckStarted` /
  `qualityCheckFinished` (quality_check telemetry around the pack build gate,
  with the last gate run's failure count and rule or test ids), and
  `qualityGateAbsent`. A durable run falls back to the runtime-owned build
  when no pack gate is declared. A phase run fails with
  `MissingValidationGateError` instead.
  A phase run adds no telemetry event of its own and runs through
  `FeatureTaskRuntimeRunEventSink.NONE`. It returns an invocation id (the
  review session id when one is given, else `phr-<uuid>`) that the CLI prints.
  `skill-bill phase <review|validation|plan|pr> [intake] [mode:..] [target:..]` takes
  `mode:inline|delegated|auto` (`auto` resolves inline) and
  `target:HEAD|uncommitted|pr|staged|unstaged|<commit-sha|branch|tag>`. An omitted target reviews
  uncommitted changes when the worktree is dirty and `HEAD` when it is clean.
  A target that names no commit is a usage error, and a worktree status that
  cannot be read blocks the review step. `skill-bill code-review` routes
  through `PhaseRunEntry` too, so both modes find, verify, and fix Blocker and
  Major findings before it reports. `PhaseInstructions` carries the operator
  instructions a phase run adds to its step prompts.
  `PhaseRunIntakeResolver` turns the intake into the run's issue key and run
  invariants as the definition's intake requirement says. An optional-intake
  run takes its issue key from the intake, else the current branch, else the
  definition id, so a `phase pr` title names the real issue. An issue URL in the
  intake supplies its key from the first path segment that is one. A missing
  issue key raises `PhaseIntakeRequiredError`. The CLI rejects an empty plan intake as a
  usage error. `skill-bill phase plan <KEY> [description]` sets
  `specBundleRequired`: the plan prompt asks for a decomposition package, the
  planning stopper writes the parent spec, subtask specs and decomposition
  manifest through `FeatureSpecPreparationWriter` (spec type from
  `ConfigResolutionService`), and a direct plan blocks. The result carries a
  `PhaseRunSpecBundle` whose paths the CLI prints, so `skill-bill goal` can
  run the bundle. Implementation and simplification run inside workflows and
  consume their plan output. `skill-bill phase pr` refuses a detached, protected, or
  base branch with `PullRequestBranchRefusedError`, pushes the branch when it
  has unpushed commits, and runs the pull-request readiness gate only when the
  run's forward steps include `commit_push`.
  The `skill-bill` dispatcher, the only listed skill, translates
  `phase:<name>` into this subcommand.
  `FeatureTaskPhaseRunDefinitionScan` keeps the package off named definitions,
  and the durable-store scan covers it.
- `PhaseStrategyRegistry` holds the registered strategies. `PhaseStrategySelection`
  binds each definition's slots to a strategy id, either fixed or keyed by a
  selection fact (code review mode, quality gate). `PhaseStrategySelectionFacts`
  carries the definition and the fact values. `PhaseStrategyLookup` resolves a
  step id to its strategy and answers the selected and unselected steps and
  the traversal for those facts. A duplicate registration, a step outside its
  slot, an unknown strategy, a selection naming an unregistered strategy, and
  a definition whose slots do not match its bindings each raise a typed error.

| Slot | Steps | Strategy |
| --- | --- | --- |
| `preplan` | preplan | `agent-preplan` |
| `plan` | plan | `agent-plan` |
| `implementation` | implement, simplify | `implement-then-simplify` |
| `audit` | audit | `acceptance-audit` |
| `code_review` | review, verify_findings, implement_fix | `inline` or `delegated` |
| `quality_gate` | build or validate | `pack-build` or `agent-validate` |
| `write_history` | write_history | `boundary-history` |
| `commit_push` | commit_push | `runtime-commit` |
| `pull_request` | pr | `pr-description` |

Composition:

- A full run walks the skeleton. `runPreparedPhase` looks up the strategy for
  the ready step and calls `runStep`. It has no phase-id branch. The shared
  pre-launch block check lives in `FeatureTaskRuntimeRunLoopPreLaunch`.
- `InlineReviewStrategy` (`slot.codereview`, id `inline`) owns the code_review
  slot. Its review step prepares the pass, launches one agent through its own
  `PhaseRunner` with the same launch facts as every other step (stamp sink,
  worktree-edit observer, model and effort override), decodes the register,
  and settles through `PhaseRunState`. `VerifyFindingsStep` and
  `ImplementFixStep` own their step policy and run the shared attempt path.
  No review driver, review-specific runner, or review binding in
  `runtime-core` remains; `CodeReviewSlotBoundaryArchitectureTest` keeps the
  package off the launcher, recorders, `FeatureTaskRuntimeRunState`,
  persistence writers, checkpoint git, and run-loop types other than
  `PhaseRun` and `PhaseOutcome`.
- Run-loop decisions a slot owns sit behind `PhaseStrategy.loopRules`
  (`PhaseLoopRules`). The loop asks the strategy selected for a step, or for a
  backward edge's destination. It does not name the step or the loop.
  `InlineReviewLoopRules` owns these review rules:
  - reopening a stale capped review before the run starts;
  - invalidating review-generation evidence;
  - discarding or resuming an in-flight review_fix reentry, and its expected
    checkpoint;
  - reconciling the reserved goal review pass;
  - carrying a capped or skipped goal review forward without a launch;
  - routing the REVIEW_CAP_REACHED verdict.
  Every read and write goes through `PhaseRunState`.
- The `quality_gate` slot has two strategies. `PackBuildStrategy`
  (`slot.qualitygate.packbuild`) runs the runtime-owned pack build gate and
  its triage and repair sessions. `AgentValidateStrategy`
  (`slot.qualitygate.agentvalidate`) runs the agent validate step. A BUILD goal
  child selects build; the final child and a standalone run select validate.
  Traversal filters the forward path to the selected steps, handoff projection
  omits the unselected steps' outputs, and the handoff rejects a settled output
  from an unselected step. Nothing rewrites transitions, and no shared
  runloop, phase, runner, review, validation, or lifecycle code imports the
  strategy packages. The gate cycles read and write gate progress through
  `PhaseRunRecords`.
- Validate runs all required project checks and repairs their failures in one
  agent session. It returns the uniform completed output only after every check
  passes. Partial progress reports do not start another agent or enter schema
  recovery. A concrete external blocker ends the session with its reason and
  remaining failures retained in the phase record. Malformed output and process
  failures block after that session. An explicit operator retry starts a new
  validation session. Resume reads success from the step status. Historical
  `progress` and `no_progress` verdicts remain readable terminal reports.
- A phase run executes one slot's strategy outside the full graph. Phase runs
  arrive in later SKILL-380 subtasks and reuse the same runner, state port, and
  input and output shapes.
- Operations (SKILL-382) will compose the same runner and state port, and
  name their steps with operation-local names outside the domain graph.
- IDE status goes through the same lookup.
  `FeatureTaskRuntimeCurrentPhaseExecutionDeriver` asks the step's strategy for
  its execution counter through `PhaseStrategyStatusProjection`.
- Every strategy owns its step code in its slot package:

  | Strategy | Package | Owns |
  | --- | --- | --- |
  | `agent-preplan` | `slot.preplan` | Preplan directive, ceremony line, prose settlement |
  | `agent-plan` | `slot.plan` | Plan directive, goal-continuation constraint, decomposition stop |
  | `implement-then-simplify` | `slot.implementation` | Implement and simplify directives, continuation segments, simplify scope boundary, receipt checks |
  | `acceptance-audit` | `slot.audit` | Audit directive, remaining-criteria retry prompt and briefing rewrite, unchanged-remainder block, audit verdict rule (`AcceptanceAuditVerdictRule`) and its `gaps_found` rejection, audit-to-review checkpoint (`AcceptanceAuditLoopRules.forwardCheckpoint`) |
  | `inline` | `slot.codereview` | Review, verify_findings, and implement_fix prompts, review envelope decoding, finding-disposition gate, review briefing field set; standalone and goal-child selection maps `inline` and `auto` here, and `RuntimeOwnedReviewMode` rejects a requested `delegated` |
  | `delegated` | `slot.codereview` | The same `CodeReviewSlot` steps, with a review step that runs `ParallelCodeReviewRunner` lanes (bounded by `withBoundedLaneProgress`) inside its `PhaseRunner` session and edits no files; the `REVIEW` definition selects it for `delegated`, standalone and goal-child runs do not |
  | `pack-build` | `slot.qualitygate.packbuild` | Runtime-owned build gate, triage and repair sessions |
  | `agent-validate` | `slot.qualitygate.agentvalidate` | Agent validate step, its repair session, retryable blocked disposition |
  | `boundary-history` | `slot.writehistory` | write_history directive, the boundary history and decision rules (the `boundary-history-directive.md` and `boundary-decisions-directive.md` resources, with runtime inputs in `BoundaryMemoryPromptRules`; the prompt invokes no skill), finalization briefing field set, changed paths and history and decision writes measured by `WriteHistoryMeasurement` under `FeatureTaskRuntimeMeasuredFactKeys`; a fact it cannot measure is recorded as unknown with a diagnostics record |
  | `runtime-commit` | `slot.commitpush` | Commit push cycle, upstream-head fallback, finalization briefing field set |
  | `pr-description` | `slot.pullrequest` | PR directive, the pull request description rules (the `pr-description-directive.md` resource, with the template search result in `PrDescriptionPromptRules`; the prompt invokes no skill), the repo template search (`PullRequestTemplateSearch` over the `PullRequestTemplateFiles` port: a found template keeps its headings and drops its checklist, none falls back to the coded template, several with no default block the step), PR readiness gate (`PullRequestReadinessGate`), PR identity measured before and after the step through `PullRequestIdentityLookup`, and `pr_description_generated` emitted after a completed step through the lifecycle telemetry gate |

- A strategy reaches shared code through hooks, never the other way round.
  `PhaseStrategy.stepHooks` returns the step's `PhaseStepHooks` (launch,
  pre-launch reconcile, completed-round settlement, accepted output, blocked
  output disposition). `PhaseStrategy.verdictRule` returns a
  `FeatureTaskRuntimeStepVerdictRule` that `FeatureTaskRuntimeRunState` settles
  the step's verdict by. `PhaseStrategy.briefingInvariantFields` picks the
  run-invariant field set the step's briefing renders.

- Each strategy supplies its steps' prompt sections through
  `PhaseStrategy.promptSections` (`PhaseStepPromptSections`: task directive,
  ceremony line, gate flags, scope boundary, step context, continuation, retry
  shape, value guidance, output contract). The composer
  (`FeatureTaskRuntimePhasePromptComposer`) owns the shared header,
  discipline, retry, and settlement sections and has no phase-keyed table.
  Goal planning composes preplan and plan prompts from the registered
  strategies through `PhaseStrategyLookup`.
- Every step except the three `code_review` steps settles through
  `feature_task_phase_complete` / `feature_task_phase_block` with a status,
  summary, prose value, and optional prompt; a failure disposition accompanies
  a non-completed status. The runtime stamps the phase id, and the durable
  settlement directive is added whenever the step has a settlement target.
  `PhaseOutputGate` takes the terminal outcome first, then the settlement
  record, then non-blank stdout prose as the value for any step name, including
  a step outside the domain graph. There is no response envelope to parse and
  no format relaunch. A step whose prompt sections set `settles = false` (the
  runtime-owned build and commit_push turns) does not settle with the uniform
  output.

Dispatch and policy:

- Selection is a code binding in `RuntimeFeatureTaskSlotProvides`, not
  operator config. The provider lists every strategy explicitly and gives each
  one its own `PhaseRunner` from an unscoped provider.
- Step policy (mutating, relaunch on invalid output, single agent session,
  read-only idle, file mutating, generation scoped) is declared by the
  strategy that owns the step. No phase-id set outside the strategies decides
  launch policy.

Guard rules (`RuntimeEngineBoundaryArchitectureTest`, scanning with
`FeatureTaskSlotBoundaryScans`; each rule reports the files it read, has no
baseline or exemption, and has synthetic violations that call its own scan):

- Step identity: no shared file decides behaviour by step identity. The rule
  scans every feature-task package outside `slot`, with no exemption. It
  rejects a `FeatureTaskRuntimePhaseIds` or
  `FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_*` constant (qualified,
  aliased, or imported), a string literal equal to a step id, element access
  on a slot's `steps` or `stepIds`, and any use of a non-private `slot`
  declaration whose value is a single step id. That includes an entry of a
  non-private `slot` enum whose constructor arguments wrap a step id, however
  it is reached (qualified, import-aliased, star-imported, or bare). Shared
  code asks the selection for the slot or strategy that owns the step, asks
  that strategy's resume rules or reported gate, or takes the step id as a
  parameter, instead.
- Launch port: only the `PhaseRunner` implementation depends on
  `GoalRunnerSubtaskLauncher`. The rule scans the feature-task packages and
  `goalrunner.planning`, so goal planning launches only through a
  `PhaseRunner`. Under `goalrunner.planning` the `PhaseRunner` exemption does
  not apply: no planning class may hold the launcher, even behind a local
  `PhaseRunner`.
- Dependency direction: shared feature-task packages import no strategy
  package; strategy packages import none of the run loop's drive, launch,
  attempt, or planning-branch objects and do not reference
  `FeatureTaskRuntimeRunState` by name. No slot file references
  `FeatureTaskRuntimeRunLoopContext`. Strategies take collaborators by
  constructor.
- Durable stores: outside `runloop.durable`, no `runloop` or `slot` file
  imports that package or names the phase recorder, goal-continuation
  recorder, settlement service, activity-stamp and worktree-edit writers,
  run-invariants store, probe writers, or the checkpoint git operations.

Adding a phase strategy:

1. Write the strategy class in its slot package under
   `skillbill.engine.featuretask.slot`: declare the slot, strategy id, steps,
   entry step, per-step policy, and prompt sections, and run each step
   through the strategy's `PhaseRunner`.
2. Add it to the registry provider in `RuntimeFeatureTaskSlotProvides` with
   its own `PhaseRunner`.
3. Name it in `PhaseStrategySelection` for the definitions and selection facts
   that pick it.

## Runtime Contract And Schema Seams

- Runtime contract schemas live in `orchestration/contracts/`. The
  `*_CONTRACT_VERSION` constants stay in `runtime-contracts`; the `*SchemaPaths`
  locators that name the staged copies live in
  `skillbill.infrastructure.contracts.locator` in `runtime-infra/contracts`
  (SKILL-374). Where an inner layer only needed a locator's `EXPECTED_SCHEMA_ID`,
  it reads the top-level `GOAL_PLANNING_PREPARATION_SCHEMA_ID` or
  `FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_ID` constant in `runtime-contracts`
  instead. For MCP tool seams, each `telemetry-event-schema.yaml`
  `$defs` branch is both the validating envelope and the advertised
  `inputSchema` (`McpInputSchemaProjection` strips `event_name`,
  `contract_version`, and runtime-owned quality-check keys). The JVM
  JSON-Schema validators, their typed schema errors, and their
  classpath-resource copy tasks live in `runtime-infra/contracts`,
  reached only through the ports-owned validators `InstallPlanWireValidator`,
  `DecompositionManifestValidator`, and `WorkflowSnapshotValidator`. Validator modules
  load schema resources from the `runtime-infra/contracts` classpath copy tasks, not from `runtime-contracts`.
- Workflow-state schema validation is owned by
  `skillbill.infrastructure.contracts.workflow.WorkflowStateSchemaValidator`, compiled into
  `runtime-infra/contracts`. The runtime-domain workflow engine MUST NOT import that
  validator directly. The ports module declares
  `skillbill.ports.workflow.WorkflowSnapshotValidator`, which the composition root
  wires to the infra adapter
  `skillbill.infrastructure.contracts.WorkflowSnapshotValidatorInfraAdapter`. The
  port takes the typed `skillbill.workflow.engine.model.WorkflowStateSnapshot`, not a
  `Map<String, Any?>`; projecting that record onto the canonical wire shape is
  adapter work owned by
  `skillbill.infrastructure.contracts.WorkflowStateSnapshotWireMapper`, so
  `WorkflowEngine` never builds a snapshot map. `WorkflowStateRecord.toSnapshot()`
  strictly decodes step and artifact columns at the ports mapping boundary. The
  engine owns aggregate rules, while adapters and application entry points own
  canonical schema validation. Architecture
  tests forbid any `skillbill.infrastructure.contracts.workflow.*SchemaValidator*` or
  `skillbill.infrastructure.contracts.*Mapper` import under `runtime-domain` workflow
  source. (SKILL-52.2 Subtask 4 narrowed the
  `runtime-domain -> runtime-contracts` module-graph edge to non-validator
  helpers only; SKILL-233 narrowed it further to `JsonCodec` — including its
  stdlib-typed `parseValue` / `valueToJsonString` facade that keeps
  `kotlinx.serialization` out of `runtime-domain` — the `*_CONTRACT_VERSION`
  constants and the typed
  `InvalidWorkflowStateSchemaError` / `MalformedJsonTextError`. Workflow wire payloads are
  built once in `skillbill.application.workflow.WorkflowWireProjections` using
  `WorkflowWirePayloadKeys` and `SharedPayloadKeys`; there is no contracts-module
  ordering helper on that path.)
- Feature-task runtime wire artifact schema validation ports live in
  `runtime-ports`: `skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator`
  and `skillbill.ports.workflow.decomposition.DecompositionManifestValidator`.
  Callers name the closed `FeatureTaskRuntimeWireArtifactKind` explicitly.
  Infra implements them through
  `FeatureTaskRuntimeWireArtifactValidatorAdapter` and the decomposition adapters under `runtime-infra/contracts`; composition wires one adapter
  instance per port. Goal-continuation artifact keys declare in
  `FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys`; `WireVocabularyGovernedSeamInventory`
  scans that encode/decode pair. `SkillBillVersion` reads `skillbill/version.properties`
  from `runtime-core`; its `getResourceAsStream` call is the single documented
  ambient-environment exception for packaged version metadata, and its missing-resource
  fallback emits a durable-decode substitution record. Typed workflow boundary wrappers live in owning area `model`
  packages rather than a monolithic `WorkflowBoundaryCollections` hub. Engine continuation
  dispatch uses `WorkflowDefinition.usesFeatureTaskRuntimeContinuation` rather than importing
  the feature-task runtime workflow definition.
- Install-plan schema validation is owned by
  `skillbill.infrastructure.contracts.install.InstallPlanSchemaValidator`, compiled into
  `runtime-infra/contracts` and reached through the domain-owned port
  `skillbill.ports.install.InstallPlanWireValidator`. The owning seams are
  install-plan building and CLI/MCP emission, both of which validate through the
  injected port rather than importing the validator directly.
- Decomposition-manifest schema validation is owned by
  `skillbill.infrastructure.contracts.workflow.DecompositionManifestSchemaValidator` (paired
  with `DecompositionManifestCoherenceValidator`), compiled into
  `runtime-infra/contracts` and reached through the domain-owned port
  `skillbill.ports.workflow.decomposition.DecompositionManifestValidator`. The owning parse/emission
  seam is `skillbill.application.decomposition.DecompositionManifestFileWrites`, which
  validates YAML text and in-memory maps through that port before workflow
  artifacts are persisted or returned. Repo-local manifest text persistence is
  owned by
  `skillbill.infrastructure.workflow.FileSystemDecompositionManifestFileStore`
  behind `skillbill.ports.workflow.decomposition.DecompositionManifestStore`.
- Platform-pack manifest schema validation is owned by
  `skillbill.scaffold.PlatformPackSchemaValidator` in `runtime-infra/skills`. The
  owning parse seam is `skillbill.scaffold.ShellContentLoader.buildPack`.
- Native-agent composition schema validation is owned by
  `skillbill.nativeagent.NativeAgentCompositionSchemaValidator` in
  `runtime-infra/skills`. The owning parse seam is native-agent source loading and
  composition.
- Telemetry-event schema validation is owned by the MCP adapter because the MCP
  tool registry is the event-name source of truth. The owning parse seam is the
  MCP telemetry tool input validator in `runtime-mcp`.
- Goal declared-progress event schema validation
  (`orchestration/contracts/goal-progress-event-schema.yaml`) is owned by
  `skillbill.infrastructure.contracts.workflow.GoalProgressEventSchemaValidator` in
  `runtime-infra/contracts`, reached through the ports-owned
  `skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator` (the
  `FeatureTaskRuntimeWireArtifactValidator` adapter in `runtime-infra/contracts`
  dispatches to it). The owning durable write/parse seam is
  `skillbill.engine.goalrunner.persist.WorkflowGoalRunnerOutcomeStore.recordProgressEvent`,
  which validates the declared-progress event map through the injected
  `goalProgressEventValidator` before it is appended to the bounded `goal_progress_run_history` /
  `goal_progress_latest_event` workflow artifacts. The supervisor read seam
  (`WorkflowGoalRunnerOutcomeStore.progress`) decodes the latest declared event
  softly so a malformed stored record cannot disable deterministic liveness.
- IDE status schema validation
  (`orchestration/contracts/ide-status-schema.yaml`) is owned by
  `skillbill.infrastructure.contracts.workflow.IdeStatusSchemaValidator` in
  `runtime-infra/contracts`, reached through the domain-owned port
  `skillbill.workflow.idestatus.IdeStatusValidator` (wired in `RuntimeComponent` to
  `IdeStatusValidatorAdapter`). The owning emit seam is
  `skillbill.application.work.IdeStatusService`, which validates before CLI
  JSON emission.
- IDE status selection has a **retention ceiling**
  (`skillbill.application.work.IdeStatusSelectionPolicy.retainedAt`). The IDE
  surface reports work the runtime is currently reporting on, never a ledger of
  every unresolved row. Each tier ages out against its authoritative
  `updated_at`: active/paused after `LIVE_RETENTION` (24h, generous enough that a
  long quiet phase never drops a genuine run), blocked after `BLOCKED_RETENTION`
  (also 24h — blocked work is a prompt awaiting the user, not a finished event),
  and failed/terminal after `SETTLED_RETENTION` (6h). Past the ceiling the
  candidate is dropped and the repository reports `no_matching_work`, so a
  settled or abandoned workflow reads as idle rather than occupying the widget.
  Clock skew (observation before update) never drops work.
  `SETTLED_RETENTION` must stay strictly greater than
  `IdeStatusFreshnessClassifier.FRESH_WINDOW`: equal values make retention and
  freshness exact complements, and no settled snapshot could ever be emitted with
  `freshness: "stale"`.
- The authoritative `updated_at` for a candidate is resolved once, in
  `IdeStatusService.authoritativeUpdatedAt`, and reused for retention, freshness
  classification, and the emitted wire field. Projectors must not re-derive it —
  two anchors for one candidate let the widget freeze its elapsed clocks against a
  timestamp the selection never saw.

## Phase Context Boundary (SKILL-137 handoff projections)

SKILL-146 makes this boundary explicitly four-part: complete producer output is
private evidence; a named consumer projection is the only prompt-visible
derivative; repository state has an immutable checkpoint identity; and
phase-local instructions use workflow-owned invariant allowlists. Declaration
and persistence wires have independent incompatible `0.2` contracts. Delivered
records identify the workflow, consumer, producer iteration, and checkpoint;
the versioned, exact-decoded `FeatureTaskRuntimePhaseRecord` wire is the
authoritative durable private-evidence record written and read by the phase
recorder. It remains under the private phase-record artifact key, separate from
the delivered-projection key and prompt-facing read API. Unknown fields,
missing record identity, and unsupported versions are incompatible rather than
defaulted.
legacy records missing that identity loud-fail with restart or explicit
out-of-band migration guidance. The operator action is deliberately identical at
workflow, briefing, handoff, private-evidence, and delivered-projection read
seams: restart the active run or use the documented out-of-band migration
procedure. Unsupported versions are never defaulted or interpreted as the
current least-context shape.

Budgets are enforced before launch against serialized UTF-8 bytes and
collection items. The runtime never truncates, drops fields, or falls back to a
complete artifact. Measurements contain identifiers, byte/item counts, token
estimates, and failure classifications only, never prompt or evidence bodies.
They are written only through lifecycle telemetry and progress stores; no
measurement or diagnostic field is added to a phase receipt or other
prompt-consumable domain artifact.

A feature-task-runtime phase no longer receives the complete output of its
upstream phases. Context reaching a phase is split into four parts with distinct
owners, storage, and failure modes.

**1. Private evidence.** Complete validated phase output stays on
`FeatureTaskRuntimePhaseRecord.outputArtifact` (and `rejectedOutput` for
schema-rejected attempts) under the
`feature_task_runtime_phase_records` artifact key. It is the run's durable record
of what each phase actually produced. Nothing reads it into a prompt directly.

**2. Consumer projection.** What a phase receives is declared, not inferred.
`PhaseHandoffProjectionDeclaration` (`runtime-domain`) names one source, one
projection contract id/version, prompt visibility, a UTF-8-byte and
collection-item budget, and a repository-checkpoint policy.
`FeatureTaskRuntimePhaseDeclaration.projectionDeclarations` is the sole place a
source can be declared; `consumedUpstreamPhaseIds` is derived from it, so a
recorded output with no declaration is never delivered.
`FeatureTaskRuntimeHandoffProjectionValidator` turns declarations into a
`FeatureTaskRuntimeHandoffEnvelope` of named typed projections and compact
references. It rejects — never truncates — on a missing required source,
malformed or undeclared field, unsupported contract version, duplicate
projection name, budget overflow, invalid compact reference, or
checkpoint-policy violation, each through
`InvalidFeatureTaskRuntimeHandoffProjectionError` naming the workflow, consumer
phase, projection, and contract without echoing payload bodies. The envelope has
its own Draft 2020-12 contract
(`orchestration/contracts/feature-task-runtime-handoff-envelope-schema.yaml`,
pinned by `FEATURE_TASK_RUNTIME_HANDOFF_ENVELOPE_CONTRACT_VERSION` and
`FeatureTaskRuntimeHandoffEnvelopeSchemaContractVersionTest`), reached from the
domain only through the `FeatureTaskRuntimeHandoffEnvelopeValidator` port with
`FeatureTaskRuntimeHandoffEnvelopeValidatorInfraAdapter` in `runtime-infra/contracts` —
the same domain/infra validator-boundary convention as
`WorkflowSnapshotValidator`. Delivered envelopes persist separately from private
evidence as `FeatureTaskRuntimeDeliveredProjectionRecord` under
`feature_task_runtime_delivered_projections`; the two artifact keys must never
merge, because merging them is exactly how a round trip could hand a consumer
the private artifact in place of its projection. Raw-map exposure is confined to
private adapter serializers outside the inner-layer public surface.

A projection may declare `inlineAlternative` to deliver a lossless compact
reference instead of inline content. A `private_evidence_artifact` reference is
accepted only when the declaration also sets `allowsPrivateArtifactReference`,
and the reference itself is minted by the runtime from the source's durable
identity, so dereferencing it is a deterministic runtime operation rather than
model-driven retrieval.

**3. Repository-derived context.** `FeatureTaskRuntimeRepositoryCheckpoint`
carries a deterministic fingerprint, optional base/head refs, and working-tree
ownership. Policies are `not_required`, `must_match`, and
`refresh_from_repository`. Both checkpoint-aware policies require and carry a
freshly resolved checkpoint. `must_match` is retained as a legacy durable wire
value and, like `refresh_from_repository`, accepts repository movement and
re-derives the consumer scope. The domain stays git-agnostic: the application layer resolves
the checkpoint in `FeatureTaskRuntimeRunLoop` through the existing
`WorkflowGitOperations` port, reusing the same `repositoryFingerprint` extension
the audit-repair path already depends on. No new git port was introduced.

**Shared review evidence (SKILL-164).** Branch/commit review evidence is derived
once per `FeatureTaskRuntimeRepositoryCheckpoint.fingerprint` into a repo-local
artifact under `.skill-bill/run-evidence/<workflowId>/<fingerprint>/`. The
delivered projection is a reference only — `store_path` plus a bounded
file/hunk index — never inlined diff bytes, so the planning-projection budget
stays independent of branch diff size. The contract is
`orchestration/contracts/feature-task-runtime-shared-evidence-projection-schema.yaml`,
pinned by `FEATURE_TASK_RUNTIME_SHARED_EVIDENCE_PROJECTION_CONTRACT_VERSION` and
validated on every store read: schema-invalid or unreadable content re-derives,
while a fingerprint that contradicts its addressed location loud-fails. Audit
consumes this projection as a floor alongside `scoped_repository_state`; it
never replaces audit's scoped repository read, because audit's highest-value
finding is a criterion with no code behind it and therefore no diff. Telemetry
records each resolve as `skillbill_feature_task_runtime_shared_evidence` with
outcome `derivation`, `reuse`, or `checkpoint_change_rederivation`.

That publication address is also the ownership authority. `isRuntimePrivatePath`
covers the rest of the `.skill-bill/` root but deliberately does not claim
`run-evidence`; `FeatureTaskRuntimeCheckpointScope` resolves ownership through
`FeatureTaskRuntimeRunEvidenceOwnership` against the active run's workflow id,
so only artifacts at this run's own address are runtime-owned and non-blocking.
Another workflow's artifact, or a file forged directly under the store root,
receives no exemption: it is preserved and stays an ordinary actionable path.
Ownership therefore never derives from the path prefix alone, and both failure
directions are covered — a prefix-free rule would block the run's own evidence,
and a blanket prefix rule would silently sweep the forged file.
`FeatureTaskRuntimeRunEvidenceAddress` owns the single address derivation both
the store adapter and the engine read. `reconcileCheckpointPathInventory` applies
the same ownership test, so the run's own evidence never enters the durable
`workflow_owned_paths` inventory the goal review pathspec and the checkpoint
fingerprint are built from, whichever producer writes that inventory.

Finalization path inventories come from the checkpoint's runtime-resolved
base/head and scoped owned-path comparison. Implementation receipt paths are
claims only: validation scope, boundary candidates, commit inclusions and
exclusions, and PR changed paths are derived from the resolved inventory. Runtime
continuation exposes bounded validation, boundary, history,
commit, and PR requests or receipts; it never substitutes the private audit,
review, implementation, validation, or history artifacts. The `build` phase
(SKILL-204) runs only the pack `validation_gate.build_command` for
compile/buildability proof and never invokes the collect-all validation gate.
Default standalone runs skip `build` (`review -> validate`); goal continuation
stamps which quality gate a child runs (subtask 2).

`SkeletonDefinition.VALIDATION` binds `PackValidationStrategy` to `PHASE_VALIDATE`.
It runs the dominant pack's `collect_all_full_gate_command`, repairs parsed
findings in the same agent session, then runs
`cache_bypassing_collect_all_full_gate_command` to verify. Goal-child `BUILD`
stays on `PackBuildStrategy`, which uses only the two build commands. Each gate
run records its effective argv, exit code, and repository checkpoint. A passed
receipt requires a non-empty run list and a successful terminal required command;
an earlier pass cannot cover a failed verification. Missing required declarations
or command members block both durable and in-memory routes. Historical receipts
without command semantics block recovery and remain available for inspection. The
build receipt and validation evidence contracts are versioned independently; a
reader rejects legacy evidence when it cannot prove current command semantics.

**4. Phase-local instructions.** Run identity remains durable state on every
briefing, but prompt rendering is selected per phase by
`FeatureTaskRuntimeRunInvariantPromptAllowlist`.
`FeatureTaskRuntimeRunInvariantPromptField` classifies each invariant as
identity, acceptance-contract, policy, ceremony, review, add-on, or finalization.
Identity, ceremony, and policy mandates reach every phase; the acceptance
contract is withheld from the finalization phases (`write_history`,
`commit_push`, `pr`), which act on work audit and validate already settled.
Policy mandates are not withheld: they are free-form operator directives that
govern the irreversible outward-facing phases, and this allowlist is their only
delivery path.
Hydrated add-on content is scoped by the manifest-owned
`feature_addon_usage.feature-task` consumer assignment, which is run-scoped:
every phase of a feature-task run is that consumer, so there is no narrower
per-phase gate in `FeatureTaskRuntimePhasePromptComposer.budgetedAddonsFor`.
What that seam does own is the budget — hydrated add-on content is budgeted
independently of phase receipts, so neither budget can borrow the other's
headroom and an oversized add-on rejects rather than inflating the briefing.

`FEATURE_TASK_RUNTIME_PHASE_BRIEFING_PAYLOAD_BYTE_CEILING` now bounds only the
non-projection framing. Projection bodies are bounded by their own declared
budgets, which is what makes the no-truncation guarantee expressible: the
assembler has no budget left to split, so it has nothing to truncate.

The shipped per-edge declarations are currently one coarse whole-receipt
projection per edge (`FeatureTaskRuntimePhaseWorkflowDefinition.upstreamReceiptProjections`).
That proves the mechanism is load-bearing without yet claiming any edge is
minimally scoped; fine-grained named-field projections replace them per edge
later. Those coarse projections are declared `required = false` because presence
of a declared upstream output is already gated ahead of launch by the run loop's
missing-upstream block; the validator's required path stays load-bearing for
declarations that own their own presence contract.

Because those coarse receipts carry a whole phase output, their budgets are sized
against recorded runtime phase outputs rather than picked as round numbers: no
phase other than `preplan` exceeded 20,844 UTF-8 bytes across 239 durable
outputs, while `preplan` reached 131,901. Hence `PHASE_RECEIPT` (65,536 bytes)
for every edge and `PREPLAN_DIGEST_RECEIPT` (196,608 bytes) for the single
`preplan` -> `plan` edge. A rejection therefore means a phase output grew far
beyond every observed size, not that an ordinary run outgrew its budget. Re-size
them from the same measurement when the delivered shape narrows to named fields.

When a projection is rejected anyway, `FeatureTaskRuntimeRunLoop` catches
`InvalidFeatureTaskRuntimeHandoffProjectionError` at the launch seam and blocks
the phase through the ordinary `blockAndPersistInPhase` path with a
`needs_user_action` disposition. The rejection is static declaration or
configuration drift rather than agent output, so retrying without operator action
reproduces it; blocking durably keeps the phase row and the run's finalization
consistent instead of unwinding out of a run that already persisted
`STATUS_RUNNING`.

### Producer-side enforcement (SKILL-140 Subtask 1)

A bounded planning projection was validated only at its consumer's launch seam,
where the producing phase is already settled `completed`. A malformed digest,
plan, or receipt therefore blocked the *next* phase — with no fix loop able to
reach the phase that actually wrote it — and the run wedged. The producer gate
closes that gap: a completed phase that owns a projection must emit one its
consumer can parse, checked at the producing phase's own schema gate so a
violation re-enters that phase's bounded fix loop and blocks only at the existing
cap.

`FeatureTaskRuntimePlanningProjectionContract.producedProjectionKindFor` is the
single domain-owned routing map from producing phase id to the projection kind it
owes (`preplan` -> `preplanning_digest`, `plan` -> `executable_plan`, `implement`
-> `implementation_receipt`, and null for every other phase, including the derived
`plan_commitment`, which no phase produces). `producerProjectionGateReason` in
`FeatureTaskRuntimeRunnerPolicies` reads that map and, for a completed envelope
whose phase owns a kind, calls the same `featureTaskRuntimePlanningProjectionFromEnvelope`
with the same `planningProjectionValidator` port the launch seam uses — no
projection rule is restated at the gate. `FeatureTaskRuntimePlanningProjectionEdgeTest`
binds the two sides so any envelope the gate accepts the launch seam accepts for
the corresponding consumer edge, and neither can be made stricter than the other.

The gate runs in `settleValidatedOutput` only after `terminalBlockedReasonFrom`,
so a blocked or failed envelope — whose `produced_outputs` carries blocking
reasons, not a projection claim — settles through the terminal path and never
reaches the gate. A `decompose`-mode plan is likewise exempt: it terminates the
run at planning and hands the planning stopper a separately-contracted
decomposition package (`featureTaskRuntimeIsDecompositionPackage`), which no
consumer parses as an executable plan. That exemption is scoped to the
executable-plan producer (`plan`), the only phase with a decompose stopper
backstop; a `preplan` or `implement` output merely shaped like a decomposition
package has no backstop and still faces the gate, so it cannot settle `completed`
and wedge its consumer. The rejection reason names the phase, the
expected projection kind, and the underlying validation failure (its source label
plus reason), bounded by the existing `SCHEMA_GATE_DETAIL_MAX_CHARS` schema-gate
detail truncation — no second truncation rule.

### Quarantine-and-regenerate (SKILL-140 Subtask 4)

Producer-side gating (Subtask 1) reduces launch-seam rejections to legacy and
drift records — precisely the population an in-band recovery edge can repair.
When `FeatureTaskRuntimeRunLoop.launchAndCapture` catches
`InvalidFeatureTaskRuntimePlanningProjectionSchemaError` or an
`InvalidWorkflowStateSchemaError` on an upstream handoff envelope, it no longer
blocks on first occurrence. Instead the consumer settles with the synthetic
`RECORD_REJECTED` verdict, which drives the existing
`FeatureTaskRuntimeTransitionFunction` over a pinned consumer→producer
regeneration edge (`plan`→`preplan`, `implement`→`plan`, each with its own
`regenerate_*` loop id and the `MAX_RECORD_REGENERATION_ATTEMPTS` cap). No
parallel state machine is introduced: the same loop-id, edge-iteration,
watermark, and crash-resume machinery the review-fix loop uses bounds
regeneration, so a crash mid-regeneration resumes the same cap sequence without
reset.

Before the edge fires, the rejected record is appended to a durable, append-only
quarantine store (`FEATURE_TASK_RUNTIME_QUARANTINED_RECORDS_ARTIFACT_KEY`,
validated by the canonical quarantine schema). That store is private evidence: it
is never resolved into an upstream projection, so no rejected byte reaches an
agent prompt or briefing, and no runtime path ever mutates or deletes an entry —
only out-of-band operator action may. The producer's settled `completed` status
is invalidated through the existing phase-record machinery (its rejected payload
moves to `rejected_output`, its status returns to `running`), so the handoff
contract's `selectLatestOutputsByPhase` no longer surfaces the rejected record and
the regenerated higher-iteration output supersedes it on this or any resumed run.

Cap exhaustion blocks durably with a reason naming the quarantined record, its
producing phase, and the attempt count. A record the runtime cannot attribute to
a producing phase, or whose producer a goal-continuation truncation dropped from
the resolved pipeline, blocks durably with an actionable reason rather than
attempting an impossible re-entry. Static declaration/config drift
(`InvalidFeatureTaskRuntimeHandoffProjectionError`), briefing byte-ceiling
overflow keep their first-occurrence durable block: re-running a producer cannot
fix them.
Out-of-band row deletion or migration is the corruption fallback for records the
edge cannot regenerate. Per-run regeneration telemetry records activation counts,
attempt counts, and outcome-class tallies on the
`skillbill_feature_task_runtime_finished` event — counts and class labels only, never record contents.

Canonicalization and reconciliation of malformed durable projection records
beyond this recovery edge belong to later SKILL-140 subtasks.

## Install Policy Ownership (SKILL-52.1 install-policy-foundation)

Install request validation and pure install-plan construction live in
`skillbill.install.policy` inside `runtime-domain`. The policy consumes typed
snapshots from `skillbill.install.model`: discovered base skills, platform pack
skills, detected agent targets, and default agent target paths. It resolves
selected platforms, planned skills, agent targets, MCP registration intent, and
the typed `InstallPlanDraft` without touching filesystem, process execution,
staging hashes, symlink checks, binary discovery, or rollback mechanics.

### Infrastructure `java.util.logging` owners (SKILL-353)

Contract validators log schema drift at `WARNING` through
`skillbill.infrastructure.contracts.locator.logSchemaLoadFailure`
before throwing the family's `Invalid*SchemaError`; that is the operator signal
for packaged-schema versus runtime-contract version skew, not a silent fallback.
`InstallStaging` and staging I/O log reuse and failure at `FINE`/`SEVERE`.
`JvmAgentRunProcessRunner` and `ProcessRunDegradationRecorder` export bounded
degradation lines to stderr; `degradationExportLogger` records sink failures
only. `JdkRuntimeDiagnostics` mirrors `RuntimeDiagnostics` warnings when no
injectable port exists at the JDK adapter seam.

The remaining `java.util.logging` owners are `InstallStaging`,
`InstallStagingIO`, `InstallStagingAtomicMoves`, and `InstallStagingPrune`
(staging reuse, rollback, and cleanup have no diagnostics port);
`InstallSymlinkReplacement` (symlink replacement cleanup);
`FileSystemDiffResolver` (bounded git-diff failure context);
`FileSystemFeatureTaskRuntimeSharedEvidenceStore` (projection-cache
degradation export);
`SkillRemoveJvmFileSystemApply` (uninstall cleanup);
`NativeAgentCompositionSchemaValidator` (schema drift before a typed failure);
`PlatformPackSchemaValidator` (tolerated legacy manifest version);
`WorkflowStateSchemaValidator`, `IdeStatusSchemaValidator`,
`GoalProgressEventSchemaValidator`, `GoalObservabilityEventSchemaValidator`,
`GoalPlanningPreparationSchemaValidator`, `InstallPlanSchemaValidator`,
`DecompositionManifestSchemaValidator`, and `ReviewContextSchemaValidator`
(schema drift before typed rejection); and `JdkRuntimeDiagnostics`,
`JvmAgentRunProcessRunner`, and `ProcessRunDegradationRecorder` (the JDK
adapter and bounded process/degradation export seams have no injectable
secondary sink). These are intentional adapter-boundary logs; new fallback
degradations use `RuntimeDiagnostics`.

`runtime-infra/skills` remains the owner of these filesystem mechanics: platform
manifest discovery and schema parsing, base-skill directory scans, agent
detection/default path probing, pointer realpath validation, content hashing,
staging path computation, symlink/native-agent/MCP/apply side effects, Windows
preflight, and rollback behavior. The infra builder converts those facts into
typed snapshots before calling the policy.

The install-plan wire map remains the schema source of truth at both existing
seams. `buildInstallPlan` still calls
`wireValidator.validate(buildInstallPlanWireMap(plan))`, and the CLI emission
boundary still revalidates the same wire map before emitting `installPlanPayload` or the
planning prefix of `installApplyPayload`. New install policy APIs must use typed
request/result/snapshot models and must not add public raw `Map<String, Any?>`
returns outside the documented open-boundary allow-list. Adapter modules may
call the shared wire-snapshot validator only at the approved builder and CLI
emission seams; they must not import the schema validator directly or declare
install planner/validator policy.

## Cross-Adapter Contract Owners (SKILL-371 subtask 3)

- **Repository identity:** `RepositoryEnclosingRootPort.repositoryIdentity` in
  `runtime-infra/host` (`CanonicalRepositoryRoot`) is the only production
  function that joins `FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX`
  to a canonical Git top-level path. CLI, engine, and SQLite callers obtain
  identity through that port (or `goalRepositoryIdentity`, which forwards to it).
  Governed feature-task spec paths resolve in
  `skillbill.application.workflow.resolveFeatureTaskGovernedSpecPath`.
- **Goal-child launch protocol:** flag and environment spellings for
  `feature-task run` / `resume` live in
  `skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationLaunchTokens`.
  Launcher argv/env builders and CLI option readers reference those constants only.
- **Scaffold payload:** `skillbill.application.scaffold.decodeScaffoldCommandRequest`
  (JSON text or `JsonObject` only; no raw `Map` at the public seam) and
  `runScaffoldInvocation` own UTC session ids, `repo_root` defaulting, and
  opt-in external-source registration. `runtime-cli` and `runtime-mcp` call those
  entry points; adapter-local payload parsers are not duplicated.

## Scaffold Capability Ports And Pure-Policy Ownership (SKILL-52.1 subtask 2)

`ScaffoldGateway` in `skillbill.ports.scaffold` is the typed port consumed by
`runtime-cli` and `runtime-mcp` through `RuntimeComponent`, per the
[2026-09-03] load-bearing thin ports decision in `agent/decisions.md`.
SKILL-52.3 subtask 3 closed the public `ScaffoldGateway` raw-map migration
(typed `Scaffold*Result` DTOs on every producer). SKILL-231 subtask 3 retained
`ScaffoldGateway` and `ScaffoldCatalogGateway` through `RuntimeComponent` when
pass-through application services were collapsed. Adapter-internal payload maps
under `runtime-infra/skills/src/main/kotlin/skillbill/infrastructure/skills/scaffold/` remain for YAML ingress, planning merge,
and wire serialisation; they do not widen the port surface.

- **Pure-policy ownership boundary:** every payload-shape rule, kind
  discriminator, subagent-rejection rule, platform-pack selection/defaults/
  notes computation, install-path builder, and platform-pack manifest YAML
  renderer that has no filesystem dependency lives in
  `skillbill.scaffold.policy` inside `runtime-domain`. Files in
  `runtime-domain/src/main/kotlin/skillbill/scaffold/policy/` MUST NOT
  import `skillbill.infrastructure.skills.*`,
  `skillbill.scaffold.ScaffoldService`, or
  `skillbill.scaffold.FileSystem*`. The
  `ImplementationOwnershipArchitectureTest.scaffoldPolicyPackagesMustNotImportInfraFs`
  test enforces this prospectively.
- **Scaffold IO surface:** `ScaffoldGateway` is the only scaffold IO port.
  SKILL-377 deleted the per-capability scaffold ports that no caller
  injected. `FileSystemScaffoldOrchestrator` in
  `runtime-infra/skills/src/main/kotlin/skillbill/infrastructure/skills/install/scaffold/`
  uses the concrete `adapters/FileSystemScaffoldSourceLoader` and
  `adapters/FileSystemScaffoldRepoValidation` classes directly. The
  `skillbill.ports.scaffold.source.model` and
  `skillbill.ports.scaffold.repo.model` packages keep the result models that
  `ScaffoldGateway` returns. `FileSystemScaffoldGateway` implements the typed
  `ScaffoldGateway` port.
- **Adapter-internal raw-map functions** (`Map<String, Any?>` only inside
  `scaffold/`; not part of `ScaffoldGateway`):
  - `adapters/FileSystemScaffoldRepoValidation.kt` — `internal`:
    `optionalBaselineLayers`
  - `adapters/FileSystemScaffoldSourceLoader.kt` — `internal`:
    `resolveAddonConsumerSkillDirs`
  - `authoring/AuthoringRenderOutput.kt` — `public property`: `payload`
  - `payload/ScaffoldCommandRequestRawPayload.kt` — `internal`:
    `toRawScaffoldPayload`; `private`: `appendAgentAddonFields`,
    `appendHorizontalFields`, `appendPlatformPackFields`,
    `appendPlatformOverrideFields`, `appendCodeReviewAreaFields`, `appendAddOnFields`
  - `payload/ScaffoldPayloadMapPolicy.kt` — `internal`:
    `validatePayloadVersion`, `detectKind`, `requireStringMap`,
    `requireStringOrDefaultMap`, `rejectBaselineLayersForNonPlatformPack`
  - `payload/ScaffoldPayloadMapPlatformPackPolicy.kt` — `internal`:
    `resolvePlatformPackSelection`, `resolvePlatformPackDefaults`;
    `private`: `rejectLegacyPlatformPackSelector`
  - `payload/ScaffoldPayloadMapSubagentPolicy.kt` — `internal`:
    `optionalSpecialistSubagents`, `rejectLeafSubagentSpecialists`
  - `platformpack/PlatformPackSchemaValidator.kt` — `public member on an internal class`:
    `validate`
  - `platformpack/ShellContentLoaderPackBuild.kt` — `internal`:
    `assemblePlatformManifest`, `extractCustomFields`, `validatedCustomFields`,
    `validateAgainstCanonicalSchema`
  - `runtime/RepoValidationRuntime.kt` — `public`: `toPayload` (report wire types)
  - `runtime/ScaffoldService.kt` — `internal`: `scaffoldWithAdapters`
  - `runtime/ScaffoldServicePlanning.kt` — `internal`: `resolveRepoRoot`,
    `planScaffold`, `planHorizontal`, `planPlatformOverridePiloted`, `planPlatformPack`,
    `rejectPlatformPackSubagentOverrides`, `planCodeReviewArea`
  - `runtime/ScaffoldServicePlanningPayloadMerge.kt` — `internal`:
    `planAddOn`, `planAgentAddon`
  - `runtime/ScaffoldServiceRollbackPayload.kt` — `internal`: `canonicalName`,
    `optionalAddonLocationPath`
  - `runtime/ScaffoldStandaloneEntrypoint.kt` — `public`: `scaffold`
    (standalone JVM entry)
## Architecture Guardrails

The architecture suite lives in `runtime-core/src/repoTest/kotlin/skillbill/architecture`
and runs in `:runtime-core:repoTest`. That task declares every module's `src/**`,
every `*.gradle.kts`, `ARCHITECTURE.md`, `agent/**`, `build-logic/convention/src/**`,
`config/**`, and `.editorconfig` as inputs, so editing any source the scanners read
invalidates the task instead of leaving a stale pass. Installer and launcher shell
tests run in `:runtime-cli:repoTest`, which reads `install.sh` and `uninstall.sh`
through the shared governed-repository inputs.

Test placement: the subject's module owns its test. `runtime-core` owns composition
and multi-adapter integration tests under `skillbill.di.*`. Repository-contract
suites — the ones that read governed sources outside their own module — live in
`src/repoTest`.

`PrincipleEnforcementInventory.enforceableRules` pairs each mechanically checked
rule with the test class that proves it. The tests also enforce the following
boundary rules:

- `runtime-core` contains only `skillbill.di` source packages.
- `runtime-core`'s public project edges match `RuntimeComponent`'s generated
  public ABI exactly, read from the component source rather than from prose.
- Infrastructure modules do not depend on runtime-core, CLI, MCP, or
  sibling concrete infrastructure adapters.
- CLI and MCP adapters declare direct runtime dependencies and do not
  use runtime-core as an implementation umbrella.
- CLI and MCP adapters call application services and ports instead
  of importing concrete install, scaffold, native-agent, launcher,
  skill-remove, SQLite, HTTP, validation, or filesystem implementation
  internals.
- MCP workflow calls must use application services.
- Application services remain independent from entry-point frameworks,
  concrete persistence, direct filesystem access, Java HTTP clients, and JDBC.
- repository and unit-of-work ports are the persistence boundary.
- versioned database migrations are recorded in `schema_migrations`.
- learning application use cases return typed results.
- Domain and port layers remain independent from adapters, infrastructure,
  composition roots, and implementation details.
- Public application, domain, and port model declarations live under `model`
  packages.
- LearningRecord is owned by the learnings domain.
- review parsing and triage decision normalization are pure surfaces.
- SQL-backed review persistence lives under `skillbill.infrastructure.sqlite.review`.
- telemetry proxy payload mapping belongs with the HTTP adapter.
- Learning, review, telemetry, workflow, install, scaffold, and skill-remove
  ownership stays in the packages named above.
- Workflow-state, install-plan, decomposition-manifest, platform-pack,
  native-agent composition, and telemetry-event schema validators are exercised
  at their owning parse seams.
- Every `runtime-cli` command area's transitive `skillbill.cli` import closure
  contains only the shared `kernel` and `model` leaves, never a sibling command
  area and never the composition root `skillbill.cli.core`.
- No runtime module source file, and no main-source file, type, or member
  declaration, carries the spillover signature (`*Extras`, `*Continued`,
  `*Helpers`, `*Support`, `*Misc`, `*Fns<N>`, letter-plus-digit, or bare
  trailing-digit siblings) outside a named exemption; the bare `Support`,
  `Helpers`, `Misc`, and `Extras` forms apply to `src/main` only, the numbered
  forms to every `src` tree.
- No main-source site outside `skillbill.di` constructs a concrete class censused
  from `@Provides` parameter types and explicit Provides constructions;
  `RuntimeCompositionGuardArchitectureTest` matches import aliases, ignores comments
  and string literals, and skips unrelated same-named functions.
- `RuntimeComponent` logical service properties are pinned separately from `@Provides`
  generated wiring; `RuntimeComponentInboundApiArchitectureTest` rejects any other
  public function on `RuntimeComponent` or a `Runtime*Provides` mixin even when the
  abstract property set is unchanged.
- A failed `uninstall` mutation is a recorded degradation with a non-zero exit
  code, shared by launcher removal, desktop removal, recursive tree removal,
  agent-target cleanup, native-agent unlinking, and MCP unregistration.
- The Raw Map Boundary Rule (rule 11) is enforced by
  `RuntimeRawMapArchitectureTest.runtime architecture forbids public raw map
  shapes in inner layers` with zero-tolerance: the only allow-listed members
  are the four `DurableWorkflowArtifactFamily` accessors (`contains`, `value`,
  `putInto`, `removeFrom`), public declarations typed exactly `Any` are
  rejected, and there is no annotation grandfather path.

Architecture scanners use `ArchitectureScanSupport.runtimeRoot` as the
repository root that contains `runtime-kotlin`. A named module source root is
resolved with `RuntimeModuleCatalog.runtimeKotlinModuleDirectory` and the
`src/main/kotlin` suffix. Missing named roots fail the scan; they do not
produce an empty passing result. This applies to
`engineInboundApiViolations`, `mainPackageRootsForModule`,
`ArchitectureScanSupport.kotlinFilesUnder`,
`ArchitectureScanSupport.authoredKotlinSourcesUnder`, and the ownership,
install-policy, enforcement-hardening, ports-declaration, port-null-object,
contract-import, and skills-import walkers. `RuntimeRawMapArchitectureTest`
and `RuntimeArchitectureTest` match
`runtime-kotlin/<module>/src/main/kotlin/`.

### Line-ceiling and cycle guardrails

`ProductionLogicalTypeLineCeilingArchitectureTest` attributes each production
Kotlin file to a logical type: type-declaring files bill to the first top-level
named type FQN; extension-only files bill every line to each distinct
extension-receiver FQN. A shrink-only baseline records offenders above the
1200-line ceiling; baselined units may only shrink and unlisted units must stay
at or below the ceiling.

`ApplicationPackageAcyclicityArchitectureTest` tracks mutual import pairs among
the areas of one package prefix under one scan root, both passed as parameters.
The `runtime-application` baseline is shrink-only; any new mutual-import pair
not already baselined fails the build.

`RuntimeApplicationAmbientClockArchitectureTest` bans `Instant.now()`,
`LocalDateTime.now()`, `LocalDate.now()`, `OffsetDateTime.now()`,
`ZonedDateTime.now()`, `Clock.systemUTC()`, and `JvmSystemClock.instant()` in
every module main source root declared by
`PrincipleEnforcementInventory.moduleArchitectureScanCases`. One iterating test
compares each module against its baseline; a rejection fixture seeds a synthetic
violation into a temporary `runtime-engine` tree and asserts the rule reports it.

Ambient-clock and ambient-environment baseline rows are keyed `path:call:count`,
grouped per file and call form. Inject-default rows are keyed
`path::Symbol::parameter`. Neither form carries line numbers, so moving a call
or declaration to another line leaves the baselines unchanged.

`InjectConstructorDefaultsArchitectureTest` bans default arguments on
`@Inject` constructors and dependency bags consumed by them, and non-private
property initializers on an `@Inject` class that declares no primary
constructor, in every declared module main source root. Production wiring must
bind every port explicitly in `RuntimeComponent`; test-only stubs such as
`ApprovingReviewPhaseRunner` are never reachable through an unbound dependency.

The scanner strips comments and string and character literals before it walks
delimiters, so a default whose literal holds an unbalanced brace or paren does
not hide the properties declared after it. The `runtime-application` baseline is
empty by rule, not by census: the recorder never rewrites it and the test
asserts it stays empty, so a new default fails the build instead of being
recorded away.

### Ambient-environment and command-area guardrails

The acyclicity, ambient-clock, and `@Inject`-defaults scanners are shared, not
copied: each takes its scan root (and, for acyclicity, its package prefix) as a
parameter, and each rule iterates every module scan case over the same scanner
body. A second copy of a scanner scoped to another module is not an acceptable
substitute.

`AmbientEnvironmentArchitectureTest` bans `System.getenv`, `System.getProperty`,
`Path.of("")`, and `Paths.get("")` under a parameterized scan root. Its scope is
the scan root plus a recorded baseline per module, with no per-pattern carve-outs;
test infrastructure stays outside the scanned root. Named file-path exemptions on
`PrincipleEnforcementInventory.ambientEnvironmentExemptions` omit a process entry
from baseline recording only; every other main-source site must still match an
empty baseline. That list names
`runtime-kotlin/runtime-mcp/src/main/kotlin/skillbill/mcp/core/Main.kt` as the
MCP process boundary and
`runtime-kotlin/runtime-core/src/main/kotlin/skillbill/di/core/RuntimeBootstrapBindings.kt`
as the runtime bootstrap boundary.

Every module case asserts set equality against its baseline rather than absence
of unlisted sites, so a scanner that ignored its scan-root or package-prefix
parameter cannot pass against a stale baseline; where the baseline is empty that
equality is a hard ban. Regenerate these baselines from the scanners with
`RECORD_ARCHITECTURE_BASELINES=1`, never by hand.

`RuntimeCliAreaIsolationArchitectureTest` proves what an empty cycle baseline
cannot: every command area's transitive `skillbill.cli` import closure must
contain only the shared leaves `skillbill.cli.kernel` and `skillbill.cli.model`,
never a sibling command area and never the composition root
`skillbill.cli.core`. A cycle baseline can be emptied by moving a single import
even when the areas stay entangled through one-directional hub edges, so the
closure assertion is the guard that any command area builds and tests alone.
`RuntimeCliAreaIsolationArchitectureTest` also rejects `featuretask` production
sources that construct `RejectedOutputDiagnosticService` or call
`unitOfWork.diagnosticService`; rejected-output CLI routes through
`RejectedOutputDiagnosticInspection` in `runtime-application` instead. The
scanner does not treat Clikt `.default(".")` on `--repo-root` as equivalent to
`Path.of("")`; omitted roots resolve through `resolveCliRepositoryRoot` and
`CliRunInputs.repositoryRoot`.

The scan enumerates every area it finds under `skillbill.cli` and exempts one
name, `CLI_COMPOSITION_ROOT_AREA`; probing a single hand-picked area would let a
one-directional edge such as `goal -> featuretask` pass both guards.
`skillbill.cli.core` holds only the composition root — `CliComponent`,
`CliRuntime`, `Main`, `SkillBillCommand`, `CliCommandGroups`, and
`CliUtilityCommandGroups` — and it is the only package that may import a
command area. `install` therefore owns its own command tree and top-level
group, and the units two command areas share — the completion telemetry drain
and the `WorkflowUpdateResult` payload mapper — live in `skillbill.cli.kernel`.

`RuntimeSpilloverFileNameArchitectureTest` bans the spillover filename signature
across every module source root. Exemptions are a named list on
`PrincipleEnforcementInventory`, empty by rule, never an ad-hoc regex carve-out.
The 1200-line per-file ceiling (2026-09-04 decision) moves only by decision
entry, never by baseline or exemption: a re-merged unit above it fails the
logical-type ceiling instead.

### Inward-layer guardrails

The package-acyclicity, ambient-clock, ambient-environment, and
`@Inject`-defaults scanners are instantiated once per Gradle module through
`PrincipleEnforcementInventory.moduleArchitectureScanCases`, driven by
`RuntimeModuleCatalog.declaredGradleModules`. Each case supplies its own main
scan root and package prefix (or scan root alone for ambient-environment and
inject-defaults) rather than forking a second scanner class.

`AmbientEnvironmentArchitectureTest` takes its scan root as a parameter; every
module main source root has a recorded baseline. The `runtime-cli` baseline
remains empty by rule.

`RuntimeSpilloverFileNameArchitectureTest` scans every module's `src` tree
(main and test), matching `*Extras`, `*Continued`, `*Helpers<N>`, `*Fns<N>`,
`*Support<N>`, `*Misc<N>`, letter-plus-digit suffixes, and bare trailing-digit
names when a de-digited or differently digitized sibling exists in the same
package directory. Under `src/main` the bare `*Support`, `*Helpers`, `*Misc`,
and `*Extras` forms are banned too, and the same pattern runs over every
top-level and member declaration name (class, object, interface, fun, val, var)
with string literals and comments stripped. File violations are keyed on
repository-relative paths and identifier violations on `path#name`, both against
`baselines/spillover-file-name-baseline.txt`.

`RuntimeModuleCatalog.moduleEdgeExpectations` owns every module's expected
`api(project(...))` and `implementation(project(...))` sets; `RuntimeCoreCompositionOnlyTest`
compares Gradle files to that authority. `runtime-core` keeps
`api(:runtime-application)` and `api(:runtime-ports)` as the kotlin-inject ABI
edges. Every `runtime-infra` module (`host`, `contracts`, `skills`, `launcher`,
`workflow`, `http`, and `sqlite`)
narrow `api(:runtime-ports)` and `api(:runtime-domain)` to `implementation`.
`runtime-cli` carries no `api` project edges.

Baselines that were empty on main (`runtime-application` and `runtime-cli`
package-cycle, ambient-clock, ambient-environment, and inject-defaults baselines,
plus the runtime-application inject-defaults floor) stay empty by rule. Module
baselines recorded here are shrink-only ceilings: they may only shrink, never
grow without an explicit baseline update through the recorder.

### Port null-object classification

`PortNullObjectAbsenceArchitectureTest` requires that no `Unavailable`, `Noop`,
`Empty`, or `Unconfigured` object is declared in any runtime module's main
source. A port whose absence a production call site actually reaches is
nullable, and the reached site names its fallback (`?: JdkHttpRequester`,
`?: git`) or returns the absent answer. The substitutes that tests still need
live in the owning module's `src/testFixtures` under their original packages,
so they are unreachable from a published runtime.

`RuntimeContractModuleImportRulesTest` pins the two inward layers: `runtime-ports`
declares interfaces and DTOs and imports no adapter machinery
(`java.io`, `java.nio.file.Files`, `kotlinx.serialization`, `me.tatarka.inject`,
`org.yaml`), and `runtime-domain` imports no serialization, charset, or IO
library (`java.io`, `java.nio.charset`, `com.fasterxml`, `kotlinx.serialization`,
`org.yaml`). Domain code reaches JSON only through the stdlib-typed
`skillbill.contracts.JsonCodec` facade and text encoding only through
`kotlin.text.Charsets`. Both guards assert an empty violation list; neither
carries a baseline.

`data object` cases of a sealed hierarchy — `ValidationGateTriageResult.Empty`
is the one in the tree — are not substitutes and the census excludes them.

### Destructive command failure policy

`uninstall` is the runtime's only destructive command. A mutation it cannot
apply is a recorded degradation with a non-zero exit code, never a warning
string on a zero exit. Launcher removal, desktop removal, recursive tree
removal, agent-target cleanup, native-agent unlinking, and MCP unregistration
share that one policy through `UninstallMutationRecorder`, which owns it: each
site hands the recorder the failed mutation, the recorder emits a
`skillbill.ports.diagnostics.RuntimeDiagnostics` error record and contributes
to a failed outcome, and the command reports a non-zero exit code. No mutation
site formats its own warning or decides its own severity. A partial uninstall —
launcher symlink removed, state tree left behind — therefore cannot report
success.

The completion telemetry drain is the one deliberate swallow that stays: it
must not change the run's exit code and must not reach the run's stdout or
stderr. It is not silent. Every abandonment path — the worker still alive after
the join timeout, an interrupted join, and the worker's own failure — emits a
`RuntimeDiagnostics` warning, which is the sanctioned channel under
`docs/observability-policy.md` for a degradation that must stay off the run's
output surfaces.

`skillbill.application.runtime.RuntimeSingleton` scopes services and adapters that hold a cache, connection,
or lease across accessor reads (`DatabaseSessionFactory`,
`FeatureTaskRuntimeWorkerSupervisor`, `FeatureTaskRuntimeWorkerCoordinator`,
`DurableGoalPlanningAttemptRecorder`). Deliberately unscoped services:

- `GoalRunner` — per-access construction is intentional until subtask 2 makes
  `validationQualityRetries` durable across accessor reads.
- Stateless orchestration services (`WorkflowService`, `ReviewService`,
  `ParallelCodeReviewRunner`, and similar) — no cross-call mutable state.

Runtime database selection belongs to the bound `EnvironmentContext`. The
`DatabaseSessionFactory` resolves and retains one normalized path for its
component lifetime; application and port operations do not accept database
path overrides. CLI parsing selects `--db` before component creation, while
MCP and embedded callers bind their selected path in the same context. The
`EnvironmentContext.dbPathOverride` property is configuration at that
composition boundary, not operation or request plumbing.

## Workflow Git status inventory

The closed workflow-Git result vocabulary is owned by
`skillbill.ports.workflow.gitops.model`:

- `WorkflowGitOperationResult` is the sealed `Ok`/`Failed` result. Its
  non-null `value` and `error` payloads default to empty strings. The result
  cases own the canonical `wireValue` tokens `ok` and `error`, and
  `WorkflowGitOperationResult.fromWire` is the only decoder for that result.
- `WorkflowGitOperationStatus` owns the same `ok` and `error` tokens for
  structured Git DTOs and is the only decoder for those DTO status fields.
  `WorkflowScopedPathContentsResult.status`,
  `WorkflowSelectedDiffHunksResult.status`, and
  `WorkflowWorktreeActivityResult.status` use this enum; none is an open
  provider vocabulary.
- `GoalSubtaskReviewBaselineResult.status` and
  `GoalSubtaskReviewInputResult.status` remain the legacy Git review
  operation envelope boundary. Their `ok`/`error` tokens are consumed only
  by the review adapter and application recovery seams; they are not
  `WorkflowGitOperationResult` values and do not authorize raw status access
  on that sealed result.

`recordsNothingToCommit` is a pure result extension that searches both
  payloads. The Git adapter may normalize a recognized no-change failure to an
  empty `Ok`, while goal finalization separately accepts a marker-bearing
  `Failed`; both paths are intentional and preserve their existing payload
  semantics.

# Wire vocabulary

Runtime-domain wire-token declarations own closed enum tokens and their aliases. Runtime-contracts
`*Keys` declarations own durable and wire payload keys that two or more production modules read;
`SharedPayloadKeys` is the shared owner for the feature-task phase settlement keys;
`DecompositionManifestPayloadKeys` and `DecompositionPlanningPayloadKeys` own decomposition-manifest
and planning-projection keys; `LifecycleTelemetryPayloadKeys` owns the telemetry envelope, including
`event_name`. A `*Keys` object with a single owner lives in that owner's module instead — for
example `SqliteReviewTelemetryPayloadKeys` and
`SqliteLifecycleTelemetryMaterializationPayloadKeys` in `runtime-infra/sqlite`,
`GoalRunnerPurgePayloadKeys` and `GoalRunnerResetPayloadKeys` in `runtime-cli`,
`GovernedReviewEvidencePayloadKeys` in `runtime-infra/launcher` — and it must not restate a value a
shared owner already declares. `WireVocabularyArchitectureTest` asserts that zero-overlap for the
two SQLite adapter key objects (SKILL-374).
`DecompositionStatus` retains its separate `completed` input alias and `complete` output token.

## Governed payload seams (mechanical scope)

`WireVocabularyGovernedSeamInventory` is the independent expected-key authority. It reads canonical
schema YAML for decomposition manifests and the decomposition bundle journal (not a scan of existing `*Keys` objects). It also declares the closed
goal-continuation artifact vocabulary independently from its Kotlin owner. For each seam it
compares closed schema fields to declared `*Keys` / `*PayloadKeys` constants and fails when a
schema field has no Kotlin owner.

Literal payload-key enforcement runs only on production sources whose paths match the seam markers
(documented in `WireVocabularyGovernedSeamInventory.seams`). Outside those markers, telemetry,
CLI presentation, SQL column labels, and prompt prose may still carry string literals even when
they spell the same token.

| Seam | Schema authority | Open extension (not key-owned) |
| --- | --- | --- |
| Decomposition manifest | Root, subtask, dependency, stack branch, and current-intent closed objects | N/A at manifest root (`additionalProperties: false`) |
| Decomposition manifest bundle journal | Bundle-journal root and entry closed objects | None |
| Feature-task runtime goal-continuation artifact | `FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys` | None |

A green `WireVocabularyArchitectureTest` on runtime main sources does not prove every `String` in
the runtime is typed, that handoff-envelope projection bodies are fully keyed, or that unrelated
payload families (telemetry, review, install) have been migrated. The inventory's governed markers
cover the encode/decode pair for each listed seam; the architecture test includes a fixture that
fails when a governed seam accesses a literal key instead of its owner.

`WireVocabularyArchitectureTest` discovers runtime main sources through `RuntimeModuleCatalog`,
indexes declarations with source locations, rejects same-owner duplicates and local vocabulary
restatements, and reports the measured baseline-to-final delta. Identical spellings in different
enums remain separate when their decoding context differs. A collection literal restates a token
only inside that owner's decoding context.

# Native-agent installation integrity

Native-agent rendering promotes artifacts atomically into the installed cache and records each
Skill Bill-managed link in the user-home `.skill-bill/native-agent-link-inventory.json`. The inventory stores
the logical worker name, provider, installed path, cache target, and content digest. Reconciliation
uses the complete prior inventory to remove obsolete or dangling managed links; it never deletes a
regular file or a symlink that no longer resolves to its recorded managed target. Install verifies
the linked artifact's logical name, digest, target, and readability before committing the inventory.

# Delegated code-review architecture

**One authoritative preparation.** `ParallelCodeReviewRunner` resolves scope, diff, dominant stack,
rubrics, and project rules once for the whole review, then hands every top-level lane the same
immutable parent packet. A lane never re-resolves a fact the parent already established, and the
review runs one scope-discovery command regardless of how many specialists it launches.

**Flattened manifest layering.** `ReviewLaunchPlanPolicy.flatten` walks a routed pack's declared
composition and emits one direct specialist lane per selected area, with the nearest owning layer
winning and the full origin-layer chain retained for attribution. A composed root such as `kmp`
therefore expands straight to its own specialists plus the required baseline specialists; the
baseline review skill is never launched as a nested orchestrator.

**Forbidden child rediscovery.** `ReviewOperationPolicy` classifies every operation a specialist
requests without consulting platform, pack, or provider identity. Repository status, scope and
base/head discovery, diff recomputation, build and test invocation, pack and add-on resolution,
routing, learnings resolution, telemetry ownership, project-guidance traversal, and opaque searches
are refused because the parent packet already carries those facts. Project guidance reaches a
specialist only as packet-attested matched rule references, never as a file body.

**Bounded evidence and expansion ledger.** `ReviewEvidenceBroker` is the single measured surface a
specialist may act through. Assigned paths are served in bounded batches; anything outside the
assignment needs an authorized expansion whose record belongs to the parent packet's expansion
ledger and whose assignment digest must match the requesting lane. Once a lane produces a terminal
outcome the broker keeps returning that outcome instead of serving more context.

**Native-agent preflight.** When delegated execution selects provider-native specialists, every
`(agent, logical worker)` assignment is verified against the managed native-agent link inventory
before any worker starts. A missing, stale, or dangling link fails the whole review with
`MissingInstalledNativeAgentError` and its governed repair command; there is no generic-worker
fallback.

**Independent parallel lanes.** The two top-level lanes share the parent packet and nothing else.
Each holds its own assignments, evidence brokers, budgets, and accounting nodes, so one lane's
budget termination, timeout, or process failure never disturbs its sibling. Accounting folds each
session exactly once: direct usage sums owned sessions, an inclusive provider report already
containing its descendants is never added to them again, and counters aggregate the same way.

# Closed status-family inventory

Closed workflow and decomposition decisions use the domain-owned `DecompositionStatus`,
`WorkflowStatus`, and `WorkflowStepStatus` vocabularies. Their `wireValue` members are the only
declarations of the supported tokens, and `fromWire` is used at durable-map seams. The following
fields are either typed here or remain open because their values are supplied by a provider, a pack,
or a versioned durable payload whose vocabulary is intentionally owned by that boundary:

- `skillbill.goalrunner.model.GoalRunnerLivenessSnapshot.processState`,
  `skillbill.ports.agentrun.model.AgentRunLivenessSnapshot.processState`, and
  `skillbill.goalrunner.model.GoalRunnerSupervisionEvent.continuationMode`/`processState` use the
  domain-owned `GoalRunnerProcessState` and `GoalRunnerContinuationMode` enums. Their artifact
  writers emit `wireValue`, and the supervision projection uses the explicit `UNKNOWN` process
  state when no liveness snapshot is available.
- `skillbill.goalrunner.model.GoalRunnerLivenessSnapshot.livenessState` and
  `skillbill.ports.agentrun.model.AgentRunLivenessSnapshot.livenessState` use
  `GoalRunnerLivenessState`, as does
  `skillbill.goalrunner.model.GoalRunnerLivenessDecision.state`;
  `skillbill.goalrunner.model.GoalPlanningStatusSnapshot.state` and
  `skillbill.ports.idestatus.model.IdeStatusPlanning.state` use `GoalPlanningStatusState`;
  `skillbill.goalrunner.model.GoalRunnerStatusProjection.executionLiveness` and
  `skillbill.goalrunner.model.GoalRunnerStatusProjectionRuntimeInputs.executionLiveness` use
  `ExecutionLiveness`; `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedgerEntry.status`
  uses `FeatureTaskRuntimeRepairLedgerStatus`; and
  `skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership.leaseState` uses
  `FeatureTaskRuntimeWorkerLeaseState`. Each owning enum provides the sole `wireValue`/`fromWire`
  mapping for its fields.

- `skillbill.workflow.decomposition.model.DecompositionManifest.status` and
  `DecompositionSubtask.status`: legacy manifest state accepts unknown future values and keeps the
  raw wire value for forward-compatible read and rewrite.
- `skillbill.workflow.engine.model.WorkflowStepState.status`, `WorkflowStateSnapshot.workflowStatus`,
  and `Workflow*View.workflowStatus`: workflow definitions are pack-owned and may add statuses;
  typed branches use the shared vocabulary where the runtime makes a closed decision.
- `skillbill.ports.workflow.model.GoalChildWorkflowDeletionScope.deletableStatuses` uses
  `WorkflowStatus`; SQL and store seams bind `wireValue` at persistence time.
- `skillbill.ports.workflow.model.WorkflowStateRecord.workflowStatus`: this is the persisted port
  record crossing the SQLite and workflow-engine compatibility seam, so it preserves unknown
  definition values; consumers convert it with `workflowStatus()` before making closed decisions.
- `skillbill.ports.featuretask.model.FeatureTaskRuntimeCrashReconciliationCandidate.workflowStatus`
  and `skillbill.application.decomposition.model.DecompositionManifestRuntimeUpdate.workflowStatus`
  are read from SQLite worker/decomposition update rows and preserve the workflow-definition token
  while crossing worker and decomposition update ports; their consumers convert it with
  `workflowStatus()` before closed dispatch.
- `skillbill.goalrunner.model.GoalRunnerObservabilityProgressInput.workflowStatus` preserves the
  caller-supplied workflow-definition token while `WorkflowServiceInputMapping` projects
  observability from durable artifacts; it is not a process or goal status owned by the
  observability model.
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord.status` is owned by
  `WorkflowStepStatus`, and `FeatureTaskRuntimeGoalContinuationOutcome.status` is owned by
  `GoalRunnerTerminalStatus`; both durable decoders reject unknown values at their artifact seams.
- `skillbill.engine.featuretask.model.FeatureTaskRuntimePhaseStatus.status` remains a
  presentation string; `FeatureTaskRuntimeStatusService` decodes it with `WorkflowStepStatus`
  before count and phase-selection decisions and emits the enum's `wireValue`.
- `skillbill.engine.featuretask.model.FeatureTaskRuntimeSubtaskOutcome.status` is owned by
  `GoalRunnerTerminalStatus`; `FeatureTaskRuntimeRunnerLaunchOutcomes` uses exhaustive typed
  dispatch and the CLI presentation emits its `wireValue`.
- `skillbill.telemetry.model.SyncResult.status` is owned by `TelemetrySyncStatus`; the remaining
  `FeatureTaskRuntimeFinishedRecord.completionStatus`, `QualityCheckFinishedRecord.result`,
  `FeatureVerifyFinishedRecord.auditResult`/`completionStatus`,
  `GoalStartedRecord.status`, `GoalSubtaskFinishedRecord.status`, `GoalFinishedRecord.status`,
  `GoalIssueFinishedRecord.status`, and telemetry mode fields are provider and telemetry labels
  owned by their emitting contracts.
- `skillbill.goalrunner.model.GoalRunnerProgressEvent.kind`,
  `skillbill.goalrunner.model.GoalRunnerLivenessSnapshot.phase`,
  `skillbill.goalrunner.model.GoalRunnerSupervisionEvent.phase`,
  `skillbill.review.context.model.ReviewContextPacket.status`, and
  `skillbill.review.context.model.ReviewBuildTestFact.kind`/`outcome` are exact open labels owned by
  their emitting contracts. `skillbill.ports.scaffold.model.ScaffoldBaselineLayer.mode` is the
  presentation of a pack-owned baseline mode and remains open at that port boundary.
  `ImportedReview.executionMode` and `ReviewSummary.executionMode` are
  owned by `ReviewExecutionMode`; the decoder preserves nullable absence and emits `wireValue`.
- `skillbill.review.model.ReviewRunLane.resolutionState` is owned by
  `ReviewLaneResolutionState`, and `ReviewRunLane.reviewDisposition` is owned by
  `ReviewLaneReviewDisposition`; SQLite legacy null or unknown values fail closed to unresolved
  and incomplete before application dispatch.
- `skillbill.ports.review.model.ReviewScopeFacts.status`,
  `skillbill.review.model.GoalRunSummary.status` preserve review-store and provider status labels;
  the review scope and stats boundaries own those vocabularies and do not make closed decisions
  from the raw values.
- `skillbill.ports.scaffold.model.ScaffoldSkillStatus.completionStatus` is owned by
  `ScaffoldCompletionStatus`, and `ScaffoldSectionStatus.status` is owned by
  `ScaffoldSectionCompletionStatus`; both use their enum `wireValue`/`fromWire` pair at the
  authoring adapter boundary. `ScaffoldValidateResult.mode` and `.status` use
  `ScaffoldValidationMode` and `ScaffoldValidationStatus`, respectively, with decoding at the
  authoring adapter boundary. `skillbill.scaffold.model.ScaffoldModels.mode`/`kind` and
  `skillbill.ports.agentrun.model.AgentRunLauncherModels.phase` remain open because their values
  are extension-owned labels. `GoalPlanningBoundaryHeading.kind` is owned by
  `GoalPlanningBoundaryHeadingKind`; `GoalPlanningContext` has no `kind` field.
- `skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement.kind` uses the sealed
  `FeatureTaskPhaseSettlementKind`. Service-produced values use its three canonical cases, while
  durable reads retain an unknown wire token in `Unknown` for forward-compatible rewrite.
- `skillbill.learnings.model.LearningRecord.status` and `LearningEntry.status` remain external
  learning labels whose contracts validate presence and shape; the producer owns the
  vocabulary.
- `skillbill.workflow.taskruntime.model.SettlementEnvelopeRequest.status` uses
  `SettlementStatus`; prose settlement accepts only the completed, blocked, and failed members.
- `skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause.pauseKind` uses
  `FeatureTaskRuntimeAuditGapPauseKind` at its durable artifact boundary.
- `skillbill.review.context.model.ReviewAccountingInput.terminalOutcome` and
  `ReviewAccountingNode.terminalOutcome` use `ReviewAccountingTerminalOutcome`; integration
  accounting continues to use `ReviewIntegrationTerminalOutcome`.
- `skillbill.workflow.model.goalreview.GoalSubtaskCommitFocusedAccounting.integrationTerminalOutcome`
  uses `ReviewIntegrationTerminalOutcome`; durable artifact decoding uses `fromWire` and emission
  uses `wireValue`, preserving the existing integration tokens, unknown-value rejection, and
  skipped-pass reason rule.
- `skillbill.review.context.model.ReviewBudgetOutcome.budgetKind` uses `ReviewBudgetKind`; all
  budget dimensions are decoded once at the review budget seam and emitted through `wireValue`.
