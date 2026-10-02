# SKILL-398 Subtask 2 - operation-diagnostic-and-phase-write-results

Parent spec: [.feature-specs/SKILL-398-runtime-exception-reduction/spec.md](spec.md)
Issue key: SKILL-398

## Scope

(F-003) Three families report expected outcomes by throwing and are turned back into results a few frames up. Make them return the result directly.

**Operations** (`../../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/operation`, and `OperationUsageError` wherever it is declared, at the census tree `runtime-contracts/.../error/operation/OperationUsageError.kt`).

- `Operation.pre`, the confirmation gate and every operation's `run`/`execute` report a refusal (today `OperationRefusalError` and its 17 subclasses: unknown, consumed, superseded or foreign token; moved or unreadable anchors; PR not found or not checked out; protected branch; dirty worktree; release branch behind; verify workflow unknown, foreign or closed; spec rehydrate needed; verify target not checked out) or a usage problem (today `OperationUsageError` and its 9 subclasses) as a value. Use one sealed type, for example `sealed interface OperationRefusal { Blocked(message); Usage(message) }`, returned from `pre` (null means proceed) and carried by `OperationOutcome` / `OperationRunResult` where `run` or confirmation can refuse. Reuse `OperationOutcome.Blocked` for the blocked case.
- `OperationExecutor` has no `catch` of an operation failure. `runtime-cli/.../operation/OperationCommand.kt` maps the usage value to the same output and exit code it produces today for `OperationUsageError`.
- Update the KDoc at `Operation.kt:16-17` to describe the returned refusal.
- `DuplicateOperationIdError` is a registry wiring defect: replace it with `require`/`check` in `OperationRegistry`, same message text.
- Delete every operation error class. Messages stay byte-identical.

**Rejected-output diagnostics.**

- `runtime-ports/.../diagnostics/RejectedOutputDiagnosticRepository.kt`: `read` returns a sealed read result (found, absent, expired, oversized) instead of throwing `Absent`/`Expired`/`Oversized`; `insert` returns a sealed insert result (inserted, conflict) instead of throwing `Conflict`. Adapt `runtime-infra/sqlite/.../SqliteRejectedOutputDiagnosticRepository.kt`, `runtime-application/.../diagnostics/RejectedOutputDiagnosticService.kt`, `RejectedOutputDiagnosticInspection.kt`, `runtime-cli/.../featuretask/RejectedOutputCommands.kt` and `runtime-engine/.../lifecycle/core/FeatureTaskRuntimeRejectedOutputRecorder.kt` to branch on those values.
- `InvalidRequest` and `RejectedOutputDiagnosticAmbiguousSelectorError` are invalid caller input: the service returns them as values to its callers.
- `Persistence`, `Permission`, `Corrupt`, `Retrieval` and `InvalidConfiguration` are I/O or durable-state failures (tier 3): throw `SkillBillRuntimeException` with an entry of a `RejectedOutputDiagnosticFailureCode` enum declared beside the port's owner vocabulary. The recorder's degrade path catches `SkillBillRuntimeException`, maps `code is RejectedOutputDiagnosticFailureCode` to its `FeatureTaskRuntimeDiagnosticFailureClass` exactly as `degradableFailureClass()` does today, and rethrows any other code.
- Delete `RejectedOutputDiagnosticError` and `RejectedOutputDiagnosticAmbiguousSelectorError`.

**Required phase writes.**

- `FeatureTaskRuntimePhaseStateRecorder` and `FeatureTaskRuntimePhaseBriefingRecorder` return a value for a rejected required write (for example `RequiredPhaseWrite.Rejected(kind, workflowId, phaseId, attempt)`) instead of throwing `RequiredPhaseWriteRejected`. The goal-planning and run-loop callers (`GoalPlanningSharedPreplanSettlement.kt`, `GoalPlanningSharedPreplanProduction.kt`, `GoalPlanningPhaseAttemptGate.kt`, `GoalPlanningStepAttempts.kt`, `FeatureTaskRuntimeRunLoopStepBindings.kt` at the census tree) branch on it with the behaviour they have today. Delete `RequiredPhaseWriteRejected`.

If the custom-throwable baseline exists, remove the rows of every deleted class.

## Acceptance Criteria

