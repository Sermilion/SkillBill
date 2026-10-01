# SKILL-392 Subtask 2 - engine-owned feature-task run

Parent spec: [.feature-specs/SKILL-392-runtime-cli-boundary-cleanup/spec.md](spec.md)
Issue key: SKILL-392

## Scope

Covers F-001, F-011 and F-012 from [investigation.md](investigation.md). Touches:
- runtime-engine `featuretask.runner` and `featuretask.lifecycle.execution`;
- runtime-cli `featuretask`, `model` and `goal.core`;
- runtime-ports `system/HostPlatformPort.kt` and runtime-infra/host `JdkHostPlatformPort`;
- the three handwritten `HostPlatformPort` test substitutes;
- runtime-core `RuntimeEngineInboundApiTest` pins;
- one test moved from runtime-cli to runtime-core.

Changes:
- **F-001.** Move the feature-task execution sequence into one `@Inject` entry in runtime-engine: open or reuse the workflow id with its execution plan, derive the identity once, own the worker lease, read the run invariants, assemble the request and run it. `FeatureTaskRuntimeExecutionEntry.admit` uses the same identity derivation. The CLI keeps option parsing, preparation, `UsageError` texts, presentation, exit codes and the telemetry drain. The retained run-override seam is retyped to the new entry's input and output.
- **F-011.** Read the goal-run provenance java command through `HostPlatformPort`.
- **F-012.** Move `IdeStatusReadSnapshotConcurrencyTest` into runtime-core `src/test` under `skillbill.di.*`.

## Acceptance Criteria

1. runtime-cli main references none of `FeatureTaskRuntimeRunner`, `FeatureTaskRuntimeWorkerCoordinator`, `FeatureTaskRuntimeExecutionPlanResolver`, `FeatureTaskRuntimeExecutionPlanCreationRequest` or `FeatureTaskRuntimeRunInvariantsSource`. It does not construct `FeatureTaskExecutionIdentity` and does not call `WorkflowService.openFeatureTask` for the run, explicit-run, resume or deprecated-alias paths. Those commands call one runtime-engine entry that returns `FeatureTaskRuntimeRunReport`.
2. runtime-engine main has one function that derives the expected `FeatureTaskExecutionIdentity` for a feature-task run from the workflow id, issue key, repository root, spec path and route scope. Both the new entry and `FeatureTaskRuntimeExecutionEntry.admit` use it. It normalizes the issue key through `FeatureTaskExecutionIdentityPolicy`, and for existing standalone and goal-child workflows it produces the same governed spec path and repository identity strings that are persisted today.
3. The new entry acquires the feature-task worker lease before running and releases it on every exit, including failure and cancellation. The existing worker-coordinator, takeover-fencing and admission suites pass unchanged.
4. Running, resuming and goal-child continuation produce the same workflow rows, the same `FeatureTaskRuntimeRunReport`, and the same stdout, stderr, exit codes and payloads as at `ae23f4f28`. An invalid governed spec path and a workflow-open failure keep their current `UsageError` texts.
5. `RuntimeEngineInboundApiTest.PINNED_ENGINE_INBOUND_API_TYPES` adds the new entry and its input model. It drops every engine type that no runtime-application, runtime-cli or runtime-mcp main file references afterwards.
6. `CliRuntimeContext` and `CliRunInputs` still offer one run-override seam, typed as the new entry's input to `FeatureTaskRuntimeRunReport`. `FeatureTaskRuntimeGoalContinuationProtocolTest` still asserts that contract-built continuation argv populates every goal-continuation field.
7. runtime-cli main contains no `ProcessHandle` reference. `HostPlatformPort` supplies the java command, `JdkHostPlatformPort` implements it, and every handwritten substitute compiles.
8. `IdeStatusReadSnapshotConcurrencyTest` lives in runtime-core `src/test` under a `skillbill.di.*` package, and its assertions are unchanged. runtime-cli tests contain no test that exercises only engine services without `CliRuntime` or a CLI declaration.
9. The new engine entry has at most 12 constructor parameters, all private. Its package stays within 12 sibling files, and the engine model package within 20. No new architecture-test class, baseline entry, detekt suppression or module is added.

