# Subtask 1: durable block incidents and automatic investigation scheduling

## Scope

Establish proposed BlockIncidentService.observeBlock/inspectIncident/reconcilePending, domain lifecycle types, BlockIncidentRepository and narrow worker/spool ports under an incident area such as engine/diagnostics/block. Keep immutable original occurrences separate from mutable diagnosis, recovery and report state. Provide a usable deterministic pending/degraded incident lifecycle in this commit; the rich investigator follows in subtask 2.

## Acceptance Criteria

1. The incident model and validated persistence preserve an immutable original failure and actual available correlation identities separately from mutable diagnosis, recovery and report state.
2. Every accepted new blocked goal or phase transition and causal entry-boundary failure records or links an incident before investigation, including planning, preparation, child execution, parent finalization and standalone early outcomes, without classifying normal lifecycle states as blocks.
3. Occurrence generation and causal aliases coalesce replayed settlement/run-loop and propagated child/parent observations, while later same-error blocked attempts have distinct identities and cannot overwrite a live run.
4. Durable investigation intents, fenced worker claims and bounded reconciliation support originating-process exit and restart without altering workflow state or recursively diagnosing secondary worker failures.
5. Database/evidence/worker failures preserve the original error and exit semantics and expose bounded durable fallback or an honest unsaved warning when all persistence fails.

## Lifecycle and ownership requirements

Store reason, failure code/disposition, primary throwable classification, original exit behavior, timestamps, workflow/parent-child/phase identity, actual phase-run/invocation/execution identities, attempt, accepted-attempt/block generation, repair-turn/output generation, evidence references and availability. Nullable coordinates have explicit availability; goal-only incidents never invent child IDs. Store revisions, deadlines, retry counters, owner token/generation and next eligible time. Unique occurrence aliases link settlement and run-loop evidence through accepted attempt generation. Workflow+phase+attempt is insufficient because operator retries may reuse coordinates. Message text and issue key are never causal keys. Separate refused invocations may get their own admission incident but must not mutate the currently owned goal.

Extend UnitOfWork and DatabaseSessionFactory's connection-bound transaction/fenced APIs. The accepted block and intent share the owning transaction when they share the DB. After commit, scheduling failure cannot roll back or replay that block. SQLiteRepositories/SQLiteUnitOfWork implements persistence only. Append migrations after the then-current tail, observed as version 65 `record-standalone-plan-report`; confirm the next free number during implement because SKILL-429 may advance it. Add indexes, uniqueness and desktop goals/phases invalidation triggers in the new migration. Never alter historical migration 52 to simulate upgraded triggers. Keep DesktopReadSchema version/hash and desktop compatibility consistent if their exposed schema changes. Prefer runtime detail projections over a new Room incident store.

Create canonical Draft 2020-12 YAML incident schema version 0.1, Kotlin version pin, typed RuntimeFailureCode family and SkillBillRuntimeException parse failures. Validate producers, stored reads, agent results and fallback imports. Quarantine malformed incident data with visible degradation while retaining the blocked workflow. Use owning Keys and enum wireValue, shared keys only for cross-module ownership; preserve existing SharedPayloadKeys and lifecycle vocabulary. Do not replace installed phase settlement contracts with this new feature contract.

## Required trigger coverage