1. No main source declares `OperationRefusalError`, `OperationUsageError`, any of their subclasses, or `DuplicateOperationIdError`. `OperationExecutor` contains no `catch`. Duplicate operation registration fails through `require`/`check` with the former message.
2. Every operation refusal and usage case produces the same CLI stdout, stderr and exit code, and the same operation result payload, as before; existing operation tests pass with only exception-type assertions replaced by assertions on the returned refusal.
3. `RejectedOutputDiagnosticRepository.read` and `insert` return sealed results that represent absent, expired, oversized and conflict without throwing; no main source declares `RejectedOutputDiagnosticError` or `RejectedOutputDiagnosticAmbiguousSelectorError`.
4. Persistence, permission, corrupt, retrieval and invalid-configuration failures throw `SkillBillRuntimeException` with a `RejectedOutputDiagnosticFailureCode`; the recorder degrades exactly the failure classes it degrades today and rethrows every other failure.
5. No main source declares `RequiredPhaseWriteRejected`; the recorders return the rejection as a value, and no goal-planning or run-loop code catches or `is`-checks an exception to detect a rejected required write.
6. Messages of every former class are byte-identical where they are still shown. No expected-output or wire-fixture assertion is edited other than type-to-value or type-to-code replacements.
7. If the custom-throwable baseline exists, it lists none of the deleted classes and `FailureCodeTotalityArchitectureTest` passes.

## Non-Goals

- Execution-plan admission errors (`FeatureTaskRuntimeExecutionPlanAdmissionError` family): they end the run and become codes in subtask 5.
- Changing which diagnostic failures degrade and which propagate.
- Other `SkillBillRuntimeException` subclasses.

## Dependency Notes

Depends on: none.
Applies to the operation classes wherever they are declared (SKILL-391 moved them into runtime-engine). If subtask 5 converted any of these classes to codes first, convert the code checks into the values described here. If subtask 1 has not landed, add the target-type pieces this subtask uses as the parent spec defines them. Coordinates with SKILL-390 (engine) and SKILL-393 (ports); the second lander keeps both edits.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite. Test obligations: a test per new result branch where an outcome was thrown before (operation blocked and usage, diagnostic absent/expired/conflict, rejected required write), each asserting the downstream behaviour that the old catch produced.

## Implementation Details

Paths below are relative to `../../../runtime-kotlin`. Line numbers refer to tree `432d427c8`. Do the tasks in order. Each family (operations, diagnostics, required writes) compiles on its own once its task block is finished.

### Task 0: target-type precondition (AC-004, AC-007)

- Check `runtime-contracts/src/main/kotlin/skillbill/error/core/RuntimeExceptionBases.kt`. If subtask 1 already added `RuntimeFailureCode`, the coded `SkillBillRuntimeException(code, message, cause)`, `LegacyFailureCode` and the `(message, cause)` secondary constructor, keep them as they are.
- If any of these pieces is missing, add it exactly as the parent spec's "Target failure model" defines it. Keep the class `open` while subclasses exist.
- Do not touch `ShellContentContractException` or its subclasses. That is subtask 4.

### Task 1: operation refusal value (AC-001, AC-002, AC-006)

**`engine/operation/core/OperationOutcome.kt`**

- Add `sealed interface OperationRefusal : OperationOutcome { val reason: String }`.
- Make `Blocked(override val reason: String)` implement `OperationRefusal`.
- Add `data class Usage(override val reason: String) : OperationRefusal`.
- Blocked keeps its wire meaning. Usage is new and never reaches `writeOperationResult` output (Task 4).

**Replace `engine/operation/core/OperationErrors.kt` with `OperationRefusals.kt`**

- It holds internal functions that return `OperationOutcome.Blocked` or `OperationOutcome.Usage`. Each one carries the former class's message, copied byte for byte, including the two-branch messages of `MissingReleaseBumpError`, `PullRequestNotFoundError` and `ClosedVerifyWorkflowError`.
- Give a message a function when it is built at more than one site, or when a test builds the expected text through the old constructor. Those are:
  - anchor-unreadable, pull-request-not-found, missing-intake, invalid-argument, invalid-selection, unresolvable-verify-target, unresolvable-scope, unknown-verify-workflow, closed-verify-workflow, consumed-token;
  - read by `ReleaseOperationTest`: superseded-token, unknown-token, foreign-token, moved-anchors, release-worktree-dirty, release-branch-behind.
- Inline every other message at its single site.

**Deletions**

- Delete `OperationErrors.kt`.
- Delete `runtime-contracts/src/main/kotlin/skillbill/error/operation/OperationUsageError.kt` and its now-empty `skillbill.error.operation` package directory. No guard, baseline or doc names the package; only history records do, and those stay.

### Task 2: operation contracts and core (AC-001, AC-002)

**`Operation.kt`**

- `fun pre(context): OperationRefusal? = null`.
- `ConfirmableOperation.admit(context, proposal): OperationRefusal? = null`.
- `ConfirmableOperation.currentAnchors(context): CurrentOperationAnchors`, a new sealed interface in the same file:
  - `Read(val values: Map<String, String>)`
  - `Unreadable(val refusal: OperationOutcome.Blocked)`

  Use this name, not `OperationAnchors`, which `skillbill.ports.operation.model` already declares.
- Rewrite the KDoc at lines 16-17: "`pre` returns an `OperationRefusal`. A blocked refusal is reported as blocked with nothing changed; a usage refusal is reported as a usage error. `null` means proceed."
- Rewrite the `admit` KDoc (lines 40-43) the same way: "returns a refusal to reject the confirm with the token still valid".