## Non-Goals

- Moving run preparation or resume verification out of the CLI.
- Changing the goal-child persisted identity derivation in `GoalRunnerSubtaskLaunchPrepare`.
- Engine-wide issue-key normalization, `FeatureTaskRuntimeRunner` getter visibility, or preflight add-on resolution (recorded follow-ups).
- Adding an interface in front of the new entry.

## Dependency Notes

Depends on: subtask 1. It waits for no other issue: if SKILL-387 or SKILL-389 (runtime-core) has landed, rebase onto it first; otherwise implement against the current tree, and whichever lands second keeps both edits. Subtask 1 removes `VerifyRuntimeResumeArgs` and the port field from `CliRunInputs`, which this subtask's CLI edits build on.

SKILL-393 (runtime-ports) also edits `PINNED_ENGINE_INBOUND_API_TYPES` and deletes the engine `work.model` typealiases. Whichever lands second keeps both pin edits. If SKILL-393 lands first, the moved `IdeStatusReadSnapshotConcurrencyTest` imports `IdeStatusProblemCode` from `skillbill.ports.idestatus.model`.

SKILL-387 has no shared symbol but rewrites engine phase-output admission. Whichever lands second rechecks `FeatureTaskRuntimeRunner.run`, `FeatureTaskRuntimeRunRequest` and `FeatureTaskRuntimeExecutionEntry` against the other's change. Before implementing, recheck `../..` for runtime-engine `featuretask.runner` or runtime-ports `HostPlatformPort` bundles from parallel sessions.

## Validation Strategy

- **Build**: compiles runtime-ports, runtime-infra/host, runtime-engine, runtime-core and runtime-cli, proving kotlin-inject resolution of the new entry.
- **Validate**: runs the full project checks, including:
  - `RuntimeEngineInboundApiTest`;
  - the engine worker-coordinator, fencing and admission suites (identity and lease evidence);
  - the runtime-cli feature-task and goal suites, including `FeatureTaskRuntimeGoalContinuationProtocolTest` and `CliGoalRuntimeTest`;
  - the moved runtime-core IdeStatus test;
  - the runtime-mcp parity tests.

## Implementation Details

Planned against HEAD `677d01c00` on `base/SKILL-380-phase-slot-strategies`. Subtask 1 lands first on the same feature branch. Apply each step to the files as subtask 1 left them. In particular, `VerifyRuntimeResumeArgs` is gone, `FeatureTaskRuntimeRunPreparation` injects `RepositoryEnclosingRootPort` directly, and `CliRunInputs` no longer carries `repositoryEnclosingRootPort`. Where subtask 1 has not removed something, use what is present.

The byte-identity reference is `d13547842`. It is the rewritten SKILL-386 commit, and its tree differs from `ae23f4f28` only in `../../../README.md`, so diffs compare against it.

### Task 0: Pre-flight census (no code)

- Recheck `../..` for any new bundle that touches runtime-engine `featuretask.runner`, `featuretask.lifecycle.execution` or runtime-ports `HostPlatformPort`.
- SKILL-398 subtask 4: if it has landed, the two derivation throws in Task 1 use its coded `SkillBillRuntimeException` with the FeatureTaskRuntime area code instead of `InvalidFeatureTaskExecutionIdentitySchemaError`. Do not change the `SkillBillRuntimeException` arm in `CliRuntime`.
- SKILL-387 has already landed. Implement against the current `FeatureTaskRuntimeRunner.run`, `FeatureTaskRuntimeRunRequest` and `FeatureTaskRuntimeExecutionEntry`.
- SKILL-390 owns the other engine `trim().uppercase()` sites, the `FeatureTaskRuntimeRunner` getters and preflight add-on resolution. Leave them alone, including `validateAdmittedRequest`'s `request.issueKey.trim().uppercase()`.

### Task 1: One identity derivation in the engine (AC 2, AC 3)

File: `runtime-engine/src/main/kotlin/skillbill/engine/featuretask/lifecycle/execution/FeatureTaskRuntimeExecutionEntry.kt`. Add two top-level `internal` functions here; no new file.

