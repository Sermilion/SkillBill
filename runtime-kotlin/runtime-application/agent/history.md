## [2026-10-01] SKILL-388 subtask 1 — Application boundary repairs and enforcement
Areas: runtime-application/{review,reviewevidence,workflow,decomposition,uninstall}, runtime-ports/diff, runtime-infra/{workflow,sqlite}, runtime-engine/featuretask, runtime-cli/system, runtime-contracts, runtime-core repoTest, docs/observability-policy.md
- Git facts now come through `DiffResolverPort` as fact-level reads (commit resolution, merge base, index entries, untracked paths, diff) with `ReviewDiffModels`; runtime-application no longer builds git argv. `FileSystemDiffResolver` owns process handling, separates stderr, distinguishes null from empty diff, and honors interrupts. reusable
- `ParallelCodeReviewRunnerPlanning` keeps `diffResolver` private and exposes internal fact forwarders that the Revisions, LaneMap and EvidenceCoordinates extensions call.
- Legacy goal-runner control migration (application and sqlite v40) decodes through typed policies, fails atomically on malformed identity or required fields, and chains the caught cause through error helpers returning Nothing.
- Enum `wireValue` replaces string literals at decomposition and workflow retry seams; `WireVocabularyGovernedSeamInventory` and `TypedParseBoundaryArchitectureTest` gained the goal-runner-controls seam and missing-selection cases.
- Uninstall confirmation text moved out of application models into the CLI command; prompt goes to live stdout, status to result stdout.
- Dead code deleted and module-local declarations narrowed to internal; test fakes rewritten to fact-level fakes.
- Trap: the engine shared-review resolver degrades only on IllegalArgumentException from diff-evidence parsing; a broad catch fails detekt.
- Trap: KDoc on non-interface types and `//` comments in architecture scan support fail CommentAndInterfaceKdocArchitectureTest.
Feature flag: N/A
Acceptance criteria: 9/9 implemented (validate phase passed gradle check; agnix not run headless)

## [2026-09-23] SKILL-370 subtask 2 — Ownership moves and namespace hygiene
Areas: runtime-application/{review,workflow,decomposition,telemetry,agentrun,idestatus}, runtime-engine, runtime-domain/workflow/decomposition
- Dependency direction flipped: `runtime-engine` now declares `implementation(project(":runtime-application"))` and runtime-application declares no engine edge in any configuration. `RuntimeModuleCatalog` and `ARCHITECTURE.md` record the same edge.
- Engine-only helpers moved out of runtime-application: `WorktreeEditJournalWriter` to `skillbill.engine.worktreeedit`, `topLevelJsonObjectCandidates` and `stderrExcerpt` to `skillbill.engine.agentoutput` — outside `engine/goalrunner` and outside `featuretask/lifecycle/core`.
- Manifest transitions moved down to runtime-domain: `intentFor`, `withParentStatus`, `withBlockedSubtask`, `withRetriedSubtask` in `skillbill.workflow.decomposition`, `normalizedBlockedReason` in `skillbill.workflow.decomposition.runtime`, both free of `java.nio` and `skillbill.ports` imports. `DecompositionManifestTransitionsTest` pins the parent-status rollup and the block/retry transitions in their new module; before the move they were covered only through callers.
- Every `typealias` left runtime-application main; consumers import the owning declaration (`skillbill.ports.workflow.model.WorkflowFamily`, `skillbill.goalrunner.model.GoalObservability*`) directly, and `WorkflowFamilyKind.workflowFamily()` replaces the alias-mediated mapping.
- Stutter packages collapsed: `review/parallel/core/code/review/{evidence,inline,pass,runner}` became `review/parallel/runner`, `review/parallel/core/review` tests became `review/parallel/planning`, and `review/review` became `review/snapshot`. Test directories and package declarations moved together, so test sources stay co-located with their packages.
- `GoalLifecycleTelemetryEmitter` no longer carries a `NONE` member; engine tests take `noopGoalLifecycleTelemetryEmitter` from runtime-application testFixtures instead of duplicating a double.
- Orphan test packages moved under main packages: `workflow/workflow` → `workflow/persist`, `telemetry/telemetry` → `telemetry/service` and `telemetry/settings`, `evidence` → `reviewevidence`.
- Visibility: 56 top-level functions and properties with no out-of-module reference are now `internal`. The audit narrowed 43 more top-level types (review stage requests and outcomes, specialist launch models, shared review evidence codec/resolution/assembly, workflow service helpers and arg types, `DecompositionManifestWriteGuard`, `Semver`, `UninstallMutationRecorder`) and the `run` members of the claim-verification and spec-adjudication runners, after a type-position census confirmed every exposer is internal or private.
- Types that another module names, reaches by inference, or constructs through runtime-core's generated `@Inject` graph stay public; see `agent/decisions.md` (2026-09-23).
- Trap: after subtask 1 put release fetching behind `ReleaseCatalogPort`, `:runtime-cli:kspKotlin` and `:runtime-mcp:kspKotlin` could not resolve the infra adapter until `RuntimeComponent` exposed `updateCheckService` (the accessor pattern `skillBillUpdateService` already uses) and `PrincipleEnforcementInventory.runtimeComponentInboundApi` pinned it. A service reached only through a port needs a component accessor for KSP to see the binding.
Feature flag: N/A
Acceptance criteria: 8/8 implemented (validate phase owns pack gate and build phase owns compile proof)