**`OperationStepRunner.kt`**

- Add `OperationStepResult.Refused(val refusal: OperationOutcome.Blocked)`.
- `fingerprint`, `dirtyPaths` and `contentIdentities` return through the refuse lambda. `runReadOnly` and `runEditing` return `Refused` with the same anchor-unreadable message the throw carried today.

**`OperationConfirmationGate.kt`**

- Replace `requireGitValue` (line 99) with `internal inline fun WorkflowGitOperationResult.gitValueOr(what: String, refuse: (OperationOutcome.Blocked) -> Nothing): String`. The trimmed value and the anchor-unreadable message stay the same.
- `propose` returns `OperationOutcome`: `AwaitingConfirmation`, or the Blocked refusal when `repositoryAnchors` cannot read git.
- `confirm` keeps its order and returns each refusal as a value:
  1. Find the proposal; if missing, unknown-token.
  2. `refusal(...)`: consumed, superseded, foreign, then moved anchors. `currentAnchors` → `Unreadable` and a git anchor failure both return Blocked.
  3. `admit`.
  4. `markConsumed` → consumed-token on false.
  5. `execute`.
- `refusal` returns `OperationRefusal?`.

**`OperationRegistry.kt`**

- The constructor uses `require(registered.put(operation.id, operation) == null) { "Operation '${operation.id}' is registered more than once." }`.
- `get` becomes `fun find(operationId: String): Operation?`. Its only main caller is the executor.

**`OperationExecutor.kt`**

- No `try`/`catch`. Order:
  1. Generate the invocation id.
  2. `registry.find(id)`, or return unknown-operation Usage with message `"Unknown operation '$id'; expected one of ${registry.ids.joinToString(", ")}."`.
  3. Unsupported `confirm:` → Usage `"Operation '$id' takes no confirm: token."`.
  4. `outcome = operation.pre(context) ?: (gate.confirm(...) or proceed(...))`.
- Call `operation.post(context, outcome)` unless the outcome is `Usage`. Today a usage error skipped `post`; no operation overrides `post`.
- `proceed` returns `gate.propose(...)`'s `OperationOutcome`.

### Task 3: operations return refusals (AC-001, AC-002)

Use the inline `refuse: (OperationRefusal) -> Nothing` idiom that `VerifyOperation.resumeStep` and `VerifyStepSequence.readOnly` already use. Callers pass `{ return it }` or `{ return OperationRunResult.Finished(it) }`. detekt's `ReturnCount` excludes lambda returns. If an inline chain stops compiling because a lambda is captured, return a small private sealed value for that one helper instead.

**`release/ReleaseBump.kt`**

- `parse(raw): ReleaseBump?`.

**`release/ReleaseOperation.kt`**

- `pre`: missing bump → Usage; dirty worktree, branch behind remote and git anchors → Blocked.
- `run` reads the bump with `checkNotNull(ReleaseBump.parse(...))`, because `pre` already validated it on every path that reaches `run`.
- `currentAnchors` returns `Read`/`Unreadable`.
- `run` builds `operationValues` from `Read`, and returns `Finished(refusal)` for `Unreadable` or for a git anchor failure.

**`prreviewfix/PrReviewFixOperation.kt`**

- `pre` returns the Usage that `usageError(...)` builds today.
- `pullRequest`, `listThreads`, `storedAnchors` and `requirePullRequestBranch` take the refuse lambda.
- `run`, `admit`, `execute` and `currentAnchors` return the refusal.
- `admit` keeps its order: selection, branch, protected branch, dirty worktree.

**`prreviewfix/PrReviewFixSelection.kt`**

- `parsePrReviewFixSelection` returns `PrReviewFixSelection { Selected(threads); Invalid(usage: OperationOutcome.Usage) }`, a new internal sealed type in the same file.
- `admit` and `execute` both return `Invalid.usage`. Execute's re-parse keeps today's output if it ever fails.

**`verify/VerifyOperation.kt`**

- `pre` returns Usage.
- `run` returns `Finished(refusal)` for a first-baseline refusal.
- When `confirm`/`propose` returns an `OperationRefusal`, `run` returns it before taking the second `worktreeBaseline`. Today the throw skipped that second baseline, and verify returned no Blocked value of its own.
- `confirmableSnapshot`, `verifyWorkflow`, `resumeStep` (unknown, done), `requireSpec`, `resolveTarget`, `pullRequestHead`, `mergeBase`, `commit` and `worktreeBaseline` take the refuse lambda.
- The missing-target intake in `confirm` returns Usage.
- `extractAndPark` returns `step.refusal` on `Refused`, with no `failExtraction` write.

**`verify/VerifyStepSequence.kt`**

- Add `VerifyStepDone.Refused(refusal)`. `readOnly`'s `onFailure` type widens to `VerifyStepDone`.
- `run` returns `result.refusal` without `fail(...)`'s workflow write.

