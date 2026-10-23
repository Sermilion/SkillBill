# SKILL-435 subtask 3: typed, fenced, user-initiated incident recovery

## Scope

Let a user apply the recovery action a saved diagnosis proposes, safely and exactly once, and then resume the exact saved goal or standalone phase through the existing launch path. Recovery is always user initiated; nothing in diagnosis, reconciliation or reporting starts it.

This subtask covers intake paragraphs 6 and 7, which make up parent criterion AC-002. The recover command and control action are the runtime surface; subtask 5 presents them in the terminal and desktop.

The incident summary enum `BlockIncidentRecovery {UNAVAILABLE, NOT_STARTED, COMPLETE, FAILED}` keeps its members so `block-incident-schema.yaml` stays at `0.2`. Detailed progress lives in the new attempt record.

## Acceptance Criteria

1. A pure eligibility policy decides whether recovery is offered and builds the preview shown before activation.
   A new object `BlockIncidentRecoveryEligibility` in `runtime-domain/.../model/blockincident/` takes the incident, its latest `BlockIncidentDiagnosisRecord` and a `BlockIncidentRecoveryFacts` value (current workflow status, current attempt and generation, attempt-limit remaining, whether an operator decision or mandate forbids retry, whether a quality or acceptance gate failure caused the block, confirmed prerequisite kinds). It returns `Available(preview)`, `NeedsPrerequisites(instructions)` or `Unavailable(reason)`.
   `Unavailable` reasons: `NO_SUPPORTED_ACTION` (`proposedRecoveryAction` is `NONE`), `INVESTIGATION_NOT_SETTLED` (diagnosis `PENDING` or `INVESTIGATING`), `NOT_BLOCKED` (workflow no longer `BLOCKED`), `GENERATION_CHANGED` (attempt or generation differs from the occurrence), `ATTEMPT_LIMIT_REACHED`, `OPERATOR_DECISION`, `QUALITY_GATE`. Recovery never bypasses the attempt limit, an operator decision, a quality gate or an acceptance requirement; those blocks stay unrecoverable here.
   `NeedsPrerequisites` lists each unconfirmed prerequisite with its kind and instruction text. All prerequisites in this subtask use check `USER_CONFIRMED`: the user confirms them, and the action itself proves them by succeeding or failing.
   The preview holds the action wire value, a label (`Reopen blocked phase` or `Retry after prerequisite`), the diagnosis `recoveryEffects`, the target (goal workflow id or standalone invocation id and phase id) and the incident token and revision the user is acting on.
   Execution is criterion 3. Audit confirms by reading the policy and its reason list.
2. Recovery attempts persist with a durable idempotency key, progress states and outcome, and the incident summary reflects them.
   New domain types `BlockIncidentRecoveryAttempt` and enums `BlockIncidentRecoveryAttemptStatus {REQUESTED, APPLYING, APPLIED, RESUMING, RESUMED, FAILED}` and `BlockIncidentRecoveryFailure {STALE_INCIDENT, PREREQUISITE_UNMET, OWNERSHIP_MISMATCH, ACTION_FAILED, BLOCK_NOT_CLEARED, RESUME_FAILED, INTERRUPTED}` live in new files in the domain blockincident package; wire values go in the runtime-contracts blockincident keys file.
   A new append-only migration (next free version, expected 78) creates `block_incident_recovery_attempts` with `attempt_id` (`bra_<uuid>`), `occurrence_token`, `action`, `incident_revision`, `idempotency_key` UNIQUE (sha256 of `<token>:<action>:<incident revision>`), `status`, `failure`, `owner_token`, `owner_generation`, `owner_expires_at`, `requested_at`, `updated_at`, `target_workflow_id` and `target_invocation_id`, with desktop change-revision triggers and a `DesktopReadSchema.VERSION` bump if its rule requires one.
   A new port `BlockIncidentRecoveryRepository` in `runtime-ports/.../blockincident/`, exposed as `UnitOfWork.blockIncidentRecoveries`, offers `insertIfAbsent(attempt): BlockIncidentRecoveryAttempt` (returns the existing row on key collision), `transition(attemptId, expectedStatus, newStatus, failure, ownerToken)`, `latest(token)`, `history(token)` and `interrupted(now, limit)`. `BlockIncidentRepository` gains `recordRecoveryOutcome(token, expectedRevision, recovery: BlockIncidentRecovery)`. Sqlite implementations and testFixtures in-memory versions exist for both.
   Audit confirms by reading the migration, the unique key, the port and both implementations.