| Actual owner from digest | Required integration |
| --- | --- |
| FeatureTaskRuntimePhaseStateRecorder.recordPhaseState/recordOwnedPhaseState and serialPhasePatch | Enqueue at accepted transactional blocked transitions, preserving worker admission and SKILL-429 carryover/coordinator fields. Include serial ledger and required-write rejection paths. |
| FeatureTaskPhaseSettlementService.block and settlement repository | Accepted observation may precede loop consumption; identical replay links existing occurrence, conflicting/refused submissions enqueue nothing. SQLite settlement adapter remains admission/storage only and excludes implement. |
| RunLoopPhaseBlocking.blockAndPersist variants, PreLaunch, PhaseAttemptOnce, PhaseOutputGate, PhaseAttemptContinuations | Observe the owning block transition for failed starts/briefings, launch errors, producer rejection and exhausted repairs. A printed event after rejected persistence is not an accepted durable block. |
| GoalRunner.run, GoalPlanningSource, GoalRunnerRunPreparation, GoalLoop, SelectedSubtaskLoop, WorkerRequestHandling, Finalization | Capture planning/preparation/dependency/policy/child/finalization blocks and failures before runPrepared; distinguish typed Stopped reasons. Nonempty goalBlockedReason alone is not proof. |
| WorkflowGoalRunnerBlockWrites, OutcomeTerminalPersistence, OutcomeStore and OutcomeReconcile | Preserve block evidence, explicitly link child propagation and retain existing resume/standing-cause behavior. CrashReconcileExpiredWorkerToResumable returns RECONCILABLE, not a new block. |
| FeatureTaskRuntimeRunner entry and Execute | One narrow entry failure observer covers pre-execution and post-loop exceptions as well as inner failures; durable causal aliases deduplicate nested observations. Preserve interruption/cancellation. |
| PhaseRunEntry.run, withStatus, runForGoal, runStandaloneMonitor and StandalonePlanRun | Cover early monitor refusal, exceptions and parent finalization outside standalone sink. Preserve typed pause before resultOf/StandalonePlanRun's current Blocked mapping; do not diagnose a pause. |

Exclude waiting CI, paused, completed, skipped, canceled/normal interrupted shutdown, nonblocking QA receipts/warnings and successful/no-target monitor. Retain exact monitor selector/PR/worktree identity. PhaseCommand's presentation catches cannot erase primary errors already captured by engine.

## Worker and fallback policy

Persist intent first, then use installed-runtime detached launching through WorkRunLauncher/DetachedWorkRunLauncher and RuntimeComponent providers beside RuntimeWorkControlProvides. This launcher is not a queue or guaranteed supervisor. Worker startup and supported runtime startup/reconnect reconcile pending/expired claims. No daemon thread in the exiting process, desktop polling worker or desktop-open dependency. Implement host-specific detach support and verify survival on each supported host; unsupported hosts retain pending/degraded work and explain the unavailable guarantee. A process-scope return alone never proves survival.

Named default policy: one diagnostic agent turn, 120 seconds wall time, 64 KiB aggregate selected UTF-8 text, 8 KiB per source, 16 KiB structured response, at most two worker attempts within a five-minute incident deadline. Claims renew every 10 seconds with a 30-second lease; expiry plus confirmed-dead ownership is required before reclaim. Uninspectable ownership remains pending/degraded. Shutdown cancellation has a five-second cleanup budget. Persist pending, investigating, complete, partial, exhausted, timed_out, canceled, unavailable_agent, persist_failed and crashed outcomes distinctly. Cancellation affects diagnosis only. Every fallback/degradation emits bounded RuntimeDiagnostics records; secondary diagnosis/recovery/report errors never become new primary incidents.

DB-unavailable fallback uses an atomically published, restrictive-permission record in runtime user state outside the project, with only primary failure and known correlation metadata, no database/environment dump. Proposed defaults: 16 KiB per record, 1 MiB total spool, at most 64 records and two publication attempts. Refuse new records when full and record refusal through independent diagnostics rather than silently evicting evidence. Import through validated deduplicating occurrence identity after DB recovery. Mark database evidence and recovery unavailable. If spool fails too, preserve original throw/exit and print an explicit unsaved warning; do not promise later completion.

Bootstrap only queued/new incidents and explicitly identified active block generations using one durable marker. No indiscriminate historical reporting sweep. Retain all older run history and loud store compatibility handling.

## Test Obligations