**`verify/VerifyCodeReviewStep.kt`**

- Add `VerifyCodeReviewOutcome.Refused(refusal)`.
- `codeReview` maps it to `VerifyStepDone.Refused`.

**`prreviewfix/PrReviewFixExecution.kt`**

- `Refused` returns the refusal immediately. Do not call `stopped(...)`, and run no replies or push.

**`unittestvalue/UnitTestValueCheckOperation.kt`**

- `resolveScope`, `existing` and `names` take the refuse lambda and return Usage.
- A `Refused` step returns the refusal.

**`featureguard/FeatureGuardOperation.kt`, `featureguardcleanup/FeatureGuardCleanupOperation.kt`**

- `pre` returns Usage.
- `currentAnchors` returns `Read(emptyMap())`.
- A `Refused` step result returns the refusal.

**`core/OperationProposalSteps.kt`**

- `proposeFromStep` maps `Refused` to `Finished(refusal)`.

### Task 4: CLI operation edge (AC-002)

**`runtime-cli/src/main/kotlin/skillbill/cli/operation/OperationCommand.kt`**

- Remove the `try`/`catch` and the `OperationUsageError` import.
- In `writeOperationResult`'s `when`, add `is OperationOutcome.Usage -> throw UsageError(outcome.reason)`.
- Stdout, stderr and the exit code stay identical, because `usageError(error)` threw the same `UsageError(message)`. Only the unshown cause changes.

### Task 5: rejected-output diagnostics (AC-003, AC-004, AC-006)

**Codes: `runtime-contracts/.../skillbill/error/core/RejectedOutputDiagnosticFailureCode.kt`**

- This file replaces `RejectedOutputDiagnosticError.kt`. It stays beside the sealed class it replaces, per the SKILL-391 placement: infra writes the code, engine and CLI read it.
- `enum class RejectedOutputDiagnosticFailureCode : RuntimeFailureCode { PERSISTENCE, PERMISSION, CORRUPT, RETRIEVAL, INVALID_CONFIGURATION, INVALID_REQUEST, CONFLICT }`.
  - `INVALID_REQUEST` is thrown only at the recorder and CLI edges, where today the class was propagated or rethrown.
  - `CONFLICT` is thrown by the sqlite producer-evidence retention and by the recorder's transaction abort.
- Next to the enum, add message functions for every message used at more than one site: absent, expired, oversized, corrupt, persistence, conflict, invalid request, invalid configuration. Copy the text verbatim from the deleted class.
- Inline the permission and retrieval messages at their single sites.
- Delete `RejectedOutputDiagnosticError.kt`.

**Port results: `runtime-ports/.../skillbill/ports/diagnostics/model/RejectedOutputDiagnosticResults.kt`**

- Each sealed interface has nested `data class` variants only, which `PortsDeclarationArchitectureTest.forbiddenTopLevelClassViolations` accepts:
  - `RejectedOutputDiagnosticRead { Found(record); Expired(record); Oversized(record); Absent(identity) }`
  - `RejectedOutputDiagnosticInsert { Inserted(record); Conflict(identity) }`
- `Expired` and `Oversized` carry the record so that `RejectedOutputDiagnosticService.record` keeps its idempotent tombstone lookup, which "re-recording an expired attempt" pins.

**Port signatures: `ports/diagnostics/RejectedOutputDiagnosticRepository.kt`**

- `insert(record): RejectedOutputDiagnosticInsert`.
- `read(identity): RejectedOutputDiagnosticRead`.
- Other signatures stay as they are.

**`runtime-infra/sqlite/.../SqliteRejectedOutputDiagnosticRepository.kt`**

- `read` classifies `find(identity)` by `metadata.lifecycle`, or returns `Absent`.
- `insert` returns `Conflict` where it threw. The raced-insert fallback is unchanged.
- `persistence(...)` and `FileRejectedOutputDiagnosticPermissions` throw `SkillBillRuntimeException(PERSISTENCE, persistenceMessage(op), cause)`.
- Drop the now-redundant `catch (error: RejectedOutputDiagnosticError) { throw error }` arm. A `SkillBillRuntimeException` is never an `SQLException`.
- `corruptRecord` throws `CORRUPT` with its cause.
- `retainProducerOutput` keeps `Unit`:
  - readback-missing throws `PERSISTENCE`;
  - an evidence mismatch throws `CONFLICT` with the conflict message.

  Its only reader is the recorder's degrade boundary, and the spec names only `read` and `insert` as sealed.

**testFixtures**

- `runtime-ports/src/testFixtures/.../RejectedOutputDiagnosticRepositoryDefaults.kt`, `UnavailableRejectedOutputDiagnostics.kt`, `persistence/UnitOfWorkDefaults.kt`: throw the coded `PERSISTENCE` exception, or return the new values to match the signatures.