3. The recovery use case revalidates every precondition under fencing, applies one catalog action through existing typed primitives, and verifies the block cleared.
   A new `BlockIncidentRecoveryService` in `runtime-engine/.../engine/diagnostics/block/recovery/` runs inside `database.transactionFenced(DatabaseFileObservation, op)`. It reloads the incident and rejects `STALE_INCIDENT` when the revision differs from the request; reloads the workflow and rejects when it is not `BLOCKED` or its attempt or generation differs from the occurrence; checks live ownership through `unit.goalRunnerControls.controlState(id).executionLease` and the active worker lease and rejects `OWNERSHIP_MISMATCH` when another live owner holds either; and evaluates criterion 1's policy with the user's confirmed prerequisites.
   It inserts the attempt as `REQUESTED`, moves it to `APPLYING` with its owner token before any mutation, then applies the action. `REOPEN_BLOCKED_PHASE` and `RETRY_AFTER_PREREQUISITE` both call `WorkflowGoalRunnerBlockWrites.reopenBlockedPhaseForOperatorResume(unitOfWork, preferredPhaseId, reason, expectedIdentity, expectedExecutionPlan)` for goal-owned feature-task phases and `FeatureTaskRuntimeOperatorBlockRetry` for standalone feature-task phases, so completed phases, attempt counters, mandates, checkpoints and other active runs are untouched. No shell command, SQL text or model output is executed.
   After the action, it rereads the workflow. Not `BLOCKED` moves the attempt to `APPLIED`; still `BLOCKED` moves it to `FAILED` with `BLOCK_NOT_CLEARED`; an exception moves it to `FAILED` with `ACTION_FAILED` in a separate transaction. A failed attempt sets the incident summary to `FAILED` and leaves the occurrence and diagnosis unchanged.
   Resume after `APPLIED` is criterion 5. Audit confirms by reading the service's checks in order and the action dispatch.
4. Repeated, concurrent and interrupted recovery requests never double-apply an action or double-launch a resume.
   A request whose idempotency key already exists returns the existing attempt and its current status without applying anything. A request while another attempt for the same incident is `REQUESTED`, `APPLYING` or `RESUMING` returns rejection `RECOVERY_IN_PROGRESS`. A request at an older incident revision returns `STALE_INCIDENT`. The fenced transaction plus the unique key make two app windows, a double click and a concurrent terminal command converge on one attempt.
   `BlockIncidentEntryReconciler` calls `BlockIncidentRecoveryService.reconcileInterrupted()` at entry. For each attempt in `APPLYING` or `RESUMING` whose `owner_expires_at` has passed, it rereads the workflow: when the block cleared and the target run is active or completed it moves the attempt to `APPLIED` or `RESUMED`; otherwise it moves it to `FAILED` with `INTERRUPTED`. It never reapplies the action and never launches a resume; the user can request recovery again on the new revision.
   Audit confirms by reading the key collision path, the in-progress check and the interrupted reconciliation.
