# goalrunner boundary history

## [2026-10-10] SKILL-417 subtask 1 — Goal-status liveness agrees with the live goal step
Areas: runtime-engine/goalrunner/{status, model, persist}
- Status extras take child progress while the child is not idle or completed. An idle or completed child still supplies implement/review until the parent is LIVE on a goal_finalization event; then the assembler rewrites the parent copy to the live monitor/PR/CI step.
- That rewrite updates currentStepId and latestLivenessSignal together through GoalRunnerWorkflowProgress.resolvedLivenessSignal(liveStepId), so CLI no longer prints workflow_status=PAUSED; step=plan while current_step is monitor.
- Pattern: one progress path. OutcomeStore.progress still forwards to progressRecording.progress; the helper lives on the progress model and persist uses it for the parked-row fallback string. reusable
- Domain projection models and context stayed unchanged. No second progress implementation.
- Limitation: operator pause still comes from controlState, not parent WorkflowStatus. Subtasks 2 and 3 still own ide-status selection and plugin snapshot acceptance.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-10-08] SKILL-408 subtask 2 — Operator no-change decision and completed_no_change outcome
Areas: runtime-engine goalrunner (execution/core, persist, status, telemetry, manifest), runtime-engine featuretask slot/audit and runloop, runtime-domain goalrunner and workflow/taskruntime, runtime-cli goal, runtime-infra sqlite telemetry and schema, orchestration/contracts, skills/skill-bill
- goal operator-decision routes decisions for a subtask whose child has an undecided no_change_pause before the review-remediation rejection; every other subtask keeps the existing rejection. --instructions is required for retry_fix and rejected with other decisions; it is recorded on the pause artifact.
- accept_and_advance closes the child as COMPLETED with the pause artifact kept, saves the subtask as completed_no_change and advances. A goal with no commit_sha and at least one completed_no_change subtask finalizes as CompletedNoChange with no commit, push or PR; mixed goals finalize as before.
- Settled-status sets (GoalRunnerPolicy, manifest transitions, reconcile, loader, acceptance dependency check) treat completed_no_change as settled so an accepted subtask is not reverted.
- Telemetry and status: goal_finished and goal_issue_finished send completed_no_change with no_change_reason; the CLI presenter prints both in text and JSON. Schema const 1.12.0 is unchanged; the decomposition schema content hash was re-pinned.
- Reusable: many set-based settled checks still treat only complete and skipped as settled (stop reports, telemetry counts, preflight lookup, continuation selector, reset and replan coordinators, spec-drift recovery, manifest restart, planning remedies). Review them before adding any new settled status. reusable
- Pattern: NO_CHANGE_PAUSE_REASON lives in slot.attempt so shared runloop code never imports slot.audit; the no-change pause port is delegated with `by` to keep record classes under detekt TooManyFunctions. reusable
- Limitation: retry_fix and abandon_subtask are not resumable yet; a recorded decision of either stops BLOCKED. GoalRunnerRunEvent.Completed is not emitted for CompletedNoChange. The accept-path GoalRunnerTest shares one child-workflow database between the phase recorder and the closer and asserts child COMPLETED.
Feature flag: N/A
Acceptance criteria: 8/10 implemented (AC6 retry_fix resume and AC7 abandon_subtask open)