**Service results: `runtime-application/.../diagnostics/model/RejectedOutputDiagnosticResults.kt`**

- `RejectedOutputDiagnosticRecording { Recorded(metadata); Conflict(identity); InvalidRequest(reason) }`
- `RejectedOutputDiagnosticSelection { Selected(diagnostics); InvalidRequest(reason) }`
- `RejectedOutputDiagnosticDeletion { Deleted(count); InvalidRequest(reason) }`
- `RejectedOutputDiagnosticRawRead { Payload(bytes); Absent(identity); Expired(identity); Oversized(identity) }`
- Delete `RejectedOutputDiagnosticAmbiguousSelectorError.kt`.
- Extend `RejectedOutputDiagnosticInspectionResult` with `Absent(identity)`, `Expired(identity)`, `Oversized(identity)`, `AmbiguousSelector(matchCount)` and `InvalidRequest(reason)`.

**`application/diagnostics/RejectedOutputDiagnosticService.kt`**

- `record`: validate, then existing lookup (`Found`/`Expired`/`Oversized` all count as existing; a mismatch returns `Conflict`), cleanup, then insert (`Conflict` passes through). Keep this order.
- `inspect` and `delete` return `InvalidRequest` before cleanup, as today.
- `readRaw`: cleanup, read, metadata validation, then lifecycle values, then `verifiedPayload`. `verifiedPayload` throws `CORRUPT`.
- `applyRestrictivePermissions` maps `IOException`, `SecurityException` and `UnsupportedOperationException` to `PERMISSION`. Delete its redundant rethrow arm.
- `RejectedOutputDiagnosticConfig.init` throws `INVALID_CONFIGURATION` with the same messages.

**`application/diagnostics/RejectedOutputDiagnosticInspection.kt`**

- Return the values: no matches → `Absent(selector.workflowId)`; raw read with more than one match → `AmbiguousSelector`; otherwise pass the raw-read values through.
- `cleanup` returns `RejectedOutputDiagnosticDeletion`.

**`runtime-cli/.../featuretask/RejectedOutputCommands.kt`**

- Remove the ambiguous-selector catch.
- Map each value to `throw SkillBillRuntimeException(code, message)` with the byte-identical former message, so output stays on today's `CliRuntime` runtime-exception arm:
  - absent, expired, oversized → `RETRIEVAL` with their own messages;
  - ambiguous → `RETRIEVAL` with `"Rejected output diagnostic retrieval failed: $AMBIGUOUS_RAW_SELECTOR_REASON"`;
  - invalid request → `INVALID_REQUEST`.

**`engine/goalplanning/GoalPlanningLogService.kt:61-73`**

- Map `Selected` to its list and `InvalidRequest` to `emptyList()`.
- This edit touches the `runCatching`, so switch it to `getOrElseUnlessCooperative { emptyList() }`.

**`engine/featuretask/lifecycle/core/FeatureTaskRuntimeRejectedOutputRecorder.kt`**

- Inside the `recordRejectedOutput` transaction, a `Conflict` or `InvalidRequest` from `service.record` throws the coded `CONFLICT` or `INVALID_REQUEST` exception. This preserves today's rollback of producer evidence retained earlier in the same transaction. `DatabaseSessionFactory` has no rollback API, and a returned value would commit that row.
- Replace `degradableFailureClass()` with a mapping on `SkillBillRuntimeException.code`:
  - `CONFLICT` → `CONFLICT`
  - `PERMISSION` → `PERMISSION`
  - `CORRUPT` → `CORRUPT`
  - `PERSISTENCE`, `RETRIEVAL` → `PERSISTENCE`
  - `INVALID_REQUEST`, `INVALID_CONFIGURATION`, or any code that is not a `RejectedOutputDiagnosticFailureCode` → `null`, then rethrow the same exception object.

  Absent, expired and oversized can no longer reach the recorder. Its paths never read raw bytes, and `readProducerOutput` already returns null for absent.
- Catch order in `degradeDiagnosticFailure` and `producerOutput`: the two `Invalid…SchemaError` catches stay first, before `catch (error: SkillBillRuntimeException)`. Those classes still extend `SkillBillRuntimeException` through `ShellContentContractException`; a broad catch first would rethrow them instead of degrading them to `SCHEMA`.

### Task 6: required phase writes as values (AC-005)

**The type: `engine/featuretask/slot/state/RequiredPhaseWrite.kt`**

- Rename the file from `RequiredPhaseWriteRejected.kt` and keep `RequiredPhaseWriteKind`.
- Declare `sealed interface RequiredPhaseWrite`:
  - `data object Acknowledged`
  - `data class Rejected(writeKind, workflowId, phaseId, attempt)` with `val message: String`, set to `"Required ${writeKind.wireValue} write rejected for phase '$phaseId', attempt $attempt."`.
- Delete `RequiredPhaseWriteRejected`.