5. A typed recover control and terminal command apply recovery and resume the exact saved target through the supported launch path.
   `WorkControlAction` in `engine/work/control/model/WorkControlModels.kt` gains `RECOVER`, and `WorkControlRejection` gains `STALE_INCIDENT`, `RECOVERY_UNAVAILABLE`, `PREREQUISITE_UNMET` and `RECOVERY_IN_PROGRESS`. `WorkControlService` gains `recover(target: WorkRunTarget, incidentToken, incidentRevision, confirmedPrerequisites, agentId): WorkControlResult` and `recoveryPreview(target, incidentToken): BlockIncidentRecoveryEligibility result`, both delegating to `BlockIncidentRecoveryService` so no policy lives in the control service.
   After `APPLIED`, `recover` moves the attempt to `RESUMING`, calls `WorkRunActions.launch(target, agentId)` for the goal workflow or standalone phase invocation recorded on the attempt, and moves it to `RESUMED` and the incident summary to `COMPLETE` on success, or to `FAILED` with `RESUME_FAILED` on `WorkControlResult.LaunchFailed`. On `RESUME_FAILED` the block is already cleared, so the normal launch remains available.
   `runtime-cli` `WorkCliCommands` adds `skill-bill work recover <work-id> --incident <token> --revision <n> [--confirm-prerequisite <kind>]... [--agent <id>] [--preview]`. `--preview` prints the action, effects and unmet prerequisites without mutating; without it the command prints the attempt id and final status. `../../../docs/runtime-command-guidance.md` documents the command, the incident states and the rejection codes.
   Audit confirms by reading the control model, the service delegation, the resume step and the CLI command.

## Criterion Sizing

1. About 180 production lines, 3 files, 1 area (`runtime-domain` blockincident). Fits.
2. About 290 production lines, 7 files, 3 areas (`runtime-domain` blockincident, `runtime-ports` blockincident and persistence, `runtime-infra/sqlite`), with keys in the runtime-contracts file subtask 2 created. Fits.
3. About 260 production lines, 3 files, 2 areas (`runtime-engine` diagnostics/block recovery, `runtime-engine` goal-runner persist for primitive visibility only). Fits.
4. About 130 production lines, 2 files, 1 area (`runtime-engine` diagnostics/block). Fits.
5. About 240 production lines, 4 files, 3 areas (`runtime-engine` work/control, `runtime-cli` work, `docs`). Fits.

## Decisions and assumptions

Both catalog actions reuse the existing operator-resume primitives; they differ in preview and prerequisites. Implement confirms the exact signatures and that `FeatureTaskRuntimeOperatorBlockRetry` serves standalone phases; if it only serves goal-owned phases, standalone recovery returns `RECOVERY_UNAVAILABLE` with reason `NO_SUPPORTED_ACTION` and the classifier stops proposing it for standalone phases. Runtime-checkable prerequisites are deferred until a check exists; the enum member stays so a later probe needs no contract change.

## Non-Goals

No automatic recovery. No arbitrary shell, SQL or model-generated repair. No bypass of attempt limits, gates, acceptance, operator decisions or ownership checks. No independent UI recovery coordinator; the desktop calls this runtime surface in subtask 5. No reports or telemetry (subtask 4).

## Dependency Notes

Depends on subtask 1 (incident lifecycle, reconciler) and subtask 2 (diagnosis record, recovery action catalog). Subtask 4 reads attempt outcomes into reports and telemetry. Subtask 5 presents the preview, progress and history and maps the desktop control.

## Test Obligations

1. T3.1 successful recovery. A blocked goal phase with a `REOPEN_BLOCKED_PHASE` diagnosis recovers, the attempt reaches `RESUMED`, `WorkRunActions.launch` receives the saved target, and completed phases and attempt counters are unchanged. Extend `WorkControlServiceTest`. Realistic bug: the resume launches a different subtask or resets completed work.
2. T3.2 stale, concurrent and repeated. A request at an old revision returns `STALE_INCIDENT`; two requests with the same key yield one attempt and one launch; a request while `APPLYING` returns `RECOVERY_IN_PROGRESS`. Realistic bug: a double click launches the goal twice.
3. T3.3 failed and interrupted recovery. An action that leaves the workflow `BLOCKED` ends `FAILED` with `BLOCK_NOT_CLEARED` and keeps the original incident and diagnosis; an `APPLYING` attempt with an expired owner and a still-blocked workflow is reconciled to `FAILED` with `INTERRUPTED` and no action is reapplied. Realistic bug: restart repeats a partly applied action.
4. T3.4 eligibility. An attempt-limit-reached or operator-decision block returns `Unavailable` and recovery is refused. Realistic bug: recovery bypasses the attempt limit.
5. Migration upgrade evidence in `DatabaseMigrationsTest` preserving existing rows.