## [2026-09-23] SKILL-370 subtask 1 — Container wiring and adapter boundaries
Areas: runtime-application/{review/parallel,install,workflow,idestatus,updatecheck,diagnostics}, runtime-cli/workflow, runtime-mcp/workflow, runtime-infra/{sqlite,http}, runtime-ports, runtime-core/di
- Parameter-bag wiring removed: `ParallelCodeReviewRunnerBoundaries`, `ParallelCodeReviewRunnerComposition`, `InstallPlanningPorts`, and `InstallReconcilePorts` are deleted; review collaborators and `InstallService` are `@Inject` classes whose constructors list only what they read.
- Pattern: prefer direct `@Inject` constructor dependencies over bag or composition types; `InjectConstructorDefaultsArchitectureTest` now also fails any runtime-application `@Inject` class with a non-private constructor property (baseline empty), and the composition-root source-shape test is gone. reusable
- Adapter concerns left the application layer: SQLite busy-write retry lives in `SQLiteDatabaseSessionFactory` (3 attempts, `SelfManagedWriteBusyRetry` deleted), GitHub release fetching moved behind `ReleaseCatalogPort` with `GitHubReleaseCatalogAdapter` in runtime-infra/http, and `AgentActivityStampWriter` takes an injected `TimeSource` instead of `System.nanoTime()`.
- Namespace hygiene: no runtime-application type or file name contains `Cli`; rejected-output diagnostics moved to `RejectedOutputDiagnosticInspection` with CLI formatting owned by runtime-cli. `WorkflowService` exposes no public dependency property, and CLI/MCP adapters no longer call `goalObservabilityLatestEventFromArtifacts`.
- Tests: `ReviewRecordingHarness` builds collaborators through a public `parallelCodeReviewRunnerOf` testFixtures function shared with `ParallelCodeReviewRunnerTest`; GitHub mapping cases moved to `GitHubReleaseCatalogAdapterTest`; new sqlite busy-retry case and an update-check malformed-entry case via a substituted port.
- Limitation: application test fakes now raise "self-managed write lock contended" so runtime-application source is free of `SQLITE_BUSY` / `database is locked`; engine reason-text checks are untouched.
Feature flag: N/A
Acceptance criteria: 11/11 implemented (validate phase owns pack gate and targeted module test runs)

## [2026-09-16] SKILL-347 subtask 3 — Review composition and stateless use cases
Areas: runtime-application/{review,telemetry/config,featurespec,updatecheck}, runtime-core/architecture
- Before: runner unwrapped dual boundary bags and rebuilt planning, lane launch, result assembly, and verification; duplicate launcher and evidence-locator bindings; mutable update-check parser flags; unused `TelemetryConfigMutationRuntime`, `ReviewCommitSequenceResolver`, and `TelemetryConfigRuntime` forwarding; test-only feature-preparation aliases.
- After: `ParallelCodeReviewRunnerComposition` is the sole collaborator graph with one `ParallelCodeReviewRunnerBoundaries` input; runner sequences `run` only; update-check failures are invocation-local; telemetry settings parse via domain functions; `FeatureSpecPreparationRuntime.prepareForFeatureSpec` remains the injected seam; `InstallAgentService` retained per decisions log.
- Tests: review entry-point harness unchanged in behavior; commit-sequence coverage targets `SharedReviewEvidenceAssembler` / `SharedReviewEvidenceProjection`; overlapping update-check regression added.
Feature flag: N/A
Acceptance criteria: 7/7 implemented (validate phase owns pack gate)

## [2026-09-16] SKILL-347 subtask 2 — Activity and decomposition projection ownership
Areas: runtime-application/{idestatus,workflow,decomposition}
- `AgentActivityStampWriter` keeps throttle state per writer instance (LRU cap 512 workflow keys), advances persistence acknowledgement only after a successful write, and emits bounded `RuntimeDiagnostics` warnings on ordinary write failures.
- `continueWorkflow` and workflow updates carry `PendingDecompositionProjection` with the authoritative parent workflow id through post-commit manifest settlement; missing owners surface via diagnostics instead of silent no-ops.
- `persistDecompositionManifestProjectionFailure` returns `OWNER_ABSENT` when the target row is missing; projection-only retry behavior is unchanged.
- Pattern: separate observed activity from persisted acknowledgement; bind filesystem projection settlement to the parent workflow id resolved inside the transaction. reusable
Feature flag: N/A
Acceptance criteria: 7/7 implemented (validate phase owns pack gate)

## [2026-09-16] SKILL-347 subtask 1 — Cooperative cancellation and review endpoint lifetime
Areas: runtime-application/{review,idestatus,updatecheck,runtimepersistence,telemetry}, runtime-infra-fs/concurrency, runtime-core/di, runtime-ports/concurrency
- Governed review endpoints enter `use` before Cursor staging and parent launch so staging failures close exactly once and never start a worker.
- Optional-result seams rethrow `CancellationException` and `InterruptedException` before typed degradation; required persistence keeps `initCause` on `RuntimeOwnedFactUnavailable`.
- `JvmInterruptSignalPort` lives in `runtime-infra-fs`; `runtime-application` sync/drain entry points require an injected `InterruptSignalPort` with no JVM default.
- Pattern: shared `rethrowIfCooperativeCancellationOrInterruption` at application optional seams; architecture scan rejects inward thread restoration and application adapter imports. reusable
Feature flag: N/A
Acceptance criteria: 7/7 implemented (validate phase owns pack gate)