**Recorders**

- `FeatureTaskRuntimePhaseStateRecorder.recordRequiredPhaseStart` returns `Rejected` when `recordPhaseState` is false, otherwise `Acknowledged`.
- `FeatureTaskRuntimePhaseBriefingRecorder.recordPhaseBriefing` returns `RequiredPhaseWrite`: `return@transaction Rejected(...)` for a missing row, `Acknowledged` after persisting.

**Interfaces and adapters**

- Change the return types on `PhaseRunRecords.recordRequiredPhaseStart` and `PhaseLaunchRecords.recordPhaseBriefing` (`slot/state/PhaseRunRecords.kt:94`, `:168`).
- `FeatureTaskRuntimePhaseRecorder` (`:133`, `:222`) and `DurablePhaseRunRecords` (`:73`, `:141`) pass the value through.
- `InMemoryPhaseRunRecords` returns `Acknowledged`.

**Start-write path**

- `FeatureTaskRuntimeRunTransitionOwner.acknowledgeRequiredPhaseStart` returns the value. It calls `reserveReviewPassAfterPhaseState` only on `Acknowledged`; today the throw skipped it.
- `FeatureTaskRuntimeRunLoopOutputPersistence.persistPhase` and `PhaseAttemptOnce.persistRequiredStart` return the value.

**Attempt boundary**

These six sites keep the single shared handler `PhaseAttemptOnce.blockRequiredWriteRejection(host, run, rejection: RequiredPhaseWrite.Rejected)`, per the SKILL-380 one-runner decision. Each replaces its `try`/`catch` with an exhaustive `when`:

- `PhaseAttemptLoop.kt:58-62`
- `PhaseAttemptOnce.attemptOnce` (`:76-82`)
- `PhaseAttemptRunHost.persistFinalizationRequiredRunning` (`:287-291`) and its `blockRequiredWriteRejection` (`:262`)
- `PhaseAttemptGateEffects.persistGateRequiredRunning` (`:66-70`) and `blockGateRequiredWriteRejection` (`:245`)
- `FeatureTaskRuntimeRunLoopStepBindings.startReviewStep` and `blockRequiredReviewWrite` (`:334-351`)
- `PhaseStepBinding.blockRequiredReviewWrite` (`:124`)

**Inside `blockRequiredWriteRejection`**

- Keep the `runCatching`/`getOrElse` and its explicit `CancellationException`/`InterruptedException` rethrow. This is already the cooperative rethrow.
- Delete only `rejection.addSuppressed(secondary)`. A value cannot carry suppressed exceptions, and nothing ever surfaced the caught rejection.
- Keep the `RuntimeDiagnosticsBestEffortWarning` record with `secondary` as cause, and the `rejection.writeKind` / `rejection.attempt` attribution.

**Briefing during launch**

- Add `LaunchRequiredWriteRejected(rejection) : LaunchPreparation` in `runloop/core/FeatureTaskRuntimeRunLoopModels.kt:258`.
- `PhaseLaunchPreparation.prepareLaunch` returns `LaunchPreparation`:
  - `LaunchRequiredWriteRejected` when the briefing at `:288` is rejected;
  - otherwise `PreparedLaunchReady`.
- `prepareDeclaredLaunchBody` and `prepareLaunchForCapture` pass it through.
- Add `LaunchResult.RequiredWriteRejected(rejection)` (`runner/LaunchResult.kt`, `fileManifest = null`).
- In `PhaseAttemptOnce.launchAndCapture`, the `prepareLaunch` override stores the rejection in a local, as it already does for `rejected`, and returns `null`. After `runPreparedStep`, return `LaunchResult.RequiredWriteRejected`.
- `attemptOnce` blocks through the shared handler before `settleRecordRejectionLaunchOutcome`. Nothing runs after a `null` from `prepareLaunch`: `DefaultPhaseRunner` returns `preparationRejected` at once, and `captureBefore` ran in both versions.
- Before relying on this, confirm that no `PhaseRunner` decorator returned by `runnerFor` acts on a `PREPARATION_REJECTED` output.

**Review**

- `PhaseReviewStepBinding.startReview` and `PhaseStepState.prepareReviewBriefing` (`:46`) return `RequiredPhaseWrite`. The binding's `prepareReviewBriefing` maps `LaunchRequiredWriteRejected` to `Rejected`, and maps every other preparation to `Acknowledged`, which keeps today's ignored projection-rejected results.
- `CodeReviewStep.run` loses its `try`/`catch`. `runAfterStart` returns `state.blockRequiredReviewWrite(it)` when `startReview` or `prepareReviewBriefing` is `Rejected`.
- Remove the `is RequiredPhaseWriteRejected` arm from `launchFailure` (`:384`).

**Goal planning**