## Implementation Details

Tasks run in order. Each one names the criteria it serves, the paths or symbols it touches, its tests and its constraints. Paths abbreviate `runtime-kotlin/<module>/src/main/kotlin/` as `<module>/`.

### Task 1. Recovery domain types and wire keys (criterion 2)

- New files in `runtime-domain/.../skillbill/workflow/taskruntime/model/blockincident/`:
  - `BlockIncidentRecoveryAttemptStatus.kt` with `REQUESTED, APPLYING, APPLIED, RESUMING, RESUMED, FAILED` and a companion `IN_PROGRESS = setOf(REQUESTED, APPLYING, RESUMING)` plus `OWNED = setOf(APPLYING, RESUMING)`.
  - `BlockIncidentRecoveryFailure.kt` with `STALE_INCIDENT, PREREQUISITE_UNMET, OWNERSHIP_MISMATCH, ACTION_FAILED, BLOCK_NOT_CLEARED, RESUME_FAILED, INTERRUPTED`.
  - `BlockIncidentRecoveryAttempt.kt`, a data class with `attemptId` (`bra_<uuid>`), `occurrenceToken`, `action` (the subtask 2 recovery action enum), `incidentRevision`, `idempotencyKey`, `status`, `failure: BlockIncidentRecoveryFailure?`, `ownerToken`, `ownerGeneration`, `ownerExpiresAt`, `requestedAt`, `updatedAt`, `targetWorkflowId`, `targetInvocationId?`. A companion `idempotencyKey(token, action, revision)` returns sha256 hex of `<token>:<action wire>:<revision>`, reusing whatever sha256 helper `BlockIncidentService` uses for the occurrence token.
  - Allowed status transitions live in one pure function `BlockIncidentRecoveryAttemptStatus.canMoveTo(next)` (REQUESTED to APPLYING or FAILED; APPLYING to APPLIED or FAILED; APPLIED to RESUMING or FAILED; RESUMING to RESUMED or FAILED). Repositories call it so the transition rule has one owner.
- Wire values for both enums go in the runtime-contracts blockincident keys file subtask 2 created (alongside `BlockIncidentOccurrenceKeys.kt`). Enums carry `wire` properties resolved from those constants, matching the existing `BlockIncidentDiagnosis` idiom. `WireVocabularyArchitectureTest` forbids inline wire strings.
- Do not touch `BlockIncident.kt` (598 lines, near the line ceiling) or `BlockIncidentRecovery`'s members. `block-incident-schema.yaml` stays `0.2`. The attempt row is a sqlite record, not a contract document, so no new schema file is needed.
- Tests: none for this task alone. The transition rule is exercised by T3.2 and T3.3.

### Task 2. Recovery repository port and fixtures (criterion 2)

- New `runtime-ports/.../skillbill/ports/blockincident/BlockIncidentRecoveryRepository.kt` with KDoc on the interface and each method (`CommentAndInterfaceKdocArchitectureTest`):
  - `insertIfAbsent(attempt): BlockIncidentRecoveryAttempt` returns the stored row; on idempotency-key collision it returns the existing row unchanged.
  - `transition(attemptId, expectedStatus, newStatus, failure, ownerToken, ownerExpiresAt, now): Boolean`, a compare-and-set on `status` (and on `owner_token` when the attempt is owned). Returns false on mismatch.
  - `inProgress(token): BlockIncidentRecoveryAttempt?`, `latest(token)`, `history(token): List<...>` (newest first, bounded) and `interrupted(now, limit)` (status in `APPLYING`/`RESUMING` with `owner_expires_at < now`).
  - `inProgress` is an addition to the listed methods. The in-progress check in criterion 4 needs it; it is a narrow read over the same table.