## [2026-08-28] SKILL-215 subtask 1 — Execution clocks for goal and current subtask
Areas: runtime-application/{goalrunner,work,model}, runtime-domain/goalrunner/model, runtime-infra-sqlite, runtime-ports/persistence, orchestration/contracts, intellij-plugin/{domain,presentation}
- Goal-runner control state now keeps a current-subtask execution total beside `activeDurationMs`. Heartbeats fold both through the same gap cap; lease reacquire after downtime does not count the gap on either clock.
- Changing current subtask id resets only the subtask total; resume of the same id continues it. `clearControlState` retains both totals.
- `work status` JSON projects the subtask total onto `current_subtask.active_duration_ms` / `active_duration_as_of` with the same live-lease omit rules as the goal fields. `IDE_STATUS_CONTRACT_VERSION` stays `0.1`.
- Plugin `StatusUiMapper` derives subtask elapsed from that accumulator the same way `activeElapsed` derives the goal clock, falling back to wall clock when the field is absent, and caps subtask elapsed at goal elapsed when both exist. Frozen states still do not tick.
- Pattern: sibling accumulator on the same heartbeat fold plus live-anchor omit; additive optional wire field without a contract bump. reusable
- Limitation: standalone feature-task-runtime and feature-verify families still have no accumulators; CLI `goal status` prose is unchanged.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-08-21] Soft-admit optional register lines for claim verification
Areas: runtime-application/review, runtime-application/featuretask, skills/bill-code-review-inline
- Parent stdout stays free-form prose plus `verdict:`; missing or imperfect register lines never fail the lane.
- Optional `[F-XXX]` lines that parse are soft-admitted into merge findings so claim verification and spec adjudication run again.
- Settlement still derives advance from prose `verdict:` first; soft findings are attached to the envelope after that reduction so Minor-only rows cannot override `changes_requested`.
Feature flag: N/A
Acceptance criteria: 3/3 implemented

## [2026-08-21] Prose-only single-subagent review
Areas: runtime-application/review, runtime-application/featuretask, runtime-cli/codereview, skills/bill-code-review-inline
- Dual-agent parallel lanes disconnected: `code-review-parallel` fails loud; `code-review` and feature-task never set `agent2Id`.
- Parent review outcome is free-form prose plus `verdict:`; no register parse admission, no `NO_FINDINGS` gate, no unread-evidence shape block.
- Feature-task review envelope keeps empty `findings` and drives remediation from parent verdict only.
- Delegated mode still fans specialists inside the parent agent; runtime does not verify specialist register shape.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-08-20] Output-gate JSON failures block on the first invalid envelope
Areas: runtime-application/featuretask
- Extracted phase JSON is aligned to that phase's expected fields before the schema gate; nested or trailing required fields are restored on the existing capture.
- A remaining schema or malformed failure blocks immediately. There is no salvage relaunch: a second attempt was not recovering invalid envelopes in practice.
- Audit-gap and review-fix loops stay uncapped; this bound is only the per-visit output-gate correction loop.
- Audit prompts now name closed token sets for verdict and carried_gap_dispositions.observation, with accepted and rejected examples.
Feature flag: N/A
Acceptance criteria: 1/1 implemented