Production uses `InMemoryPhaseRunRecords`, so a rejection there happens only through injected records, as `GoalPlanningSweepTest.rejectedPlanningStartAndBriefingNeverLaunchTheRejectedUnitOrPublishItsPlan` does. Plumb the value to `GoalPlanningStepAttempts`:

- **Recording the briefing.** `PhasePlanningBriefingBinding.recordPlanningBriefing` returns `RequiredPhaseWrite`. `composePlanningPrompt` becomes `inline` with `onRejected: (RequiredPhaseWrite.Rejected) -> Nothing`.
- **Producing an attempt.** In `produceAttemptAfterPauseCheck`, replace the touched `runCatching` with `try { composePlanningPrompt(args) { return GoalPlanningPhaseProduction.RequiredWriteRejected(it) } } catch (error: InvalidFeatureTaskRuntimeHandoffProjectionError)`. This narrow catch is equivalent to today's.
- **The production result.** Add `GoalPlanningPhaseProduction.RequiredWriteRejected(rejection)` (`model/GoalPlanningSweepModels.kt:42`). `settlePlanningProduction` returns it without `recordPlanningAttempt`; today the throw escaped before settlement.
- **The cooperative rethrow.** `produceAttemptOrStop` drops the `RequiredPhaseWriteRejected` test and uses `error.rethrowIfCooperativeCancellationOrInterruption()`, as the touched-`runCatching` constraint requires. `InterruptedException` now propagates there instead of becoming a stopped planning outcome. Record that in the decisions entry below.
- **Producing the shared preplan.** `produceSharedPreplanCheckpoint` returns `Result<SharedPreplanProduction { Produced(checkpoint); RequiredWriteRejected(rejection) }>`.
  - `produceSharedPreplan` re-checkpoints only `Produced`.
  - Its `onFailure` uses the cooperative rethrow.
- **Refreshing the shared preplan.** `refreshStaleSharedPreplan` returns `Result<SharedPreplanRefresh { Refreshed(provenance, checkpoint); RequiredWriteRejected(rejection) }>`, which replaces `RefreshedSharedPreplan`. Its `onFailure` also uses the cooperative rethrow.
- **Settling the shared preplan.** Add `SharedPreplanSettlement.RequiredWriteRejected`. `settleSharedPreplan`, `settleStaleValidSharedPreplan` and `reclassifyAfterStaleRefresh` map the values into it.
- **Producing a subtask plan.** `producePlan` (`outcome/GoalPlanningSubtaskPlanProduction.kt`) returns a file-local sealed `SubtaskPlanProduction { Planned; Stopped(outcome); RequiredWriteRejected(rejection) }`.
- **Run progress.** `GoalPlanningRunProgress.settlePreplan(launch, onRequiredWriteRejected: (RequiredPhaseWrite.Rejected) -> PhaseOutcome)` and `producePlan(unitId, sink, launch, onRequiredWriteRejected)` return `onRequiredWriteRejected(rejection)`. They set no `halt`, `ready`, `unitStops` or `startedPlanIds` state, matching the throw.
- **The step boundary.** `GoalPlanningStepAttempts.run` blocks a rejected `persistRequiredStart` with the shared handler. It passes `{ PhaseAttemptOnce.blockRequiredWriteRejection(context, run, it) }` to both progress calls and has no `try`/`catch`.

**Decision record**

- Add a dated entry at the top of `runtime-engine/src/main/kotlin/skillbill/engine/featuretask/agent/decisions.md`, titled "Return rejected required phase writes as values (SKILL-398)", with Context, Decision, Reason, Supersedes and Alternatives considered lines.
- The entry supersedes the mechanism in `#29b63afd6d8f`: the recorders return `RequiredPhaseWrite.Rejected`, and the shared attempt boundary handles it.
- It states the guarantees that do not change: no execution after a rejected write, write kind, phase and attempt attribution, terminal recording, and secondary-failure diagnostics.
- It also notes the new `InterruptedException` propagation in goal planning.
- Alternatives considered: keep the throw, rejected under the failure model.

### Task 7: baseline (AC-007)

If `runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt` exists, delete the rows of every deleted class. Use the row format subtask 1 recorded, compare whole rows, and include nested variants:

- runtime-engine: `OperationRefusalError`, its 17 subclasses, the operation usage subclasses declared in `OperationErrors.kt`, `DuplicateOperationIdError`, `RequiredPhaseWriteRejected`
- runtime-contracts: `OperationUsageError`, `RejectedOutputDiagnosticError` and its 10 variants
- runtime-application: `RejectedOutputDiagnosticAmbiguousSelectorError`

Do not re-record the whole baseline. Other subtasks' rows must stay untouched.

### Tests

**Assertion edits in existing tests** (type-to-value or type-to-code only; expected strings unchanged):

- `OperationRegistryTest`:
  - duplicate → `assertFailsWith<IllegalArgumentException>` plus the former message;
  - unknown → `assertNull(registry.find("deploy"))` plus `registry.ids`.