**`RepositoryEnclosingRootPort.governedFeatureTaskSpecPath(workflowId: String, repoRoot: Path, specPath: Path): String`**
- Calls `resolveFeatureTaskGovernedSpecPath(this, repoRoot, specPath)` from `skillbill.application.workflow`. This is the algorithm that created every persisted standalone row: enclosing git root, then `canonicalPath`, then relative specs resolved against that root.
- `Ok` returns `relativePath`.
- `OutsideRepository` throws `InvalidFeatureTaskExecutionIdentitySchemaError(workflowId, "spec escapes admitted repository")`, the same text `admit` throws today.
- `InvalidGovernedPath` throws the same error class with `"spec is not Markdown beneath .feature-specs/"`.

**`RepositoryEnclosingRootPort.expectedFeatureTaskExecutionIdentity(workflowId: String, issueKey: String, repoRoot: Path, specPath: Path, routeScope: FeatureTaskRouteScope): FeatureTaskExecutionIdentity`**
- This is the single derivation AC 2 names.
- It builds `FeatureTaskExecutionIdentity` from:
  - `workflowId`;
  - `FeatureTaskExecutionIdentityPolicy.normalizeIssueKey(issueKey, workflowId)`;
  - `repositoryIdentity(repoRoot)`;
  - `governedFeatureTaskSpecPath(workflowId, repoRoot, specPath)`;
  - `FeatureTaskWorkflowMode.RUNTIME`;
  - `routeScope`.
- It is an extension function so it stays at 5 parameters. detekt `functionThreshold` is 6.

**Rewrite `admit`.**
- Replace the inline derivation (the root, spec, canonicalSpec and escape-check lines and the `FeatureTaskExecutionIdentity(...)` constructor) with `repositories.expectedFeatureTaskExecutionIdentity(request.workflowId, request.issueKey, request.repoRoot, Path.of(request.runInvariants.specReference), scope)`. Here `scope` is `GOAL_CHILD` when `request.goalContinuation != null`, otherwise `STANDALONE`.
- Keep `val root = repositories.canonicalPath(request.repoRoot)` only as the `resolver.resolveInputs(root, …)` argument, so gate-policy resolution does not change.
- Leave `requireMatchingRequest` and the transaction body unchanged.

**Why the persisted strings stay the same:**
- `specReference` is already absolute: `FileSystemFeatureTaskRuntimeRunInvariantsSource` normalizes it against the working directory. So the only change for real repositories is the root: admit now uses the enclosing git root instead of `canonicalPath(repoRoot)`.
- That matches how open and the CLI already derive it. It also fixes the latent mismatch when `--repo-root` names a subdirectory.
- `canonicalPath` (toRealPath with a normalize fallback) equals today's `optionalRealPath ?: toAbsolutePath().normalize()` for the existing spec file.
- The engine test port maps both calls to `toAbsolutePath().normalize()`, and every engine test that reaches `admit` uses a `.feature-specs/…​.md` reference. The `"spec.md"` references in `FeatureTaskRuntimeBranchSetupTest`, `FeatureTaskRuntimePlanningProjectionEdgeTest`, `FeatureTaskRuntimeSharedEvidenceRecorderTest` and `FeatureTaskRuntimeValidationGateTestSupport` never call `admit`.
- Goal-child rows come from `GoalRunnerSubtaskLaunchPrepare.governedChildSpecPath`, which stays untouched. Those children launch with an explicit workflow id and are matched by this same derivation, as the CLI matches them today.

**Accepted delta (mandated by AC 2).** On the explicit-workflow-id path, a malformed issue key (blank, control characters or over-length) now fails at `normalizeIssueKey` with the policy's message. Today it fails later, at admission. Valid keys are unaffected.

### Task 2: Engine input model and run entry (AC 1, AC 3, AC 4, AC 9)

**Input model.** Add `data class FeatureTaskRuntimeRunInput` to `runtime-engine/.../engine/featuretask/model/core/FeatureTaskRuntimeRunModels.kt`, next to `FeatureTaskRuntimeRunRequest`. No new sibling file: the package stays at 12 of 20.