## [2026-08-11] SKILL-184 subtask 1 — Current-phase execution on the IDE status wire
Areas: runtime-application/featuretask, runtime-application/model, runtime-application/work, runtime-domain/workflow/taskruntime, orchestration/contracts, runtime-infra-fs (schema fixtures)
- Additive optional `current_phase_execution` (`phase_id`, `kind`, `count`, optional `total`) on the IDE status wire and typed models; absent value keeps older producers valid; `IDE_STATUS_CONTRACT_VERSION` unchanged
- Projector emits only for the snapshot's current phase (suppresses during goal planning; never leaks a completed neighbour's loop/pass); kinds distinguish `pass` / `semantic_loop` / `gate_run` / `bounded_edge` / `attempt`
- Derivation from durable authorities: audit first pass vs audit-gap loop, review pass number (stale completed pass suppressed after advance), validation `gate_run_count`, capped backward edges with `total`, else generic attempt — no invented unbounded totals
- Pattern followed: schema + model + status projector + golden/parity tests (same surfacing path as `current_model`); planning stays on the existing `planning` object
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-08-11] SKILL-183 subtask 1 — Launched phase model is durable and IDE-visible
Areas: runtime-application/featuretask, runtime-application/model, runtime-application/work, runtime-domain/workflow/taskruntime/model, orchestration/contracts
- `FeatureTaskRuntimePhaseRecord` gained optional `launched_model` / `launched_effort`; the run loop records the model+effort the child was actually launched with, and records neither when no directive resolved
- Both fields are additive-optional on the wire: `toArtifactMap()` round-trips them and pre-change artifact maps lacking both keys still parse, so no migration or backfill is needed. reusable
- `IdeStatusSnapshot.toStatusWireMap()` emits a `current_model` object (required `model`, optional `effort`) or omits the key entirely; `ide-status-schema.yaml` extended to allow both shapes with `IDE_STATUS_CONTRACT_VERSION` intentionally unchanged since the addition is backward-compatible
- Pattern to follow for future status surfacing: extend the persistence record optionally, project through `IdeStatusProjector`, then widen the schema — golden fixtures and the contract-version test pin the compatibility claim
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-08-10] SKILL-176 subtask 6 — Producer-evidence identity includes agent_id
Areas: runtime-application/featuretask, runtime-ports/persistence, runtime-infra-sqlite/db, runtime-kotlin/agent
- Producer-output evidence identity widened to `(workflow_id, phase_id, generation, attempt, agent_id)` so a second agent re-entering the same attempt retains its own immutable row instead of Conflict-crashing phase recording
- Existing rows stay byte-identical; same-agent divergent bytes still Conflict; identical re-retain stays a silent no-op; `producerOutput` / `readProducerOutput` resolve by the calling producer
- SQLite rebuilds `producer_output_evidence` with `agent_id` in the PRIMARY KEY and copies prior rows unchanged (no backfill — `agent_id` was already durable)
- Decision recorded in `runtime-kotlin/agent/decisions.md`: widen identity; reject last-writer-wins and attempt-advance-on-agent-switch
- Regression: seeds `review` gen 0 attempt 2 under one agent, retains different bytes under another, asserts both rows survive and the run continues
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-10] SKILL-180 subtask 2 — Runtime-owned validation gate
Areas: runtime-application/featuretask/validation, runtime-domain/workflow/taskruntime, runtime-ports/validation, runtime-infra-fs, platform-packs/*/platform.yaml, orchestration/contracts, docs, AGENTS.md
- Validate gate execution moved from the agent into the runtime: resolve pack-declared `validation_gate` argv, run in repo root, project bounded findings for repair, rerun to verify, cap iterations, persist measured `gate_run_count` / `gate_runs`.
- No hardcoded gate command; missing declaration falls back to agent-run validate with a surfaced degradation; malformed declaration loud-fails.
- Terminal verifying run uses pack-declared cache-bypass argv; zero-executed-work results never satisfy a terminal outcome; intermediate repair runs stay cache-eligible. reusable
- Validate directive replaces transitional `bill-code-check` invoke with finding-set repair; no-suppression clause from subtask 1 survives unmodified.
- Ownership restated in AGENTS.md and `agent/decisions.md`: runtime owns build/test/gate execution; audit and repair evidence stay read-only repository facts.
Feature flag: N/A
Acceptance criteria: 20/20 implemented

## [2026-08-10] SKILL-180 subtask 1 — Validate no-suppression directive and history correction
Areas: runtime-application/featuretask, runtime-kotlin/agent, runtime-infra-fs/agent, .feature-specs/SKILL-180
- PHASE_VALIDATE now invokes `bill-code-check` (router only; never a stack-specific checker) and forbids silencing findings via annotations, baselines, disabled rules, weakened config, or skipped tests; existing one-run / fix-all / rerun-once batching stays intact.
- BUILD_ONLY validate task + directive forbid introducing suppressions, disabling rules, or weakening config while repairing compile/build failures, without adding a `bill-code-check` invocation.
- Corrected existing `agent/history.md` lines that prescribed `@Suppress` as the preferred fix so they record what landed factually without guiding future work toward suppression.
- Prompt-composer regressions lock the FULL no-suppression + router clause and the BUILD_ONLY no-suppression clause with its existing prohibitions.
- Transitional: the `bill-code-check` invoke clause is temporary until subtask 2 owns the gate; the no-suppression clause is permanent.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-08-10] SKILL-176 subtask 5 — `skill-bill goal repair`
Areas: runtime-application/goalrunner, runtime-application/model, runtime-cli/goal, runtime-core/di, .feature-specs/SKILL-176
- Operator escape hatch inspects goal children for known resume wedges (missing `validation_depth`, unreachable review/remediation base, stale blocked `goal_continuation_outcome`) and clears them only with `--apply`; default is report-only
- Repair preserves completed subtask commit shas, review passes, and audit repair state; refuses live-lease children; healthy goals are a zero-exit no-op; non-wedged rows report which checks passed instead of a false success
- Durable `goal_child_repair_evidence` records field, prior/new value, and wedge class (append-only, additionalProperties — no workflow-state schema bump); apply is atomic via the workflow write seam
- Reusable: shared `GoalRunnerChildRepairOperations` diagnosis/apply used by status service and CLI; same absent-vs-set, reachability, and corroboration rules as subtasks 1/2/4
- Limitation: explicit operator tool only — does not auto-repair on resume; not a general row editor
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-10] SKILL-176 subtask 4 — Blocked-reason fidelity
Areas: runtime-application/goalrunner, runtime-application/featuretask
- Stored non-complete `goal_continuation_outcome` is no longer authoritative alone; resume corroborates against derived durable state before short-circuiting
- Stale `blocked` outcomes are displaced transactionally (supersede artifact + sibling `goal_continuation_outcome_displacement` evidence naming the original reason) before authority selection and markBlocked, so crash reconcile can run and reconcileAuthoritativeOutcomes cannot re-block from the superseded row
- Corroboration: blocked needs matching derived reason; failed needs derived failed; paused needs durable paused; timeout stays authoritative; COMPLETE-without-sha fall-through unchanged
- `GoalReviewRunPreparation.Blocked` carries the specific reason and disposition; the review-prep caller no longer substitutes a fixed scope-failure sentence
- Reusable: read-time corroboration plus sibling displacement evidence outside the goal_continuation key set; data-class Blocked so computed reasons reach operators and observability
- Limitation: no migration of historical rows; detection is at read/resume time only
Feature flag: N/A
Acceptance criteria: 12/12 implemented

## [2026-08-10] SKILL-176 subtask 1 — Validation-depth absent vs default
Areas: runtime-application/featuretask, runtime-domain/workflow/taskruntime/model, .feature-specs/SKILL-176
- Durable `validationDepth` is nullable: a missing `validation_depth` key decodes as null and is omitted from `toArtifactMap`, so absence is no longer conflated with `ValidationDepth.DEFAULT` (`FULL`)
- Resume conflict gate blocks only when a recorded depth differs from the launcher; absent durable depth adopts the supplied value and continues
- Adoption persists separate `goal_continuation_field_adoption` evidence (field, adopted_value, reason) without expanding the continuation artifact key set
- Audited every `suppliedGoalContinuationConflict` field: identity/codeReviewMode/reviewBaseline cannot be absent at the gate; parallelReviewAgent treats omit and none as the same policy by construction
- Pattern: absent-adopts-supplied with a dedicated observability artifact — do not heal by decoding defaults. reusable
- Limitation: review-baseline reachability remains subtask 2; launcher depth selection is unchanged
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-09] SKILL-179 subtask 3 — Legacy prose-mode row lifecycle
Areas: runtime-application/{featuretask,workflow,goalrunner}, runtime-ports/persistence, runtime-infra-sqlite/db/workflow, runtime-cli/featuretask, .feature-specs/SKILL-179
- Parent discovery lists RUNTIME and PROSE feature-task rows so a legacy goal parent surfaces as `goal_continuation` instead of silent `no_match` over live durable state.
- Engine writes still refuse prose via `requireRuntimeModeForEngineWrite` / `LegacyProseWorkflowError` — discovery never flips `mode` or asserts the runtime schema over retired step ids.
- Identity-less runtime candidates return `NeedsIdentityRepair` naming the workflow and directing operators to `feature-task repair-identity` before projection can throw.
- `feature-task abandon` terminalizes prose parents with a status/artifact-only SQLite write that preserves `mode=prose` (no refused prose writer, no runtime upsert). reusable
- Regression tests cover prose-parent discovery, identity-less lookup, and prose abandon; CLI maps `NeedsIdentityRepair` to the operator-facing result.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-09] SKILL-179 subtask 1 — Application/persistence test contract migration
Areas: runtime-application/{application,decomposition}, runtime-core/application (tests), .feature-specs/SKILL-179
- Runtime router continuation seeds completed preplan+plan phase records; compact `currentStepArtifacts` is `[plan]` citing `WorkflowEngine.resumeView` filtering of `repository_evidence` and `compactContinueView` fallback to requiredKeys (parity with WorkflowCompactContinuationTest).
- Decomposition terminal step tracking accepts runtime `pr` alongside legacy `pr_description`.
- Deleted implement session-summary hydration test: prose-only `FeatureImplementSessionSummary` path; TASK_RUNTIME `sessionSummary` is empty by contract.
Feature flag: N/A
Acceptance criteria: 5/5 implemented (validate phase owns the gradle gate)

## [2026-08-08] SKILL-173 subtask 2 — Validate honors build_only vs full
Areas: runtime-application/featuretask, runtime-domain/workflow/taskruntime, docs, .feature-specs/SKILL-173
- Phase 6 validate consumes goal-continuation `validation_depth`: `build_only` swaps Task text + addendum to compile/buildability only (no tests, detekt, spotless, lint, scanners, or full `bill-code-check`); `full` / non-goal keeps today's Phase 6 directive byte-stable.
- Handoff projection `required_checks` under `BUILD_ONLY` is a single compile/buildability obligation; plan `test_obligations` and strategy lines are not forwarded. `FULL` retains strategy + test obligations.
- `validation_receipt` field set unchanged (`validation_status`, `checks`, `repository_checkpoint`) so `write_history` / `commit_push` need no schema bump. reusable
- Depth threads briefing assembler → prompt composer → run-loop handoff; composer and projection tests cover `build_only` vs `full`/default.
- Limitation: enforcement is prompt + narrowed `required_checks` only; no first-class `bill-code-check` compile-only mode.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-08-08] SKILL-173 subtask 1 — Thread validation_depth on goal continuation
Areas: runtime-application/{goalrunner,featuretask,model}, runtime-domain/workflow/{model,taskruntime}, runtime-ports/agentrun, runtime-infra-fs/launcher/agentrun, runtime-cli/featuretask, .feature-specs/SKILL-173
- Goal-continuation models carry `validation_depth` (`build_only` | `full`); omitted / legacy / non-goal defaults to `full`.
- `GoalRunner` stamps via `validationDepthForSubtask`: last non-skipped in manifest array order gets `full`, earlier non-skipped get `build_only`; skipped ordinal-last promotes the previous last non-skipped. reusable
- Status source is `DecompositionSubtask.status`, never dependency.skipped.
- CLI / command-builder / env round-trip preserves depth on launch and resume; closed-key loud-fail and resume conflict covered in tests.
- Limitation: validate phase still ignores depth until subtask 2 consumes it; this only threads and stamps the field.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-08] Goal planning shared-context packet v0.2
Areas: runtime-application/goalrunner, .feature-specs/SKILL-172-goal-planning-burst-and-context (deferred follow-on)
- `GoalPlanningSharedContextPacket.VERSION` is `0.2`; `platform_packs` removed from `PACKET_FIELDS`.
- Resume migrates intact 0.1 checkpoints in memory (verify legacy integrity, drop key, re-digest) without rewriting immutable preplan rows; `validate` stays strict on current keys only.
- Fresh assembly and `GoalPlanningContext` no longer produce or carry platform packs. `validation_guidance` rename remains deferred.
Feature flag: N/A
Acceptance criteria: N/A (follow-on to SKILL-172 deferred packet cleanup)

## [2026-08-08] SKILL-172 planning burst control: pace + empty-turn backoff (subtask 2)
Areas: runtime-application/{goalrunner,model}, .feature-specs/SKILL-172-goal-planning-burst-and-context
- Planning sweep no longer launches consecutive per-subtask plans back-to-back: `GoalPlanningBurstSchedule` applies a configurable pace between launches only (default 20s; never before first or after last).
- `EmptyProviderTurn` retries back off before relaunch (default base 30s, factor 2 → 30s then 60s); attempt 1 stays unpreceded and `MAX_FIX_LOOP_ITERATIONS` is unchanged.
- Waits go through injected `RuntimeTimingPort` in `waitSlice` chunks so durable pause and thread interrupt terminate the same way as a launch interrupt — no `Thread.sleep`, no agent/model/provider identity reads. reusable
- Defaults arithmetic is documented on the schedule: 15-subtask happy path adds `14 * 20s = 280s` (4m40s), inside default planning budget; tests drive ordinal wait recording, backoff schedule, pause-mid-wait, and interrupt-during-wait.
- Limitation: pacing/backoff apply only to the planning sweep; no adaptive rate control and no non-planning launch pacing.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-08] SKILL-168 subtask 4 — IDE status pause signals contract
Areas: runtime-application/{model,work,goalrunner}, runtime-domain/goalrunner/model, orchestration/contracts, runtime-infra-fs/contracts/workflow, .feature-specs/SKILL-168
- IDE status gains two optional additive wire fields, `pause_requested` (boolean) and `paused_at` (string instant); neither is in the schema `required` list and `contract_version` stays `0.1`, so the plugin pin needs no change.
- Semantic split: only a *consumed* pause projects `lifecycle_state: paused`. A requested-but-unconsumed pause stays `active` and surfaces as the `pause_requested` modifier — the previous projector collapsed both into `paused`.
- Omission discipline: `pause_requested` is never emitted as `false` and `paused_at` is emitted only from the durable control record; a lease-expiry-inferred pause omits the timestamp rather than back-filling from `heartbeat_at`/`updated_at` (an inference must not be sold as a record). reusable
- `pausedAt` threaded through `GoalRunnerStatusProjectionExtras` -> `GoalRunnerStatusProjection` -> `GoalRunnerStatusService`; only `projectGoal` populates the pause fields, so every non-`feature-goal` family stays wire-identical.
- Pattern reused from SKILL-165 subtask 1: schema-first additive extension (canonical schema with `additionalProperties:false` + contract-version parity test), then models/projector/goldens follow; golden fixture diff is additions-only. reusable
- Limitation: projection-only — plugin consumption of both fields is subtask 5.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-07] SKILL-165 subtask 1 — IDE status planning progress wiring
Areas: runtime-application/{model,work}, orchestration/contracts, runtime-cli, runtime-infra-fs/contracts/workflow, .feature-specs/SKILL-165
- IDE status gains an optional `planning` block (snake_case wire keys, omitted entirely when null) so goal decomposition progress is visible before any subtask exists; additive-only, so `contract_version` stays at `0.1`. reusable
- `IdeStatusProjector` maps non-prepared planning to step `planning`/label `Planning` with a progress summary; prepared or absent planning keeps prior projection behavior, and non-goal families never carry planning.
- Pattern: schema-first additive wire extension — canonical schema (`ide-status-schema.yaml`) with `additionalProperties:false` plus a contract-version parity test pins the shape, then models/projector/goldens follow. reusable
- Pause precedence reconfirmed by test: a blocked candidate stays blocked under `paused`/`pause_requested`; only an active candidate projects `paused`.
- Limitation: planning is projection-only (no consumer-side IDE rendering in this subtask); golden fixtures cover a single mid-planning goal shape.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-08-07] SKILL-164 subtask 3 — Audit and review projection delivery
Areas: runtime-application/{evidence,featuretask}, runtime-domain/workflow/taskruntime, runtime-ports/taskruntime, runtime-infra-fs, orchestration/contracts, .feature-specs/SKILL-164
- `FeatureTaskRuntimeSharedReviewEvidenceReference` is a closed-world projection (store_path, checkpoint_fingerprint, base_ref/head_ref, file/hunk index only) with an explicit field allowlist; no diff bytes cross the handoff boundary, so projection size stays independent of branch diff size. reusable
- `PHASE_PROJECTION_MATRIX` delivers that reference to both `PHASE_REVIEW` and `PHASE_AUDIT`; audit retains `scoped_repository_state` unchanged — the shared diff is a floor, never audit's whole evidence set. reusable
- Briefing `diff` / `current_unit_of_work` instructions name the delivered reference when present (and fall back to self-read only on the omit path); `scoped_repository_state` still requires actual repository state over upstream receipt claims.
- Backward edges (`audit_gap`, `review_fix`) reuse or re-derive via existing `REFRESH_FROM_REPOSITORY` / `MUST_MATCH` checkpoint policies; an absent referenced artifact re-derives instead of failing the run. reusable
- Pattern: deliver shared checkpoint-keyed evidence as a reference projection, not inlined bytes; keep topology, entry gates, and verdict semantics untouched. Limitation: schema registration, telemetry, and e2e wiring remain subtask 4.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-07] SKILL-164 subtask 2 — Phase-neutral shared review evidence assembly
Areas: runtime-application/evidence, runtime-application/review, runtime-ports/taskruntime, runtime-infra-fs, .feature-specs/SKILL-164
- Commit evidence assembly (`ReviewCommitUnit`/`ReviewChangedHunk`/`ReviewEvidenceTarget`) moved out of `ReviewCommitSequenceResolver` into a phase-neutral `SharedReviewEvidenceAssembly` invoked by the checkpoint-keyed shared deriver; review is now the first consumer, not the owner. reusable
- Derivation happens once per checkpoint+scope key; a fingerprint hit resolves with zero git traversal and every specialist lane (incl. `ReviewLaneBundleAssembly`) reads the same stored artifact instead of re-deriving per lane. reusable
- `SharedReviewEvidenceCodec` gives byte-for-byte identity round-trip for stored evidence, including synthetic sources (`SYNTHETIC_WORKING_TREE`/`SUPPLIED_DIFF`/`AGGREGATE_PR_DIFF`) and degraded-reason fidelity; corrupt payloads degrade to fresh derivation rather than failing the phase. reusable
- Pattern: key shared phase evidence by (repository checkpoint fingerprint, scope) at a port seam, derive once, and prove reuse with a zero-traversal assertion rather than a cache-hit counter. reusable
- Limitations: only review consumes the store; audit consumption, projection contract changes, and briefing instruction changes remain out of scope. Observable review behaviour is unchanged by design.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-06] SKILL-158 subtask 2 — Sparse commit-to-lane routing
Areas: runtime-domain/review/plan, runtime-domain/review/context/model, runtime-application/review, runtime-ports/review, runtime-infra-fs, orchestration/contracts/review-context-schema.yaml, .feature-specs/SKILL-158
- Specialist selection is now a final commit×lane matrix (`focused`|`skipped` + falsifiable reason/signals) decided once from each commit's own hunk paths/content — never the commit subject — before launch; workers do not re-decide relevance. reusable
- Required baseline lanes stay focused for every unit; optional lanes receive only evidence-matched commits, and per-lane bundles keep packet commit order with only focused hunk bodies. reusable
- Routing/assignment digests and merger provenance include the matrix; non-commit scopes keep single-synthetic-unit lane selection. Parent analysis is bounded by `max_routing_analysis_pairs`/`max_routing_analysis_bytes` and loud-fails on breach.
- Pattern: decide relevance at the parent routing seam against rubric path/content signals, assemble lane bundles from focused commits only, and prove skip reasons with adversarial fixtures rather than a worker backstop. reusable
- Limitations: single-pass bundled lane worker execution remains subtask 3; this unit only owns sparse routing and bundle preparation.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-06] SKILL-158 subtask 1 — Commit-aware review evidence model
Areas: runtime-application/review, runtime-domain/review-context-model, runtime-ports/review-model, runtime-contracts/review, orchestration/contracts/review-context-schema.yaml, .feature-specs/SKILL-158
- Review packets now carry the ordered commit sequence (sha, parent, subject, position) plus each commit's incremental hunk set, replacing the single accumulated base-to-head diff as the worker-facing evidence surface.
- Commit identity/order and per-lane bundle composition participate in packet and assignment digests; validation rejects missing, duplicate, out-of-order, unowned, or out-of-packet commit/hunk references. reusable
- Coverage is proven by an aggregate base-to-head equivalence fact (chain-and-ownership) so ordered commit units cannot silently omit or duplicate the authoritative delta. reusable
- Non-commit scopes (staged, unstaged, combined working tree, supplied diff, locally-unavailable PR) collapse to exactly one synthetic review unit with explicit source metadata — no fabricated commit history. reusable
- Pattern: Git discovery stays parent-owned; workers receive projected commit units with commit-attributed hunk bodies and never recompute scope, order, or broad diffs. Complete-diff artifacts are no longer part of the launch projection.
- Limitations: commit-to-lane routing (subtask 2) and single-pass bundled lane execution (subtask 3) are out of scope; this unit only establishes the typed evidence and serialization contract.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-05] SKILL-160 subtask 1 — Scoped per-subtask replan
Areas: runtime-application/{goalrunner,model}, runtime-cli/goal, runtime-infra-sqlite/workflow, runtime-ports/{goalrunner,persistence}, .feature-specs/SKILL-160
- Added `goal replan <key> --subtask <id>`: delete-only `deleteSubtaskPlan` discards one `goal_subtask_plans` row, retargets current-subtask intent, and leaves sibling plans, shared preplan, runtime fields, and acceptances untouched.
- Reuses `firstMissingPlan` so the next goal launch regenerates only the discarded plan; `PARTIALLY_PLANNED` status names the replanned subtask. reusable
- Pattern: new request type beside reset (never widen `GoalRunnerResetRequest`); refuse LIVE/UNKNOWN liveness, terminal targets (name `reset`), unknown key, absent/non-positive id before any durable write. reusable
- Breaking changes/limitations: does not delete the child workflow row or shared preplan (`--include-shared-preplan` is subtask 2); operator must name the subtask — no automatic staleness detection.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-01] SKILL-154 subtask 3 — Monitor-only bill-monitor skill and status integration
Areas: runtime-cli/goal, runtime-infra-fs/scaffold, governed skills, docs, .feature-specs/SKILL-154
- Added `bill-monitor` as a governed read-only entry point with dynamic catalog/install coverage and same-thread monitor-mode guidance.
- The monitor status seam canonicalizes the repository, performs one bounded snapshot, and exposes only counts, current location, liveness, and resumable state. reusable
- Pattern: keep monitor output separate from full goal status and reject diff, malformed-issue, launch, resume, watch, and mutation paths before execution.
- Known limitation: monitor mode does not carry across a new conversation; an explicit invocation is required to re-establish the read-only contract.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-01] SKILL-154 subtask 2 — Deterministic goal pause and completion control
Areas: runtime-application/{goalrunner,model}, runtime-cli/goal, runtime-domain/goalrunner, runtime-infra-fs/{agentrun,process}, runtime-infra-sqlite/{workflow,migrations}, runtime-ports/{agentrun,goalrunner,persistence}, orchestration/contracts/telemetry, .feature-specs/SKILL-154
- Added predeclared stop-after-subtask and durable idempotent operator pause control; the pause is applied after terminal child completion is persisted and before the next child is selected or launched.
- Resume preserves the parent workflow identity, planning checkpoints, commits, lease/generation fencing, and child continuation state, then starts at the first pending runnable subtask. reusable
- The foreground driver treats the original child process result as the bounded completion signal, without repeated status, log, filesystem, or database polling. reusable
- Pattern: persist completed-child state and the parent pause boundary atomically, then re-read the control projection before selecting the next child. reusable
- Breaking changes/limitations: a resumed parent keeps its existing stop-after policy; OS interrupts remain only an interruption fallback, not the operator pause protocol.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-01] SKILL-154 subtask 1 — Context-efficient skill and goal orchestration
Areas: runtime-application/{goalrunner,workflow}, runtime-cli/goal, runtime-domain/{goalrunner,workflow}, runtime-infra-fs/{goalplanning,install}, runtime-infra-sqlite/{goalrunner,migrations}, runtime-ports/{goalrunner,persistence}, runtime-contracts/errors, docs, governed feature skills, .feature-specs/SKILL-154
- Added compact supplied-versus-installed skill identity validation, bounded goal-planning context discovery, and thin `{status, commit_sha, workflow_id}` terminal retention.
- Goal launch/completion guidance now exposes one monitoring block and one terminal notification; bounded repository searches and fresh-conversation handoffs name the canonical repository path and issue key.
- Added durable goal-runner control, planning-context projections, and workflow input selection seams with contract coverage for identity mismatch, messaging, bounded output, and retention shape. reusable
- Pattern: keep child payloads, transcripts, and durable evidence out of the parent conversation while passing only validated projections and canonical handoff metadata. reusable
- Breaking changes/limitations: mismatched supplied/installed skill identities loud-fail with both source identities; pause protocol and bill-monitor remain out of scope.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-07-26] SKILL-146 subtask 3 — Bounded feature-task phase projections
Areas: runtime-application/featuretask
- Tightened audit-remediation and review-retry launches to their dedicated bounded projection sets instead of rebuilding broad upstream receipts
- Review-fix handoffs now carry the reviewed repository checkpoint through re-entry and verify the completed implement-fix fingerprint before retrying review
- Audit repair may recover its durable repair plan and state when the prior audit output is unavailable, while still requiring every other declared upstream
- Reused the phase handoff checkpoint contract for branch identity, base branch, and expected-checkpoint enforcement across remediation edges. reusable
- Preserved operator-decision pauses during carried-forward goal review so the runtime cannot settle or advance a review awaiting explicit action
- Breaking changes/limitations: completed implement-fix phases now require repository fingerprinting; fingerprint failure blocks the phase as a process failure
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-07-26] SKILL-143 — Bounded blocked-subtask recovery
Areas: runtime-application, runtime-cli, runtime-domain, runtime-ports, runtime-infra-sqlite, runtime-infra-fs
- Added an explicit subtask-scoped reset that deletes only an incompatible terminal child workflow while preserving planning checkpoints, unrelated subtask state, runtime fields, commits, workflow links, and out-of-band acceptances
- Soft-reset diagnostics name the blocking child workflow and exact scoped recovery command; the attempt ledger distinguishes resumable children from stale terminal children requiring deletion
- Goal-wide hard reset now preflights discarded acceptances and emits restorable commands; explicit restoration mode accepts only the cleared reset projection and recreates durable acceptance
- Pattern: classify durable child state from the workflow store, keep the manifest as a projection, and require an explicit operator action for every destructive recovery edge. reusable
- CLI, application, persistence, and regression coverage lock malformed/unknown/incompatible selector rejection, mutation boundaries, and end-to-end recovery without reaccepting unrelated work
- Breaking changes/limitations: recovery never retries automatically; blocked subtasks still require operator action, and hard-reset restoration requires the emitted explicit flag
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-07-25] SKILL-142 subtask 5 — Bounded inline remediation convergence
Areas: runtime-application, runtime-domain, runtime-cli, runtime-infra-fs, orchestration contracts
- Added a distinct bounded inline review tier for later remediation passes while preserving delegated review as the default full-depth path
- Auto depth now resolves from pass number through a named rule, records the resolved tier and rule, and keeps parallel lanes on one shared tier
- Remediation review is bounded to prior Blockers plus the pre-fix-to-post-fix delta; evidence-backed dispositions drive advance or resumable pause
- Added durable operator decisions; `retry_fix` uses a reusable single-use grant that discounts one capped backward edge, survives resume, and is consumed when `review_fix` re-enters `implement_fix`
- Reconciled the fixed edge cap with unresolved-Blocker semantics so cap exhaustion cannot advance a child that must pause
- Goal-facing summaries stay sanitized while location evidence remains available through the findings command
- Breaking changes/limitations: review-state contract version changed and legacy 0.1 records loud-fail; retry grants apply only to explicit `retry_fix` decisions
Feature flag: N/A
Acceptance criteria: 20/20 implemented

## [2026-07-25] SKILL-142 subtask 3 — Blocker-only reopen semantics
Areas: skills/bill-feature-goal, runtime-domain, runtime-application
- Rewrote reopen sentence in bill-feature-goal to state Blocker-only reopen without contradiction; removed mixed-signal language that suggested Major findings also trigger implement_fix
- Narrowed FeatureTaskRuntimeReviewVerdict.requiresRemediation to BLOCKER only; unchanged blocksAdvance semantics ensures only Blockers prevent advancement
- GoalSubtaskReviewSummaryReducer now counts only Blockers for remediation; Major findings are recorded in the ledger without triggering a fix pass
- Added parity test binding governed prose to runtime enum; prevents contradiction regression between skill content and runtime verdicts
- Compact summaries remove location details while ledger preserves full finding context
- Bounded-output regression test ensures path/line/hunk sanitization from goal-facing summaries
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-07-25] SKILL-142 subtask 2 — Planning projection gate parity
Areas: runtime-application, runtime-infra-sqlite, runtime-ports
- Added producer-side planning projection gate for preplan/plan phases; completed outputs now validate against the planning projection contract before being marked settled or checkpointed
- Introduced in-band replace path to goal planning preparation store (replaceSharedPreplan/replaceSubtaskPlan) for repairing projection-invalid records without losing the parent workflow id
- Producer gate (FeatureTaskRuntimePlanningProjectionGate) and consumer launch seam (GoalChildPlanningHydrator) now share one validation function through one validator port; closes the parity gap where hydration validated phase-output contract while consumer validated planning projection
- GoalPlanningPreparationCheckpoint rejects projection-invalid shared preplans and subtask plans at write time; descriptor() recovers subSpecHash from stored record independent of projection verdict
- Checkpoint-level acceptance tests: projection-invalid records are replaced; gate-satisfying records with different bytes still loud-fail as immutable
- Breaking changes/limitations: Shared preplan replace uses UPDATE-then-insert rather than DELETE because goal_subtask_plans cascades on the shared row (provenance safety)
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-07-25] SKILL-141-goal-parent-resume-lifecycle (subtask 1: parent-workflow-non-terminal-status)
Areas: runtime-application, runtime-infra-fs, runtime-mcp
- Added non-terminal `paused` parent-workflow status; GoalRunnerWorkflowStores.importFromManifestProjection and DecompositionWorkflowContinuation stamp `paused` on interruption instead of `abandoned`
- DecompositionWorkflowRuntimeLookup.findDecomposedParentWorkflow reuses non-terminal parents; isStaleAbandonedLineage never GCs them
- GoalChildPlanningHydrator hydrates from non-terminal parents preserving GoalPlanningIdentity; resume reuses the existing parent workflow id with no identity loud-fail
- FeatureTaskRuntimePhasePromptComposer updated to recognise the new status
- AgentRunCommandBuilders (runtime-infra-fs): foreground driver propagates and detects paused parent status on launch
- McpToolRegistry (runtime-mcp): removed `paused` from feature_task_runtime_workflow_update enum; runtime tool now advertises only statuses FeatureTaskRuntimePhaseWorkflowDefinition accepts; prose tool retains paused unchanged
- Explicit operator abandonment path unchanged: still terminal, reason-required, stamps operator-abandonment artifact
Feature flag: N/A
Acceptance criteria: 7/7 implemented