- `ReleaseOperationTest`: build the expected messages with the `OperationRefusals.kt` factories.
- `ReleaseVersionTest`: `assertNull(ReleaseBump.parse(raw))`.
- `PrReviewFixSelectionTest`: `assertIs<PrReviewFixSelection.Invalid>`.
- `PrReviewFixOperationExecutionTest:52-53`, `VerifyOperationTest:250`: assert the returned `OperationOutcome.Usage`.
- `RejectedOutputDiagnosticServiceTest`:
  - oversized, invalid-request → `assertIs` on the returned values;
  - corrupt, invalid-configuration → `assertFailsWith<SkillBillRuntimeException>` plus an `assertEquals` on `code`;
  - the test repository fakes return `Absent`/`Conflict` values.
- `RejectedOutputCommandsTest`: the repository fake returns `Absent`.
- `SqliteRejectedOutputDiagnosticRepositoryTest`:
  - `:44` → `Conflict` value;
  - `:72`, `:161` (retention) → coded `CONFLICT`.
- `FileRejectedOutputDiagnosticPermissionsTest:42` → coded `PERSISTENCE`.
- `FeatureTaskRuntimeDiagnosticDegradationTest`:
  - `:131` → coded `PERSISTENCE` exception;
  - `:170` → `SkillBillRuntimeException` with code `INVALID_REQUEST`.
- `FeatureTaskRuntimeRunnerTestSupport:1749`: change the field type.
- `FeatureTaskRuntimeSharedEvidenceRecorderTest:30`, `:53` → `assertIs<RequiredPhaseWrite.Rejected>` on the returned value.
- `RequiredPhasePersistenceTest`, `PhaseValidationRunTest:270-290`, `RejectingPlanningRunLoopEntry`: the records fakes return `RequiredPhaseWrite.Rejected` instead of throwing.

The existing tests then carry the AC-002 and AC-005 behaviour proofs:

- operation blocked: `ReleaseOperationTest`;
- operation usage stdout, stderr and exit: `OperationCommandTest` (unknown operation, verify intake, release bump);
- a rejected start or briefing stops execution before launch: `RequiredPhasePersistenceTest`, `PhaseValidationRunTest` and `GoalPlanningSweepTest` (preplan and plan, both kinds).

**New tests** (each targets a bug the conversion can introduce):

1. `SqliteRejectedOutputDiagnosticRepositoryTest`: `read` returns `Absent` for an unknown identity and `Expired` carrying the record for an expired row. Bug caught: lifecycle misclassified, so a raw read of an expired row reaches `verifiedPayload` and reports corrupt instead of expired.
2. `RejectedOutputCommandsTest`: a raw inspect of an expired diagnostic, and an inspect with no match, each print the former message through `CliRuntime` with today's exit code. Bug caught: a CLI value branch mapped to the wrong message or arm, or silently printing nothing.
3. `FeatureTaskRuntimeDiagnosticDegradationTest`: extend "a same-identity divergent recordRejectedOutput returns Degraded and no rod token" to also assert that the producer evidence retained in that call is not persisted. Bug caught: a returned conflict committing the transaction.

No other new tests. The remaining branches are covered by the converted tests above.

### Constraints

- Messages are copied verbatim. Inline only where Task 1 or Task 5 says so.
- No typealias for a deleted class.
- No `Result`/`Either` library and no generic success-or-refusal wrapper. Each sealed type above is specific to one call.
- No new property on `SkillBillRuntimeException`.
- No new `runCatching`. A touched `runCatching` becomes a narrow catch or uses `rethrowIfCooperativeCancellationOrInterruption`/`getOrElseUnlessCooperative`.
- `CancellationException` and `InterruptedException` still propagate everywhere they do today.
- No `@Suppress`. Respect detekt `ReturnCount 4` (lambda returns excluded), `LongMethod 70` and `CyclomaticComplexMethod 15`; split helpers rather than chaining early returns.
- Do not touch `ShellContentContractException` subclasses, the execution-plan admission errors, `RuntimeOwnedFactUnavailable`, `RefreshRefused`, or the `CliRuntime`/`McpToolDispatcher` arms. Other subtasks own those.
- After implementation, `grep` main sources for `RequiredPhaseWriteRejected`, `OperationRefusalError`, `OperationUsageError`, `DuplicateOperationIdError`, `RejectedOutputDiagnosticError` and `RejectedOutputDiagnosticAmbiguousSelectorError`. Each must return nothing, and `OperationExecutor.kt` must contain no `catch`.
- Gates (build, unit tests, detekt, the runtime-core repoTest suite including `FailureCodeTotalityArchitectureTest` and `PortsDeclarationArchitectureTest`) belong to the build and validate phases.

## Next Path

skill-bill goal SKILL-398

## Spec Path

.feature-specs/SKILL-398-runtime-exception-reduction/spec_subtask_2_operation-diagnostic-and-phase-write-results.md