| Field | Type |
| --- | --- |
| `issueKey` | `String` |
| `specPath` | `String` |
| `repoRoot` | `Path` |
| `explicitWorkflowId` | `String?` |
| `invokedAgentId` | `String` |
| `agentAssignment` | `FeatureTaskRuntimeAgentAssignment` |
| `modelAssignment` | `FeatureTaskRuntimeModelAssignment` |
| `compactionSettings` | `CompactionSettings` |
| `environment` | `Map<String, String>` |
| `timeout` | `Duration?` |
| `requestedCodeReviewMode` | `CodeReviewExecutionMode?` |
| `goalContinuation` | `FeatureTaskRuntimeGoalContinuationContext?` |
| `operatorDecision` | `GoalSubtaskOperatorDecision?` |
| `agentAddonSelection` | `HydratedAgentAddonSelection` |
| `eventSink` | `FeatureTaskRuntimeRunEventSink` |

- No defaults, so the CLI states every value.
- `goalContinuation` keeps its existing type, so the continuation protocol test can assert the same fields.

**Entry.** New file `runtime-engine/.../engine/featuretask/runner/FeatureTaskRuntimeRunEntry.kt`. The package goes from 9 to 10 files.
- Declaration: `@Inject class FeatureTaskRuntimeRunEntry`. Public, unscoped, no interface and no KDoc.
- Six private constructor parameters: `WorkflowService`, `FeatureTaskRuntimeExecutionPlanResolver`, `FeatureTaskRuntimeWorkerCoordinator`, `FeatureTaskRuntimeRunInvariantsSource`, `FeatureTaskRuntimeRunner` and `RepositoryEnclosingRootPort`.
- `RuntimeComponent` already exposes every one of these. `CliComponent` therefore resolves the entry exactly as it resolves `FeatureTaskRuntimeRunExecution` today, with no new accessor and no change to the `runtimeComponentInboundApi` pin.

**`fun run(input: FeatureTaskRuntimeRunInput, onOpenFailure: (WorkflowOpenResult.Error) -> Nothing): FeatureTaskRuntimeRunReport`**

`onOpenFailure` keeps the CLI's `UsageError` text in the CLI without adding a result type or exception type. The body moves today's `FeatureTaskRuntimeRunExecution` logic verbatim and keeps its evaluation order:

1. Set `goalChild = input.goalContinuation != null`. Today the open uses `options.goalParentIssueKey != null` and the lease identity uses `prepared.goalContinuation != null`. These are equivalent, because `parseGoalContinuationContext` returns a context or throws whenever any goal flag is present.
2. Set `workflowId = input.explicitWorkflowId ?: open(input, goalChild, onOpenFailure)`.

   `open` builds the same `WorkflowServiceOpenFeatureTaskArgs` the CLI builds today, field by field:
   - `kind = TASK_RUNTIME`, `sessionId = ""`, `currentStepId = null`;
   - the raw `input.issueKey`;
   - `repositoryIdentity(input.repoRoot)`;
   - `governedFeatureTaskSpecPath("unassigned", input.repoRoot, Path.of(input.specPath))`;
   - `routeScope` from `goalChild`;
   - `executionPlan = resolveCreation(FeatureTaskRuntimeExecutionPlanCreationRequest(…))`, with the same arguments as today. That includes the lazy `goalContinuation?.codeReviewMode ?: requestedCodeReviewMode ?: runInvariantsSource.read(Path.of(specPath)).codeReviewMode` and `SkeletonDefinition.forRun(goalChild)`.

   It calls `workflowService.openFeatureTask(args)`. `Ok` returns `workflowId`; `Error` calls `onOpenFailure(it)`.

   Open keeps its own issue-key validation, so the open path produces no new failure text.