- T1.1 catches missing/double admission. Extend FeatureTaskPhaseSettlementServiceTest, RequiredPhasePersistenceTest and blocked outcome-store regressions to prove accepted settlement plus loop replay and child propagation produce one incident; a later same-error generation produces another; refused/conflicting writes produce none. Cover serial and implement paths, preserving retained bytes and original failure when persistence is rejected.
- T1.2 catches false block classification or missed outer boundaries. Table-driven typed-boundary cases cover planning/preparation, child/finalization, early monitor refusal, standalone settlement and pre/post-execute exceptions, plus paused/waiting/completed/skipped/canceled/QA negatives. Assert persisted identities/outcomes, not helper call order.
- T1.3 catches lost/duplicated jobs and destructive upgrades. Real SQLite upgrade from the current predecessor preserves existing runs, new triggers publish only committed changes, uniqueness survives concurrent observers, parent exit leaves a runnable worker, restart reclaims only expired confirmed-dead claims, cancellation stops secondary work only, malformed records quarantine locally. Use fake clock and process inspection where possible; host survival needs a real subprocess boundary at validate.
- T1.4 catches overwritten primary errors or unbounded fallback. Inject spawn/crash/timeout/missing-agent, transaction/read failure and spool-full/unwritable conditions; prove unchanged original exception/exit/evidence, visible partial states, strict byte/time/attempt bounds, no recursive incident and one-time validated fallback import. Extend real SQLite BlockedWorkerCrashRecoveryTest patterns for live/unexpired/unconfirmed owners and unrelated runs.

## Non-Goals

No rich recovery, GitHub submission or presentation implementation yet. This commit supplies a complete incident scheduling lifecycle with deterministic degraded output, not unused schema fields. No generic scheduler, SQL classifier, fake identity or mutation of the blocked state during investigation.

## Dependency notes

No prior subtask. Carry the SKILL-429 admission and carryover changes forward. New contracts and migrations land with their consumers here. Later subtask 2 replaces the limited worker diagnosis through the narrow capability seam without changing occurrence authority.

## Implementation Details

Execute these tasks in order during later implementation. All paths and existing symbols come from the upstream digest. Proposed names describe APIs to add, not capabilities already present. Tests listed here are obligations to author alongside the relevant behavior and execute in validate, not commands authorized in this planning phase. This plan adds no acceptance criteria and does not change the scope or dependencies above.

### 1. Define occurrence authority, lifecycle contracts and narrow capabilities

Serves acceptance criteria 1, 3, 4 and 5.

- Add incident-domain immutable request/value types in `runtime-domain` and an area under `engine/diagnostics/block` for proposed `BlockIncidentService.observeBlock`, `inspectIncident` and `reconcilePending`. Separate immutable original occurrence, causal aliases and evidence availability from mutable worker/diagnosis, recovery and report state. Include every identity and original-failure field required above, with explicit absence rather than substitute IDs.
- Define accepted occurrence generation at the owning transition. Persist a monotonic block generation or accepted-attempt identity that survives retry reuse of phase coordinates. Accepted settlement and subsequent loop consumption must resolve the same generation; a propagated parent uses an explicit child causal reference. Preserve an occurrence token across DB fallback and import. Do not infer causality from reason text, issue key or timestamps.
- Add proposed `BlockIncidentRepository` and typed worker/spool ports in `runtime-ports`. Repository operations express create-or-link, claim/renew/settle and bounded pending reads; future recovery/report operations retain their separate ownership. Do not expose a mutable session or generic runner. Make observation origin explicit so secondary worker failures cannot enter primary admission.
- Add `orchestration/contracts/block-incident-schema.yaml` as Draft 2020-12 version 0.1 with bounded discriminated lifecycle sections, version pins in `runtime-contracts`, owning wire-key constants and typed failure codes. Use `SkillBillRuntimeException` at producer, stored-read, future agent-result and fallback-import parse seams. Invalid data quarantines only its incident and leaves the primary workflow intact.
- Implement deterministic pending/degraded diagnosis output now. Rich evidence interpretation, recovery execution and report delivery stay with dependent subtasks; persisted state must have real producers/readers rather than speculative unused fields.