## [2026-10-04] SKILL-399 subtask 4, goal-planning preparation results
Areas: runtime-engine/goalrunner, runtime-engine/goalplanning, runtime-ports/goalrunner, runtime-contracts/error/shellcontent, runtime-infra/sqlite, runtime-infra/contracts, runtime-core/architecture and goal tests
- Replaced preparation schema and recovery exception classes with InstallFailureCode failures and removed their custom-throwable baseline entries.
- Repository methods return preparation conflicts through typed results. Launch, hydration, child persistence, recovery and sweep readers preserve the conflict's subtask and reason.
- Followed data-only ports and exhaustive result handling. Preparation checks precede transaction writes; SQL faults retain rollback behavior.
- GoalPlanningPreparationConflict and its result families are reusable across persistence and engine readers; the engine-owned toFailure conversion preserves existing stopping behavior where required.
- Contract mismatch producers emit the contract-incompatible code; hard-reset recovery walks causes by code and retains existing schema-message compatibility.
- Added selected-subtask launch regression coverage and converted existing assertions and fixtures to results or failure codes.
- Breaking API change: preparation repository return types changed, including preparedPlanCount. Durable formats and feature flags are unchanged; the remaining legacy exception transition belongs to later subtasks.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-09-19] SKILL-361 subtask 2 — Nest engine goal-runner packages
Areas: runtime-kotlin/runtime-engine/{goalrunner, tests}, runtime-kotlin/{runtime-core, runtime-domain, runtime-ports, runtime-infra-sqlite, runtime-application}
- Nested goal-runner production and test types into responsibility-named packages; moved public inputs and results into `goalrunner.model` without behavior changes.
- Updated package-ceiling inventory, DI, and consumer ownership seams so the root and planning sibling counts remain within limits.
- Pattern: co-locate tests with moved production types and use noun-family package boundaries for runtime ownership. reusable
- Limitation: no behavior, schema, or CLI changes; remaining module nesting is subtask 3.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-09-16] SKILL-248 subtask 1 — Own goal execution cleanup and failure propagation
Areas: runtime-kotlin/{ARCHITECTURE.md, runtime-engine/{goalrunner, tests}}
- Goal-runner execution now owns reverse-order lease/heartbeat/shutdown-hook cleanup across startup, cancellation, interruption, body, and teardown failures while preserving primary errors and continuing cleanup.
- Progress, ledger, and observability callbacks propagate cancellation/interruption; failed watermark reads stay visible, and optional write failures emit bounded diagnostics without masking primary outcomes.
- Pattern: keep fenced release and bounded shutdown facilities at the coordinator boundary; record authoritative ledger watermarks before resumed writes. reusable
- Limitation: expired-lease policy, heartbeat cadence, workflow ordering, and ledger schema remain unchanged.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-09-15] SKILL-245 — Goal purge command
Areas: runtime-kotlin/{runtime-engine/{goalrunner,featuretask}, runtime-infra-sqlite/{goalrunner,workflow}, runtime-infra-fs, runtime-cli/goal, runtime-ports, runtime-contracts}
- Added confirmed `goal purge` orchestration that refuses live leases, deletes goal-owned durable state, restores the prepared manifest/spec tree, and prunes issue checkpoint refs without changing branches, commits, standalone workflows, or telemetry.
- Added SQLite purge persistence, git restoration, CLI confirmation/formatting, purge ports, and regression coverage across persistence, engine, and CLI boundaries.
- Pattern: keep destructive goal purge separate from hard reset and coordinate database deletion with authored-spec restoration. reusable
- Limitation: a dedicated CLI confirmed-purge plus preflight `new_work` integration assertion remains follow-up coverage.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-09-14] SKILL-346 subtask 2 — Hard reset and commit-span recovery
Areas: runtime-kotlin/{runtime-engine/{goalrunner,featuretask}, runtime-infra-fs, runtime-ports, runtime-contracts, runtime-cli}
- Hard reset now preflights same-goal trailer spans, refuses when publication or parent ownership cannot be proven, and documents or performs the required branch action before clearing durable identity.
- Relaunch recovery adopts a matching trailer-backed identity and accepts a subtask N>1 review base at the prior subtask commit without an ambiguous-span block; checkpoint refs and completed commits remain preserved.
- Pattern: coordinate durable reset state with proven git ownership and make branch mutations explicit in the reset result. reusable
- Regression coverage reproduces interrupted hard-reset recovery for published and unpublished trailer tips.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-09-14] SKILL-346 subtask 1 — Runnable recovery recommendations
Areas: runtime-kotlin/{runtime-engine/goalrunner, runtime-engine/featuretask, runtime-cli/goal}
- Recovery projections advertise scoped child deletion only for blocked subtasks with incompatible terminal child workflows; active or in-progress cases receive runnable hard-reset, replan, or resume/stop guidance.
- Soft reset, child-wedge repair refusal, durable child classification, and CLI expectations share the same status and deletion-precondition gate.
- Pattern: derive operator recovery commands from durable subtask state and deletion preconditions so printed commands remain executable. reusable
- Limitation: hard-reset branch cleanup and relaunch-span behavior remain in the next recovery subtask.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-09-13] Issue 342 — Expired lease reclaim on relaunch
Areas: runtime-kotlin/{runtime-engine/goalrunner, runtime-infra-sqlite/goalrunner, runtime-ports/goalrunner}
- Expired parent and child leases now reclaim through the existing takeover path despite ambiguous process inspection, while unexpired live leases remain fail-closed.
- Reclaim clears stale `runner_interrupted` control state without changing operator-stop pause state; coverage spans execution, worker recovery, and SQLite control persistence.
- Pattern: use injected lease expiry as the confirmed-dead boundary and preserve generation-bump ownership semantics. reusable
- Limitation: residual wedges outside expired-lease relaunch remain in the follow-up repair scope.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-09-12] SKILL-340 subtask 2 — Repair apply SQLITE_BUSY lifecycle fix
Areas: runtime-kotlin/{runtime-cli, runtime-engine/{featuretask,goalrunner}, runtime-infra-sqlite, runtime-ports}
- Repair diagnoses are materialized before lease preflight, and inspect read scopes close before per-child apply transactions so `goal repair --apply` can persist wedge repairs without `SQLITE_BUSY`.
- Inspect findings flow into apply; regression coverage exercises the `completed_upstream_missing_output` path, and refusal messages point operators to scoped `goal reset --subtask` recovery.
- Pattern: separate diagnosis snapshots from write transactions and carry explicit findings through repair. reusable
- Limitation: wedge classes and review-remediation policy remain unchanged; focused tests and quality validation belong to the validation phase.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-09-03] SKILL-230 subtask 2 — Concurrent planning status wire
Areas: orchestration/contracts/ide-status-schema, runtime-contracts/workflow, runtime-domain/goalrunner.model, runtime-ports/goalrunner, runtime-infra-sqlite/db.workflow, runtime-application/{goalrunner/planning,idestatus,work}, runtime-cli/goal, runtime-mcp/workflow
- `GoalPlanningStatusSnapshot` carries `planningWaveSubtaskIds` (manifest-ordered missing-plan ids, capped at `GOAL_PLANNING_WAVE_CAP`); the domain `init` requires `currentPlanningSubtaskId` to equal the wave minimum, so the IntelliJ plugin and VS Code extension keep reading one id with no edit.
- `GOAL_PLANNING_WAVE_CAP` (5) lives in runtime-contracts beside `IDE_STATUS_CONTRACT_VERSION`; the burst-schedule default, the wire-model guard, and the schema `maxItems` parity test all read it, so one edit moves cap, dispatch, and contract together. reusable
- Wave derivation moved out of `GoalPlanningStatusProjectionSql` into `GoalPlanningStatusSnapshotDerivation` (infra-sqlite): the wave is the first `cap` missing ids only for PREPLANNED/PARTIALLY_PLANNED, empty for NOT_STARTED/PREPARED/BLOCKED, and adds no query to the status read path.
- Schema: additive optional `planning_wave_subtask_ids` (minItems 1, uniqueItems, maxItems 5) at unchanged `contract_version` 0.2; `IdeStatusPlanning.toStatusWireMap()` omits the key when empty and rejects blank, duplicate, or over-cap entries at construction.
- `alignPlanningStatusWithLaunchRecoverability` clears the wave in the same copy that rewrites a resume-claiming reason to non-resumable, so status never names in-flight subtasks it cannot resume and `GoalPlanningStatusReasonCoherence` still holds.
- Goal CLI JSON and MCP carry `planning_wave_subtasks` as integers; the `work status` summary and the goal human line name the concurrent count (`wave=N subtasks`).
- Pattern: a defaulted field inserted before a non-defaulted one breaks positional constructors; three callers moved to named arguments instead of appending the field at the end. reusable
- Limitation: the wave is a projection of the missing-plan set, not liveness — a wedged lane and a healthy one look identical; per-lane heartbeat stays a non-goal.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-09-03] SKILL-230 subtask 1 — Bounded parallel plan fan-out
Areas: runtime-application/goalrunner/planning, runtime-ports/concurrency, runtime-ports/goalrunner.model, runtime-infra-fs, runtime-core/di
- Goal planning sweep dispatches missing subtask plans in waves of at most `GoalPlanningBurstSchedule.planFanOutCap` (default 5) through the new `BoundedWorkFanOutPort`; the 20s `planLaunchPace` inter-launch gate and per-launch `firstMissingSubtaskId` re-derivation are gone, replaced by one `recoveryProgress` read into an ordered `missingSubtaskIds` set.
- `BoundedWorkFanOutPort` returns one `Result` per input in input order so a failing unit cannot lose sibling results; `CancellationException` rethrows on the caller thread instead of being laundered into a failed `Result`. Ships with `SequentialBoundedWorkFanOutPort` as the deterministic test default. reusable
- `JdkBoundedWorkFanOutPort` lives in runtime-infra-fs next to `JdkRuntimeTimingPort` (daemon pool sized `min(cap, unitCount)`, shut down on every exit path) so runtime-application main stays free of threading APIs and `RuntimeLayerBoundaryArchitectureTest` holds.
- Pattern: drain the whole wave before choosing a stop and pick the lowest input-order failure, so the reported `currentSubtaskId`/`blockedReason` do not depend on thread completion order and sibling plans stay checkpointed. reusable
- Pattern: per-subtask output-sink decorator emits whole attributed lines under the port's mutual exclusion, keyed by `AgentRunOutputStream` because one sink is driven by both stdout and stderr drain threads. reusable
- Pause is checked before a wave and between waves on top of the per-attempt check; the post-drain check is skipped after the final wave so a fully prepared sweep still returns `PreparedAll`, never `PAUSED`.
- Fan-out port DI landed in `RuntimeInstallScaffoldBindings`/`RuntimeTelemetryInstallProvides3` (not A5/Provides4) because those files already hold detekt's 10-function ceiling; Provides13 already owns `goalPlanningSweepLaunchPort`.
- Limitation: cap is not operator-configurable, preplan stays serial, and `currentPlanningSubtaskId` still carries a single value — concurrent-planning status reporting is subtask 2.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-08-29] SKILL-220 subtask 5 — Oversized goal-runner decomposition
Areas: runtime-application/goalrunner (+ planning), runtime-domain/{goalrunner.model,workflow.goal.model}, runtime-infra-sqlite/db/workflow
- Split P-08 goal-runner monoliths (`GoalRunner`, workflow stores, planning sweep/prep store, status service, review state/reducer, child repair, domain models) into named collaborators; facades stay under 500 lines; new production files max ~396.
- Kept `GoalRunner` as the sole public orchestration entry; extracted helpers stay package-internal; atomic writes and lease/wait boundaries unchanged; JDBC/SQL stays in infra-sqlite.
- Pattern: responsibility-named internal collaborators behind thin facades; remove LargeClass/TooManyFunctions/LongParameterList suppressions the split makes unnecessary. reusable
- Limitation: leftover `@Suppress` is SKILL-221; feature-task and remaining oversized units are other subtasks; no schema/CLI verb changes.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-24] SKILL-200 subtask 4 — Runtime-owned preflight and add-on resolution
Areas: runtime-application/{goalrunner,featuretask,decomposition,workflow}, runtime-domain, runtime-cli/goal, runtime-infra-{fs,sqlite}, runtime-ports, orchestration/contracts
- Added read-only `skill-bill goal preflight` projections for continuation verdicts, the confirmation gate, and missing Linear spec rehydration targets without launch-side effects.
- Reused continuation classification and resolved raw ordered add-on slugs inside `goal`, while preserving structured source-identity and content-digest verification.
- Pattern: keep pre-launch decisions in a read-only runtime projection and share classification and selection seams with launch. reusable
- Limitation: Linear fetching remains agent-side; preflight reports missing targets but does not retrieve them.
Feature flag: N/A
Acceptance criteria: 17/17 implemented