- `BlockIncidentRepository` gains `recordRecoveryOutcome(token, expectedRevision, recovery: BlockIncidentRecovery): Boolean`, a revision CAS that sets `state.recovery` and bumps `revision`. It never touches occurrence, aliases or diagnosis.
- `ports/persistence/UnitOfWork.kt` gains `val blockIncidentRecoveries: BlockIncidentRecoveryRepository`. Update `testFixtures` `UnitOfWorkDefaults.kt`, add `InMemoryBlockIncidentRecoveryRepository.kt` (unique key enforced with a map keyed on idempotency key) and extend `InMemoryBlockIncidentRepository` with `recordRecoveryOutcome`.

### Task 3. Sqlite migration and repository (criterion 2)

- New migration class in `runtime-infra/sqlite/.../core/migration/area/` (for example `BlockIncidentRecoveryAttemptsMigration`), registered at the next free version after subtasks 1 and 2. The spec expects 78. Assumption for implement to confirm: subtasks 1 and 2 take 76 and 77; use whatever number follows the last registered migration, append only, and never edit 66 to 77 (decision D1).
- DDL: table `block_incident_recovery_attempts` with the columns listed in criterion 2, `attempt_id` PRIMARY KEY, `idempotency_key TEXT NOT NULL UNIQUE`, `occurrence_token` referencing `block_incidents`, and an index on `(occurrence_token, status)` plus one on `(status, owner_expires_at)` for `interrupted`.
- Desktop change revisions: add a revision-trigger helper beside `addBlockIncidentRevisionTriggers` in `core/schema/DesktopChangeRevisions.kt` and call it from the migration so the desktop sees progress changes. Bump `DesktopReadSchema.VERSION` in `runtime-contracts/.../persistence/DesktopReadSchema.kt` only if its rule ties the version to new desktop-visible tables. The digest shows the 66 migration bumped it for `block_incidents`, so the assumption is that a bump is required here too; implement confirms against the rule text.
- New `SqliteBlockIncidentRecoveryRepository.kt` in `runtime-infra/sqlite`. `insertIfAbsent` uses `INSERT ... ON CONFLICT(idempotency_key) DO NOTHING` then selects by key. `transition` is one `UPDATE ... WHERE attempt_id = ? AND status = ?` (plus owner token when owned) and checks the row count. Status and failure decode through the contract wire values; an unknown wire value fails loudly with `BlockIncidentFailureCode.INVALID_SCHEMA`, matching the existing codec's parse seam.
- Implement `recordRecoveryOutcome` in `SqliteBlockIncidentRepository` as a revision-guarded `UPDATE`. That file is 651 lines; if the addition pushes it past `ProductionFileLineCeilingArchitectureTest`, put the statement in a small sibling file (for example `SqliteBlockIncidentStateWrites.kt`) called from the repository.
- Wire both into `SqliteRepositories.kt` and the sqlite `UnitOfWork` implementation.
- Tests: extend `DatabaseMigrationsTest` with upgrade evidence (obligation 5): a database at the previous version holding a `block_incidents` row and an existing workflow upgrades, keeps both rows, and the new table exists with its unique key. Add one case to a new `SqliteBlockIncidentRecoveryRepositoryTest`: two `insertIfAbsent` calls with the same key return the same `attempt_id` and leave one row. Realistic bug: the UNIQUE constraint or the conflict clause is missing, so a double click writes two attempts.

### Task 4. Eligibility policy (criterion 1)