Test obligations: T1.1 must distinguish alias replay from a new generation even when phase coordinates and error text match. T1.3 must reject malformed/version-incompatible incident payloads without losing the original block. Add canonical schema/version parity and acceptance/rejection fixtures because these enforce a runtime contract, not incidental object layout. Reuse shared payload vocabulary rather than duplicating it.

### 2. Add connection-bound storage and safe upgrade behavior

Serves acceptance criteria 1, 3, 4 and 5.

- Extend `ports/persistence/UnitOfWork.kt`, the existing `DatabaseSessionFactory` transaction APIs and `runtime-infra/sqlite/.../SQLiteRepositories.kt` with the narrow incident repository. Keep policy in the engine. SQL implements immutable occurrence/alias uniqueness, revisions, owner generations, deadlines, retry eligibility and atomic intent storage.
- Append a migration in `DatabaseMigrationEntries.kt` after the actual implementation-time tail. The digest observed version 65; that number is evidence of the prior tail, not a reservation. Add occurrence, alias and worker-state tables/indexes and applicable goals/phases revision triggers in this migration. Do not edit historical migration 52. Preserve existing run, settlement, standalone-status and SKILL-429 data.
- Use existing IMMEDIATE transaction and nested UnitOfWork reuse so accepted block writes and incident intent commit together wherever they share the DB. Keep an entry-boundary failure outside an accepted block transition distinguishable from a successful durable transition. A post-commit launch failure changes secondary state only.
- Extend `core/schema/DesktopChangeRevisions.kt` through its current mechanism. Update `DesktopReadSchema` compatibility version/hash and affected read-only desktop schema definitions only if the exposed compatibility surface changes. Prefer runtime-owned detail reads and avoid a second writable incident store.
- Implement validated quarantine and bounded pending/expired queries. Bootstrap only queued/new occurrences and explicitly identified active generations under one durable marker. Do not scan historical failures into a reporting backlog.

Test obligations: T1.3 uses real SQLite upgrade from the implementation-time predecessor and concurrent observers to catch destructive migration, duplicate occurrence admission and missing upgraded triggers. Assert committed goals/phases invalidation, no rollback publication, retained old records and local quarantine. Extend `DatabaseMigrationsTest.kt` and owning SQLite incident tests. Preserve settlement and standalone-status regression coverage.

### 3. Integrate accepted child-phase and settlement transitions

Serves acceptance criteria 1, 2 and 3.

- Integrate engine-owned observation into `FeatureTaskRuntimePhaseStateRecorder.recordPhaseState`, `recordOwnedPhaseState` and the serial blocked-ledger path in `serialPhasePatch`. Use the accepted writer's UnitOfWork and generation authority. Preserve admission checks and all `FeatureTaskRuntimePhaseStateRequest` fields, including rejected output, repair evidence, failure disposition, launch facts, coordinator admission and revision-fenced implementation carryover.
- Integrate `FeatureTaskPhaseSettlementService.block` so Accepted creates or links an observation and IdenticalReplay resolves the same occurrence. Conflicting and Refused produce no incident. Keep `SqliteFeatureTaskPhaseSettlementStore` limited to its current storage/admission responsibility; implement remains outside that settlement table.
- Route `FeatureTaskRuntimeRunLoopPhaseBlocking.blockAndPersist` variants and branch-setup blocking through the coupled transition owner. Cover failed start/briefing persistence, launch failures, rejected producer output and exhausted gate/repair paths reached from PreLaunch, PhaseAttemptOnce, PhaseOutputGate and PhaseAttemptContinuations. Remove duplicate enqueue decisions introduced by integration instead of scattering independent policy across callers.
- Distinguish rejected required persistence from an accepted phase block. Preserve its primary error through the entry-failure path in task 4, with available evidence and explicit durability status. Printing a blocked event cannot authorize an accepted-block incident.