3. Compute `effectiveInputs = executionPlans.resolveInputs(input.repoRoot, goalContinuation?.qualityGateSelection, goalContinuation?.validationDepth ?: DEFAULT, input.timeout, workflowId)`. This happens before the identity, as today.
4. Compute `identity = repositories.expectedFeatureTaskExecutionIdentity(workflowId, input.issueKey, input.repoRoot, Path.of(input.specPath), scope)`.
5. Call `workerCoordinator.runOwned(workflowId, effectiveInputs, identity, requestedReviewSelection = (goalContinuation?.codeReviewMode ?: input.requestedCodeReviewMode)?.let { RuntimeReviewSelection.valueOf(it.name) })`.

   Inside the block:
   - Read `runInvariantsSource.read(Path.of(input.specPath))`.
   - Build `FeatureTaskRuntimeRunRequest` with exactly today's fields:
     - session id `"${FeatureTaskRuntimePhaseWorkflowDefinition.definition.defaultSessionPrefix}-$workflowId"`;
     - `runInvariants = source.copy(codeReviewMode = admitted.reviewMode ?: source.codeReviewMode, agentAddonSelection = input.agentAddonSelection.persisted)`;
     - `requestedCodeReviewMode = input.requestedCodeReviewMode`;
     - `timeout = input.timeout`.
   - Return `runner.run(request)`.

`runOwned` remains the only lease wrapper. Acquire happens before `block`; heartbeat stop and release happen in its `finally` on success, failure and cancellation. Do not reimplement or wrap it (AC 3).

### Task 3: CLI rewiring (AC 1, AC 4, AC 6)

**`runtime-cli/.../featuretask/FeatureTaskRuntimeRunExecution.kt`**
- Constructor becomes `FeatureTaskRuntimeRunEntry`, `TelemetryService`, `RuntimeDiagnostics`, `CliRunState` and `CliRunInputs`.
- Replace `run` and `execute` with one `internal fun run(options, prepared, explicitWorkflowId: String?)`. It builds `FeatureTaskRuntimeRunInput` from `prepared` and `options`:
  - `timeout = options.maxWallClockMinutes.takeIf { it > 0 }?.minutes`;
  - `requestedCodeReviewMode = options.requestedCodeReviewMode()`;
  - `environment = inputs.environment`;
  - `eventSink = runtimeRunEventSink(inputs, options.monitor)`.
- It then runs `val report = inputs.featureTaskRuntimeRunOverride?.invoke(input) ?: entry.run(input) { throw UsageError("Could not open a feature-task workflow: ${it.error}") }`.
- `completeText`, the payload, the exit code and `drainTelemetryOnCompletion` stay unchanged.
- Remove every import of `FeatureTaskRuntimeRunner`, `FeatureTaskRuntimeWorkerCoordinator`, `FeatureTaskRuntimeExecutionPlanResolver`, `FeatureTaskRuntimeExecutionPlanCreationRequest`, `FeatureTaskRuntimeRunRequest`, `FeatureTaskRuntimeRunInvariantsSource`, `FeatureTaskExecutionIdentity`, `WorkflowServiceOpenFeatureTaskArgs`, `WorkflowService`, `SkeletonDefinition`, `FeatureTaskRuntimePhaseWorkflowDefinition` and `RuntimeReviewSelection`.

**Call sites.** Update the five call sites:
- The three run sites (`FeatureTaskRuntimeCliCommands.kt:139`, `:155` and `FeatureTaskRuntimeAliasCliCommands.kt:45`) pass `options.explicitWorkflowId?.takeIf(String::isNotBlank)`. That is the expression `resolveWorkflowId` uses today.
- The two resume sites (`FeatureTaskRuntimeControlCliCommands.kt:114` and `FeatureTaskRuntimeAliasCliCommands.kt:62`) pass `workflowId`.

**`FeatureTaskRuntimeCliFormatting.kt`.** Delete `openRuntimeWorkflowId` and the `openFeatureTask`, `WorkflowOpenResult` and `WorkflowServiceOpenFeatureTaskArgs` imports. Keep `governedSpecPathForCli`, which the resume check and the repair-identity command still use.