- New `BlockIncidentRecoveryEligibility.kt` (object) and `BlockIncidentRecoveryFacts.kt` in the domain blockincident package. Results are a sealed `BlockIncidentRecoveryEligibilityResult { Available(preview), NeedsPrerequisites(instructions), Unavailable(reason) }`, with `BlockIncidentRecoveryPreview` and `BlockIncidentRecoveryUnavailableReason` in the same new file set (no growth of `BlockIncident.kt`).
- Evaluation order, first match wins: `INVESTIGATION_NOT_SETTLED` (diagnosis `PENDING`/`INVESTIGATING`, or no diagnosis record), `NO_SUPPORTED_ACTION` (`proposedRecoveryAction` is `NONE`), `NOT_BLOCKED`, `GENERATION_CHANGED`, `ATTEMPT_LIMIT_REACHED`, `OPERATOR_DECISION`, `QUALITY_GATE`. Then unconfirmed prerequisites give `NeedsPrerequisites`. Otherwise `Available`.
- Settled assumption: `QUALITY_GATE` also covers an acceptance-gate failure (the facts flag is one boolean `gateFailureCausedBlock`). `BlockIncidentRecoveryFacts` is built by the engine (Task 5) from the workflow state and the incident's `failureCode` and `failureDisposition`; the domain never reads stores.
- Preview fields: action wire value, label (`Reopen blocked phase` for `REOPEN_BLOCKED_PHASE`, `Retry after prerequisite` for `RETRY_AFTER_PREREQUISITE`), diagnosis `recoveryEffects`, target (goal workflow id, or standalone invocation id plus phase id), incident token and revision.
- Prerequisite checks: every prerequisite in the subtask 2 record with check `USER_CONFIRMED` is satisfied only when its kind is in the confirmed set. Any other check kind is treated as unmet with its instruction text, because no runtime probe exists yet.
- Tests: `BlockIncidentRecoveryEligibilityTest` covering T3.4. One case per guard that protects a rule the spec says recovery never bypasses: attempt limit reached returns `Unavailable(ATTEMPT_LIMIT_REACHED)`, and an operator decision returns `Unavailable(OPERATOR_DECISION)`. One case that unconfirmed prerequisites return `NeedsPrerequisites`. No per-reason sibling tests beyond these.

### Task 5. Recovery service: validate, apply, verify (criterion 3)