## [2026-08-22] SKILL-204 subtask 2 — Route non-last goal children through build
Areas: runtime-application/goalrunner, runtime-application/featuretask, runtime-domain/workflow/taskruntime, runtime-cli/featuretask, runtime-infra-fs/launcher/agentrun
- Goal continuation stamps `build` for every non-skipped child before the last non-skipped manifest entry, and `validate` for that last child; skipped ordinal-last promotes validation, while single-child and non-goal launches keep `validate`.
- Child loop and status carry the active build selection; build-stamped visits skip collect-all validation and settle into write-history.
- Write-history and commit-push handoffs require exactly the selected settled receipt (`build_receipt` or `validation_receipt`); legacy or absent selection resolves to `validate`.
- Pattern: persist gate choice on goal-continuation context and validate the selected receipt at the downstream phase edge. reusable
- Limitation: intermediate build children defer full validation debt to the final non-skipped child.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-08-20] SKILL-190 — Completed-upstream-missing-output child repair wedge
Areas: runtime-application/goalrunner, runtime-application/featuretask, runtime-cli/goal
- `GoalRunnerChildRepairOperations` diagnoses and applies `COMPLETED_UPSTREAM_MISSING_OUTPUT`: earliest unsettled `completed` upstream for a blocked consumer is reopened to `pending` with ledger retry evidence
- Repair apply now runs inside `UnitOfWork`, returns `GoalRunnerChildRepairApplyResult`, and projects parent manifest state through `updateGoalParentForBlockedPhaseRetry` when a validator is wired
- `WorkflowGoalRunnerOutcomeStore.applyChildWedgeRepairs` persists manifest projection artifacts to the decomposition file after repair
- CLI `goal repair` help documents the wedge alongside continuation-outcome and validation-depth repairs
- Reusable: wedge apply result that separates applied repairs from optional manifest projection side effects
- Limitation: wedge does not amend commits, rewrite review history, or replace goal reset/replan/accept
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-08-11] SKILL-181 subtask 4 — Distinct exit codes and truthful planning stops
Areas: runtime-application/goalrunner, runtime-cli/goal, runtime-domain/goalrunner model, runtime-infra-sqlite/db/workflow, runtime-core/di, skills/bill-feature-goal
- Goal run exit codes: complete=0, failed/timeout=1, paused=2, blocked=3 (CLI help + bill-feature-goal)
- Incompatible-provenance stops name `replan --include-shared-preplan` via shared remedy builders; drop "cannot be recovered" wording
- Status `planning_reason` overlays launch Invalid recoverability so resume claims disappear when launch would refuse
- Patterns: `goalRunExitCode`; `GoalPlanningOperatorRemedies`; `LaunchAlignedGoalPlanningStatusReasonCoherence` + `GoalPlanningStatusReasons`
- Reusable: remedy string helpers and status-reason coherence seam for other planning stops
- Limits: pause/resume control semantics unchanged; StaleValid still refreshable in-run (no status overlay)
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-08-11] SKILL-181 subtask 3 — Terminal-with-commit plan cascade exclusion
Areas: runtime-application/goalrunner + workflow, runtime-ports/persistence, runtime-infra-sqlite/db/workflow, runtime-cli/goal, skills/bill-feature-goal, runtime-kotlin/agent/decisions.md
- Shared eligibility helper: cascade only when manifest subtask is not (`complete` + non-blank `commit_sha`); used by scoped replan `--include-shared-preplan` and heading-set refresh replace
- Soft-invalidate shared preplan when survivors remain (FK ON DELETE CASCADE must not wipe terminal plan rows); delete only when no retained plans
- `replaceSharedPreplan` deletes an explicit cascade id list then restamps remaining plan provenance in the same transaction; relaunch regeneration restamps survivors without re-cascading
- Patterns: `cascadeEligiblePlanSubtaskIds` / `isTerminalWithCommitPlan`; `invalidateSharedPreplan`; `restampSubtaskPlanProvenance`
- Limits: exit codes / `planning_reason` remain subtask 4; scoped replan without `--include-shared-preplan` still deletes only the named plan
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-08-11] SKILL-181 subtask 2 — In-run stale-valid preplan refresh
Areas: runtime-application/goalrunner + workflow, runtime-ports/persistence, runtime-infra-sqlite/db/workflow, runtime-core/di, .feature-specs/SKILL-181-preplan-provenance-refresh
- StaleValid no longer stops prepare: one in-run shared-preplan refresh per launch, gated by child-aware liveness (parent prepare lease treated as IDLE so it cannot self-refuse)
- Heading-set equality on `selected_boundary_headings` decides outcome: same set advances provenance only and keeps payload bytes + all sibling plans; changed set adopts the new payload and discards via shared cascade helper
- Atomic refresh persist: `replaceSharedPreplanForRefresh` leaves either the prior valid record or the new one — never a provenance/payload mismatch mid-crash
- Patterns: `GoalPlanningRefreshLiveness` / `ChildAwareGoalPlanningRefreshLiveness`; `refreshedThisPrepare` latch; cascade only through `cascadeSiblingPlansAfterSharedPreplanRefresh` (reusable seam for ST3 terminal filter)
- Limits: cascade filtered by terminal-with-commit exclusion in subtask 3; exit codes / `planning_reason` remain subtask 4; explicit `replan --include-shared-preplan` still force-regenerates
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-11] SKILL-181 subtask 1 — Validity vs freshness provenance gate
Areas: runtime-application/goalrunner, .feature-specs/SKILL-181-preplan-provenance-refresh
- Replaced single equality `recoverableProvenance` with `classifyGoalPlanningProvenanceRecoverability`: Valid (manifest hash, phase-output schema id, parent-spec self-hash, payload sha, selected heading ids in the fresh model-free catalog) vs Fresh (canonical parent-spec equality)
- Outcomes: Reuse when valid+fresh; StaleValid keeps the checkpoint and continues prepare without `incompatibleProvenance` (in-run refresh is subtask 2); Invalid still loud-stops at preplan / subtask 0
- Pattern: never short-circuit on provenance equality alone — payload integrity and heading resolution always run when a checkpoint exists; catalog ids come from `contextDiscovery`, not the recovered packet
- Reusable: `GoalPlanningProvenanceRecoverability` sealed result (`Reuse` / `StaleValid` / `Invalid`) and `selectedBoundaryHeadingIds` helper shared with body resolution
- Limitation: StaleValid does not yet re-run preplan, cascade, CLI replan, exit codes, or `planning_reason`
Feature flag: N/A
Acceptance criteria: 5/5 implemented