Test obligations: extend T1.1 at `FeatureTaskPhaseSettlementServiceTest.kt`, `RequiredPhasePersistenceTest.kt` and blocked outcome-store tests. Catch duplicate accepted-settlement/loop incidents, falsely admitted conflicting/refused writes and missing serial/implement incidents. Assert original bytes, attributed child attempt, failure disposition and absence of agent/gate/Git effects after rejected required writes. Do not replace these boundary assertions with helper call verification.

### 4. Cover goal, standalone and outer failure boundaries without false blocks

Serves acceptance criteria 1, 2, 3 and 5.

- Integrate `GoalRunner.run`, `GoalPlanningSource.prepare`, `GoalRunnerRunPreparation`, `GoalRunnerGoalLoop`, `GoalRunnerSelectedSubtaskLoop`, `GoalRunnerWorkerRequestHandling` and `GoalRunnerFinalization` through typed stop/failure outcomes. Capture preparation and planning failures before `runPrepared` and parent finalization failures with actual available coordinates. Keep goal-only incidents valid without fake child phase-run IDs.
- Extend `WorkflowGoalRunnerBlockWrites`, `WorkflowGoalRunnerOutcomeTerminalPersistence`, `WorkflowGoalRunnerOutcomeStore` and `WorkflowGoalRunnerOutcomeReconcile` at accepted durable block seams. Link propagated parent observations to the child's explicit causal identity. Preserve standing cause, retry artifacts and resume behavior. `crashReconcileExpiredWorkerToResumable` remains RECONCILABLE rather than generating another block.
- Add one narrow failure observer at `FeatureTaskRuntimeRunner.run` covering preparation, execution and finalization exceptions, deduplicating inner `FeatureTaskRuntimeRunnerExecute` observations by causal identity. Preserve the original throwable, code, interruption and exit behavior even if observation fails.
- Extend `PhaseRunEntry.run`, `withStatus`, `runForGoal` and `runStandaloneMonitor`, plus `StandalonePlanRun`, to retain the underlying typed paused/refused/blocked outcome before lossy result mapping. Include early monitor returns outside the standalone sink and exceptions before CLI presentation catches. Preserve exact PR, selector, worktree and invocation/execution identity from standalone registration.
- Classify a refused invocation as its own admission incident when it represents an actual blocked request, including early monitor ownership refusal. Record no mutation against the separately live run. Normal pause, waiting, complete, skipped, cancellation/normal interruption, QA warnings and no-target successful monitor remain excluded. Do not use `goalBlockedReason` or `PhaseRunResult.Blocked` alone as admission proof.

Test obligations: T1.2 uses a compact table of distinct boundary branches and negative lifecycle outcomes. Existing goal coordinator, phase plan/review/validation/monitor and concurrent monitor fixtures provide the seams. Assert original failure/exit and actual identity, one incident for nested/propagated failures, no live-state overwrite, and no incident for normal pause despite legacy Blocked mapping. T1.1 covers new same-error generations. Avoid sibling tests that only vary literals.

### 5. Implement durable detached dispatch, leases and bounded reconciliation

Serves acceptance criteria 4 and 5, retaining criteria 1 and 3 invariants.