- New `@Inject` class `BlockIncidentRecoveryService` in `runtime-engine/.../skillbill/engine/diagnostics/block/recovery/`, plus `BlockIncidentRecoveryFactsReader.kt` (builds `BlockIncidentRecoveryFacts` from `unit.workflowStates` and the phase ledger) and `BlockIncidentRecoveryActions.kt` (action dispatch). Constructor deps: `DatabaseSessionFactory`, `Clock`, the existing identifier generator port (`ports/process` `IdentifierGenerator`) for attempt ids and owner tokens, and `RuntimeDiagnostics`. Composition only through `skillbill.di.core.RuntimeComponent`.
- `preview(target, incidentToken)` reads the incident, latest diagnosis record and facts in one read transaction and returns the eligibility result. It never mutates.
- `apply(request)` where `request` carries target, token, revision, confirmed prerequisites. Inside `database.transactionFenced(DatabaseFileObservation, op)`, in this order:
  1. Compute the idempotency key. If `blockIncidentRecoveries` already has that key, return the existing attempt without applying (criterion 4).
  2. Reload the incident; revision differs from the request gives `STALE_INCIDENT`.
  3. `inProgress(token)` non-null gives `RECOVERY_IN_PROGRESS`.
  4. Reload the workflow and build facts. Ownership: `unit.goalRunnerControls.controlState(id).executionLease` held by a live owner, or a live ACTIVE worker lease on the target, gives `OWNERSHIP_MISMATCH`. "Live" means unexpired at `clock.now()`, using the same expiry test the control service uses.
  5. Evaluate the policy. `Unavailable` maps to `RECOVERY_UNAVAILABLE` with the reason; `NeedsPrerequisites` maps to `PREREQUISITE_UNMET` with the instructions.
  6. `insertIfAbsent` as `REQUESTED`, then `transition` to `APPLYING` with a fresh owner token and `ownerExpiresAt = now + RECOVERY_OWNER_LEASE`. Assumption: a 2 minute lease, a private constant in the service; implement may align it with an existing lease constant if one fits.
  7. Dispatch the action. Goal-owned feature-task phase: `WorkflowGoalRunnerBlockWrites.reopenBlockedPhaseForOperatorResume(unitOfWork, preferredPhaseId = occurrence.phaseId, reason, expectedIdentity, expectedExecutionPlan)` with identity and plan read from current state. Standalone feature-task phase: `FeatureTaskRuntimeOperatorBlockRetry`. Widen visibility of those symbols only as far as the new package needs (criterion 3's "primitive visibility only").
  8. Reread the workflow. Not `BLOCKED` moves the attempt to `APPLIED`. Still `BLOCKED` moves it to `FAILED`/`BLOCK_NOT_CLEARED` and calls `recordRecoveryOutcome(FAILED)`.
  On `FencedTransactionResult.ChangedTarget` return `CHANGED_TARGET` (existing rejection). If the action throws, the fenced transaction rolls back; then a second transaction inserts or moves the attempt to `FAILED`/`ACTION_FAILED` and records the incident summary `FAILED`. The thrown error is logged through `RuntimeDiagnostics`, never rethrown into a new block observation (no `observeBlock` from this path).
- Confirm during implement: the exact signatures of the two primitives, and that `FeatureTaskRuntimeOperatorBlockRetry` serves standalone phases. If it only serves goal-owned phases, follow the spec decision: standalone recovery returns `RECOVERY_UNAVAILABLE`/`NO_SUPPORTED_ACTION` and the subtask 2 classifier stops proposing an action for standalone origins.
- Constraint: no shell, SQL text from the diagnosis, or model output is executed. The action enum is the only dispatch key. Completed phases, attempt counters, mandates and checkpoints stay as the primitive leaves them.
- Tests: `BlockIncidentRecoveryServiceTest` (new, engine test source set, using the in-memory fixtures or the existing goal-runner test factory). T3.3 first half: an action that leaves the workflow `BLOCKED` ends `FAILED`/`BLOCK_NOT_CLEARED`, and the incident occurrence and diagnosis read back unchanged.

### Task 6. Idempotency, concurrency and interrupted reconciliation (criterion 4)

- The key-collision short-circuit and the in-progress check are steps 1 and 3 of Task 5. The fenced transaction serializes concurrent callers; the UNIQUE key is the backstop when two processes race.
- `BlockIncidentRecoveryService.reconcileInterrupted(limit)`: for each row from `interrupted(now, limit)`, reread the workflow in one transaction. Block cleared and the target run active or completed: move `APPLYING` to `APPLIED` or `RESUMING` to `RESUMED` (the latter also records incident `COMPLETE`). Otherwise move to `FAILED`/`INTERRUPTED` and record incident `FAILED`. Never call the action dispatch or `WorkRunActions.launch` here. Settled assumption: an `APPLYING` row whose block cleared but no run is active stays `APPLIED`, so the user's next `recover` with the same key returns it and resumes (Task 7 resumes an existing `APPLIED` attempt owned by the caller after taking a new owner token).
- `BlockIncidentEntryReconciler` gets the service injected and calls `reconcileInterrupted(16)` after its existing batch, in its own try block that only warns on failure, like the existing batch. A reconciliation failure must not stop goal, run or phase entry.
- Tests: in `BlockIncidentRecoveryServiceTest`, T3.3 second half: an `APPLYING` attempt with an expired owner and a still-blocked workflow reconciles to `FAILED`/`INTERRUPTED`, and the action primitive is not invoked (assert on the workflow state being unchanged, not on a mock call). T3.2 at the service level: an old revision gives `STALE_INCIDENT`; a request while another attempt is `APPLYING` gives `RECOVERY_IN_PROGRESS`.

### Task 7. Recover control, resume, CLI and docs (criterion 5)

- `engine/work/control/model/WorkControlModels.kt`: add `RECOVER` to `WorkControlAction` and `STALE_INCIDENT`, `RECOVERY_UNAVAILABLE`, `PREREQUISITE_UNMET`, `RECOVERY_IN_PROGRESS` to `WorkControlRejection`. Add a `WorkControlResult.Recovered(attemptId, status)` variant only if the existing `Accepted` cannot carry the attempt id, and update every runtime `when` over `WorkControlResult`. The desktop builds against the installed runtime, so mapping a new variant in `RuntimeWorkAdapter` is subtask 5's work.
- `WorkControlService` gets `BlockIncidentRecoveryService` injected and adds `recoveryPreview(target, incidentToken)` and `recover(target, incidentToken, incidentRevision, confirmedPrerequisites, agentId)`. Both only delegate and map service outcomes to `WorkControlRejection`/`WorkControlResult`. If the file nears the line ceiling, put the mapping in `WorkControlRecovery.kt` beside it.
- Resume step, in `BlockIncidentRecoveryService.resume(attempt, agentId, launch: (WorkRunTarget) -> WorkControlResult)` called by `recover` after `APPLIED`: transition `APPLIED` to `RESUMING` (CAS, new owner expiry) in its own transaction, call `WorkRunActions.launch(target, agentId)` outside any transaction with the target rebuilt from `targetWorkflowId`/`targetInvocationId` on the attempt (not from the request), then in a new transaction move to `RESUMED` plus incident `COMPLETE`, or on `LaunchFailed` to `FAILED`/`RESUME_FAILED` plus incident `FAILED`. Passing `launch` as a function keeps the engine diagnostics package from depending on the work-control package cycle; implement may instead inject `WorkRunActions` directly if no cycle results.
- `runtime-cli`: add the `recover` subcommand to `WorkCliCommands`. Put the command body in a new `cli/work/WorkCliRecoverCommand.kt` because `WorkCliCommands.kt` is already 318 lines. Flags: `--incident`, `--revision`, repeatable `--confirm-prerequisite`, `--agent`, `--preview`. Preview prints action, label, effects, target and unmet prerequisites. Apply prints attempt id and final status, or the rejection code and reason, with a non-zero exit on rejection using the existing work CLI exit mapping. CLI option names and printed status words come from the contract wire values.
- `../../../docs/runtime-command-guidance.md`: document `skill-bill work recover`, the attempt statuses, the failure values and the four new rejection codes.
- Tests: extend `WorkControlServiceTest` with T3.1 (blocked goal phase with a `REOPEN_BLOCKED_PHASE` diagnosis recovers to `RESUMED`, the launch receives the saved workflow id, and completed phases and attempt counters read back unchanged) and the T3.2 double-request case (two `recover` calls with the same token and revision yield one attempt and one launch, counted by a fake launcher's observable launch list). No CLI test: the command is argument parsing over the tested service, and a wrong flag name fails visibly on first use.

### Constraints for every task

- Recovery is only user initiated. Nothing in the investigation worker, reconcilers or reporting calls `apply` or `resume`.
- Single transition owner: only `BlockIncidentRecoveryService` writes attempt status and the incident recovery summary.
- No new generic scheduler, no UI-side coordinator, no install or install-sync steps.
- Respect `InlineFqnArchitectureTest`, `WireVocabularyArchitectureTest`, `RuntimeCompositionGuardArchitectureTest`, `CommentAndInterfaceKdocArchitectureTest` and `ProductionFileLineCeilingArchitectureTest`. New logic goes in new files.
- Implement and audit do not run builds, tests or checks; validate owns them.

## Validation Strategy

Implement and audit read the tree only. Validate runs runtime-kotlin `./gradlew check --no-build-cache` and `../../../scripts/validate_agent_configs`, including `WireVocabularyArchitectureTest`, `RuntimeCompositionGuardArchitectureTest` and `ProductionFileLineCeilingArchitectureTest`. Review applies architecture-guidelines section 5 with rule IDs, especially single transition owner and fencing.

## Next path

```bash
skill-bill goal SKILL-435
```