**`FeatureTaskRuntimeRunPreparation.prepareRun`.**
- After `prepare(...)` returns, call `repositories.governedSpecPathForCli(prepared.repoRoot, Path.of(prepared.specPath))` (the injected port from subtask 1) for its validation only, then return `prepared`.
- This keeps both "Governed spec path must…" `UsageError` texts on the run and explicit-run paths, and the engine never sees an invalid path from the CLI.
- It runs after every check `prepare` makes, which is where today's open-path validation sits.
- `prepareResume` already validates in the resume check; leave it.
- Accepted ordering delta: with an explicit workflow id, an invalid spec path now reports before an unknown-workflow error from `resolveInputs`. This is only observable when both faults are present.

**`CliRunInputs`, `CliRuntimeContext` and `CliRuntime`.** Retype `featureTaskRuntimeRunOverride` to `((FeatureTaskRuntimeRunInput) -> FeatureTaskRuntimeRunReport)?` in both models, and keep the pass-through at `CliRuntime.kt:45`. The import of `FeatureTaskRuntimeRunRequest` goes away. The override now intercepts before the open; it is still the one test seam (AC 6).

### Task 4: Continuation protocol test (AC 6)

File: `runtime-cli/src/test/.../featuretask/FeatureTaskRuntimeGoalContinuationProtocolTest.kt`.
- Capture a `FeatureTaskRuntimeRunInput` instead of the request.
- Build the stub report from `input.issueKey`, `input.explicitWorkflowId ?: "wfl-override"` and `featureSize = "SMALL"`. The fixture spec declares SMALL, and no workflow exists before the open.
- Keep every `goalContinuation` assertion and both tests otherwise unchanged.

### Task 5: Engine inbound pins (AC 5)

File: `runtime-core/src/repoTest/kotlin/skillbill/architecture/RuntimeEngineInboundApiTest.kt`.

Add:
- `skillbill.engine.featuretask.runner.FeatureTaskRuntimeRunEntry`
- `skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunInput`

Drop the five types whose only consumer-main references this subtask removes:
- `FeatureTaskRuntimeRunner`
- `FeatureTaskRuntimeWorkerCoordinator`
- `FeatureTaskRuntimeExecutionPlanResolver`
- `FeatureTaskRuntimeExecutionPlanCreationRequest`
- `FeatureTaskRuntimeRunRequest`

A census at planning time (word-boundary simple-name grep over runtime-application, runtime-cli and runtime-mcp `src/main`) found ten more pins with zero references already. AC 5 drops these too:
- `FeatureTaskRuntimePhaseRecorder`
- `FeatureTaskRuntimeBranchSetup`
- `OPERATOR_DECISION_QUALITY_GATE_PHASE_IDS`
- `FeatureTaskRuntimeOperatorDecisionPause`
- `PhaseInstructions`
- `PhaseRunSpecBundle`
- `GoalPlanningPreparationCheckpoint`
- `goalRepositoryIdentity`
- `IdeStatusProjector`
- `IdeStatusResult`

Rerun that census for every pin after Tasks 3 and 7, plus subtask 1, and drop exactly the zero-hit entries. Keep the SKILL-393 edits already in the list. Leave the synthetic-leak test and the declaration-existence test unchanged.

### Task 6: Java command through `HostPlatformPort` (AC 7)