- After committed intent, launch an installed-runtime incident worker through `WorkRunLauncher` and `DetachedWorkRunLauncher`. Compose narrow providers only under `skillbill.di.core.RuntimeComponent`, beside `di/core/RuntimeWorkControlProvides.kt`. Add an internal typed dispatch path for the worker; no separate workflow, daemon thread, desktop process launcher or generic scheduler.
- The worker reopens the selected DB, claims by incident revision/token/generation and executes the deterministic lifecycle through a restricted worker capability. Implement named budgets exactly as recorded in Worker and fallback policy, including one-turn limits for the later investigator. Bound retained output and structured payload size as well as work duration. No arbitrary tool execution or model-generated action is introduced here.
- Renew leases and settle through fenced compare-and-set operations. Reconcile pending and expired claims on worker start and supported runtime startup/reconnect, with bounded batches and persisted next-eligible time. Expiry without confirmed-dead ownership is insufficient for reclaim. Preserve uninspectable ownership as pending/degraded and never steal a live claim.
- Make cancellation diagnosis-only, with bounded cleanup and explicit terminal outcomes. Spawn refusal, crash, timeout, unavailable agent and persistence loss preserve the queued/original incident and record secondary state without recursively scheduling investigation.
- Decide host detach mechanics through the existing launcher during implement. The digest establishes Linux systemd user scope availability only where supported, not a universal survival guarantee. Use an available non-desktop detached process mechanism only when demonstrated to outlive the origin; otherwise retain pending/degraded state with a specific unsupported-host reason. Record supported-host guarantees from subprocess evidence rather than treating Started as completion.

Test obligations: T1.3 exercises a real originating-process exit, later claim and restart recovery at the supported-host subprocess boundary. Fake-clock/process-inspection cases cover competing workers, renewal, expired confirmed-dead reclaim and live/unexpired/unconfirmed refusal. T1.4 catches runaway work, repeated spawn, canceled primary workflow and recursive incidents using deadline/byte/attempt exhaustion and secondary failure injection. Verify bounded cleanup and preservation of unrelated runs. Rich read-only agent enforcement remains a subtask 2 obligation through the capability defined here.

### 6. Add bounded DB-unavailable fallback and deduplicating import

Serves acceptance criteria 1, 3 and 5.

- Implement the spool port in runtime infrastructure using runtime user state outside the project, restrictive permissions and atomic publication. Enforce the recorded 16 KiB record, 1 MiB aggregate, 64-record and two-publication-attempt limits. Refuse full-spool writes rather than deleting original evidence silently. Store only original failure and known correlation facts; mark database evidence and recovery unavailable.
- Resolve the exact state directory through existing runtime user-state conventions during implement. This is an explicit assumption because the digest names no directory API. Do not use a checkout directory or infer a user path from logs. If safe state storage cannot be established, take the unsaved-warning path.
- Import only validated records after DB recovery using the original occurrence token and aliases. Commit deduplicated storage before acknowledging/removing a spool record, so a crash during import cannot duplicate an incident or lose the only copy. Quarantine malformed fallback payloads with bounded diagnostics.
- Use independent bounded `RuntimeDiagnostics` when DB/spool operations fail. Retain the original exception and exit code; terminal fallback copy explicitly states when no diagnosis was saved. Report every refusal/degradation without secrets or fabricated DB evidence. Do not implement the later shared CLI/desktop diagnosis presentation in this task.

Test obligations: T1.4 injects unreadable DB, failed transaction, unwritable/oversized/full spool and failed independent persistence. Assert unchanged primary error, strict caps, no project writes, honest unavailable/unsaved states and no recursive incident. Crash/retry import proves one occurrence and retained evidence until commit. Secret-bearing arbitrary error text is bounded local evidence, never a public report payload.

### 7. Complete integration and handoff evidence

Serves all five acceptance criteria.

- Inspect the complete trigger matrix above against the implemented transition owners and remove redundant classification/enqueue logic introduced during this work. Preserve current SKILL-429 changes and unrelated checkout edits. New incident state must be usable through `inspectIncident` for deterministic pending/degraded reads, without waiting for UI or reporting implementation.
- Keep the source-backed assumptions visible for later confirmation: migration tail may advance, pause mappings must retain typed source outcomes, standalone IDs can lack workflow coordinates, existing launcher detachment requires host proof, and a generation must remain stable across accepted settlement/loop observations and fallback import. No missing deployment input blocks this storage/scheduling subtask; unavailable agent or unsupported detachment yields the defined degraded state.
- Prepare a concise changed-owner and criterion-to-obligation handoff for later audit/review/validate. Apply A1 through A12, G1 through G7 and P1 through P8 with the section-5 checklist, using existing architecture guards. Any necessary new guard proves an actual authority/ownership boundary with acceptance and rejection cases; add no exemption, baseline expansion, all-access strategy context or second composition root.
- Leave rich evidence collection, enforced evidence-only agent diagnosis and recovery policy/application to subtask 2; sanitizer, product GitHub identity/client, durable delivery and telemetry extensions to subtask 3; shared saved projections, terminal command surface and accessible desktop presentation to subtask 4. Preserve the narrow contracts and immutable history these depend on. Recovery/report failures are already excluded from recursive primary admission.

Test obligations: no extra test for this handoff or trivial composition glue. Preserve and extend existing architecture/parity enforcement only where a real boundary changes. Validate owns all execution of T1.1 through T1.4 and affected project gates in Validation Strategy; review owns architecture findings, and later runtime phases own history, commit and publication. This planning phase executes none of them.

## Next path

Runtime plans subtask 2 after this spec; later execution commits this outcome before dependent work. No phase continuation or launch command is executed here.

## Governing constraints

Planning workflow is wftr-20261021-055741-uosu. This bundle is planning-only until a later explicit implementation request. Use the active workflow checkout and branch. Preserve unrelated SKILL-429 coordinator-admission/carryover changes and the SKILL-434 bundle. File names below identify owners, not an exclusive repair allowlist. Required later review and validation repairs may touch production wiring, test setup, formatting and lint while preserving behavior and assertions.

The preplan digest is the sole repository evidence for this plan. Names marked proposed are new APIs, not installed-runtime capabilities. Implement must confirm mutable integration facts within its assigned authority. No implementation, test execution, build, public issue submission, commit, push or goal launch occurs during this planning phase. Runtime-owned review, history, publication and settlement remain outside implement acceptance criteria.

Apply architecture-guidelines A1 through A12, section 5, G1 through G7 and P1 through P8, Runtime Architecture Design Principles, code-principles, runtime-command-guidance and observability-policy. The digest supplies their application here: narrow capabilities and authority, one coupled transition owner, explicit resource cleanup, deleted duplication rather than moved duplication, no policy in adapters, no second composition root, append-only persistence preserving records, no new guard exemption/baseline, and observable boundary proof. Runtime-domain owns types/pure policy, runtime-contracts owns shared serialized vocabulary, runtime-ports owns consumer I/O contracts, runtime-engine/application own use cases, runtime-infra owns mechanics. Only skillbill.di.core.RuntimeComponent under runtime-core/src/main/kotlin/skillbill/di composes them. Never introduce an all-access strategy context, raw runner/workflow mutator or callback bag. Kotlin source follows the repository comment restrictions.

## Validation Strategy

Only validate executes tests and full project checks; only build supplies buildability proof. Plan, implement and audit inspect production end states without invoking proof commands. At validate, resolve affected gates through runtime/manifest routing and include runtime-kotlin `./gradlew check --no-build-cache` and `scripts/validate_agent_configs`. Desktop changes also require all affected module checks, Linux Xvfb where needed, `./gradlew clean check createDistributable --no-build-cache --no-configuration-cache` under desktop-tray and `packagedDataSmoke` as required by desktop CI. Keep actual evidence and failures in validate outputs, never claim future checks already passed. Review applies the section-5 checklist with rule IDs and the existing PrincipleEnforcementInventory, StrategyCapabilityBoundaryArchitectureTest, RuntimeCompositionGuardArchitectureTest, WireVocabularyArchitectureTest, CommentAndInterfaceKdocArchitectureTest, InlineFqnArchitectureTest and ProductionFileLineCeilingArchitectureTest. Any new mechanical guard needs acceptance and rejection fixtures proving a real boundary. Preserve existing regression assertions. Do not write layout-pinning or incidental-prose tests.