- `runtime-ports/.../ports/system/HostPlatformPort.kt`: add `val javaCommand: String?` with no default body. runtime-ports bans constant-result interface defaults (runtime-kotlin/agent/decisions.md#1ebdf94fce8e).
- `runtime-infra/host/.../host/jvm/JdkHostPlatformPort.kt`: add `override val javaCommand: String? get() = ProcessHandle.current().info().command().orElse(null)`. No architecture scanner matches `ProcessHandle`, so no baseline row is needed.
- `GoalRunCommand` (`runtime-cli/.../goal/core/GoalCliCommands.kt:233`, or wherever subtask 1 left it): pass `javaCommand = hostPlatform.javaCommand`.
- Add `override val javaCommand: String? = null` to the three handwritten substitutes:
  - `SkillBillUninstallCooperativeCancellationTest.StubHostPlatformPort` (runtime-application);
  - `UninstallMutationFailurePolicyTest.StubUninstallHostPlatformPort` (runtime-cli);
  - `CliRunInputsRuntimeTest.StubHostPlatformPort` (runtime-cli).
- `CodexConfigRootsTest` delegates `by JdkHostPlatformPort` and needs nothing. A repo-wide grep found no other implementor.

### Task 7: Move the IdeStatus test (AC 8)

- `git mv` `runtime-cli/src/test/kotlin/skillbill/cli/IdeStatusReadSnapshotConcurrencyTest.kt` to `runtime-core/src/test/kotlin/skillbill/di/core/IdeStatusReadSnapshotConcurrencyTest.kt`.
- Change only the package line to `skillbill.di.core`. The test composes `RuntimeComponent`, `core` is an existing main `skillbill.di` package, and `RuntimeCompositionGuardArchitectureTest` rejects packages that no main source declares.
- Keep the current imports, including `skillbill.ports.idestatus.model.IdeStatusProblemCode`, and every assertion. The file is self-contained, with private helpers only.
- runtime-core's test classpath already has engine, infra host, sqlite and `testFixtures(":runtime-infra:sqlite")` for `ensureTestDatabase`.
- Planning-time census: no other runtime-cli test exercises only engine services. The other files that import nothing from `skillbill.cli` are same-package CLI tests or shared support.

### Task 8: New test (AC 4)

Add one test to `runtime-cli/src/test/.../featuretask/FeatureTaskRuntimePreparationTest.kt`.

The bug it catches: Task 3's prevalidation is dropped or reordered. The engine's `InvalidFeatureTaskExecutionIdentitySchemaError` would then surface with a different stderr and exit code, and a workflow row might be opened.

Steps:
1. Create a temp repository with a `.git` directory and a readable `docs/spec.md`.
2. Run `CliRuntime.run(["--db", db, "feature-task", "run", "SKILL-392", "<abs docs/spec.md>", "--repo-root", repo, "--agent", "claude"], …)`.
3. Use a non-empty `environment` map. An empty map falls back to the host environment.
4. Set `executableLookup = ExecutableLookup { true }`.
5. Assert exit code 1.
6. Assert stderr contains `Governed spec path must be Markdown beneath .feature-specs/.`.
7. Assert no runtime feature-task workflow row exists.

This passes before and after the change, so it pins the text. The sibling `OutsideRepository` text goes through the same seam and gets no second test.

No other new tests:
- Both admission sides now call one derivation, so they cannot drift apart again. The existing worker-coordinator, `WorkerTakeoverFencingTest`, admission, `CliGoalRuntimeTest` and feature-task CLI suites prove the derived strings still match persisted rows.
- Lease release stays owned and tested by `runOwned`.
- The workflow-open failure mapping is a one-line lambda that cannot be reached without fault injection. Review checks its text against the deleted `openRuntimeWorkflowId`.

### Task 9: Static completion checks (no build or test)

Use greps only, no compilation:
- runtime-cli `src/main` has zero hits for the five AC 1 type names, `FeatureTaskExecutionIdentity(`, `openFeatureTask` and `ProcessHandle`.
- The new entry has six private constructor parameters.
- File counts: `featuretask/runner` has 10, `featuretask/model/core` has 12.
- No new file under `runtime-core/src/repoTest`, no baseline edits, no `@Suppress`.
- No `//` comments anywhere in Kotlin.

### Constraints

- Production types added: only `FeatureTaskRuntimeRunEntry` and `FeatureTaskRuntimeRunInput`. No interface, module, architecture-test class, baseline entry or detekt suppression.
- Wire tokens, JSON shapes, contract versions, help text and command order stay unchanged.
- Run preparation and resume verification stay in the CLI.
- `GoalRunnerSubtaskLaunchPrepare` and the `RuntimeComponent` accessors stay untouched.
- Commit in subtask order on the shared feature branch. Build and validate phases own all compilation, detekt, spotless and `./gradlew check`.

## Next Path

skill-bill goal SKILL-392

## Spec Path

.feature-specs/SKILL-392-runtime-cli-boundary-cleanup/spec_subtask_2_engine-owned-feature-task-run.md
