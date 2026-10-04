# SKILL-398 Investigation - runtime exception reduction

Scope: every Kotlin main source set under `../../../runtime-kotlin` (runtime-contracts, runtime-domain, runtime-ports,
runtime-application, runtime-engine, runtime-infra/*, runtime-cli, runtime-mcp, runtime-core). Test sources are
counted only where they pin exception types.

Census tree: `feat/SKILL-391-runtime-contracts-kernel-ownership-cleanup` at `8cea54bafc732f8e4ff79333aff18939797893b4`
(SKILL-391 committed, base `base/SKILL-380-phase-slot-strategies` at `3f2b96cde1e930672cdeaf96ed753a4a2c306600`).
The counts were taken with scripted scans (class-declaration graph closed over `Throwable` supertypes, then
per-name throw, catch and test-reference counts). They are heuristics; every finding below cites the files it
rests on, and each subtask restates its anchors as rules, not counts.

## Intended principle

The user's rule, adopted here:

1. Use as few custom exceptions as possible. A custom exception type has to earn its place.
2. An exception means a case the runtime does not expect. Throwing is a deliberate act to end execution.
3. Everything the runtime does expect (absent, refused, conflicting, invalid input it can report) flows downstream
   as a result: a sealed interface, a nullable value, or an existing outcome type. The caller decides what to do.

One refinement, agreed in the review of this investigation: error codes do not go into `IllegalStateException`.
Kotlin's `require`, `check` and `error()` signal code defects. If an edge has to catch `IllegalStateException` to
read a code, it also catches real bugs and reports them as user errors. The target model has three tiers:

| Tier | Meaning | Mechanism | Caught where |
|---|---|---|---|
| 1. Defect | A broken invariant only a code change can cause | `require`, `check`, `requireNotNull`, `error()` | Only by the top-level crash handlers |
| 2. Expected outcome | Absent, refused, conflicting, invalid input the runtime anticipates | Sealed result, nullable, or an existing outcome type, returned by the function that knows | Nowhere; the caller branches on the value |
| 3. Anticipated failure that ends the run | Corrupt durable state, malformed contract input, I/O failure, with nothing to do but stop and tell the operator | One type: `SkillBillRuntimeException(code, message, cause)`, where `code` is an owner-declared enum entry | At the CLI and MCP edges, and at a boundary that degrades (for example the rejected-output recorder) by checking `code` |

A new custom `Throwable` subclass is allowed only when a failure must cross a boundary the runtime does not own and
cannot be expressed as tier 3, and the reason is recorded in `../../../runtime-kotlin/agent/decisions.md`. None qualifies
today.

## Census

| Measure (main sources) | Count | Evidence |
|---|---|---|
| Custom `Throwable` types | 231 | 165 runtime-contracts, 33 runtime-engine, 16 runtime-infra, 10 runtime-domain, 6 runtime-application, 1 runtime-mcp |
| Subclasses of `ShellContentContractException` | 128 | `runtime-contracts/src/main/kotlin/skillbill/error/core/RuntimeExceptionBases.kt:8`; 96 of them in `skillbill/error/shellcontent/` (9 files) |
| Of which `Invalid*SchemaError` | 31 | one per contract, as AGENTS.md:60 prescribes |
| Thrown but never caught by their own type in main | 143 (62%) | Only formatting a message |
| Never constructed | 1 | `CursorReviewStreamEmptyError` (`runtime-infra/launcher/.../review/CursorReviewStreamErrors.kt:12`) |
| Declared twice | 1 | `RuntimeOwnedFactUnavailable` in `runtime-application/.../runtimepersistence/RuntimeOwnedPersistenceBoundary.kt:9` and `runtime-engine/.../featuretask/persist/RuntimeOwnedPersistenceBoundary.kt:9` |
| `throw` statements | 1,333 | infra 611, engine 261, domain 225, application 110, cli 74, mcp 22, contracts 14, ports 11, core 1 |
| Catch blocks (excluding cancellation and interruption) | 433 | 122 catch one type and throw another; 29 swallow to null, empty or false |
| `catch (e: IllegalArgumentException / IllegalStateException)` | 97 | infra 42, domain 27, cli 11, application 9, engine 6, contracts 1, ports 1 |
| `runCatching` | 394 | engine 101, infra/skills 77, infra/launcher 46, application 40, infra/workflow 31, others under 21; 5 use `getOrElseUnlessCooperative` |
| Sealed result families (`*Result`, `*Outcome`, `*Decision`, `*Admission`, `*Status`) | 69 | The result style already exists beside the exception style |
| Tests pinning a custom type with `assertFailsWith<X>` | 1,034 in 236 files | Top: `InvalidWorkflowStateSchemaError` 122, `InvalidReviewContextSchemaError` 65, `InvalidManifestSchemaError` 59 |
| `FailureWireCode` enums | 4 | `FailureCodeTotalityArchitectureTest.kt:51-57` |

### What the edges do with the types

- CLI: `runtime-cli/src/main/kotlin/skillbill/cli/core/CliRuntime.kt:68-77` maps every `SkillBillRuntimeException`
  to `diagnosticResult(..., "Command failed.")` and every `IllegalArgumentException` to "Invalid command argument.".
  Per-command catches of `ShellContentContractException` (for example `PhaseCommand.kt:95`, `ConfigCommand.kt:65`,
  `CodeReviewCommand.kt:265`) print `error.message` and exit 1.
- MCP: `runtime-mcp/src/main/kotlin/skillbill/mcp/core/McpToolDispatcher.kt:26-30` returns the same
  `mcpToolErrorResult` for `ShellContentContractException`, `InvalidLearningSourceError`, `IllegalArgumentException`
  and `IllegalStateException`.

So the class identity of most of the 231 types is discarded at the edge. What the user sees is the message.

## Findings

### F-001 (P1) - The written policy requires a class per contract

- `docs/code-principles.md:34-45` (Failure Contracts): "Untrusted input at a named parse boundary returns a typed
  contract failure", preferred shape "`Invalid*SchemaError` or domain-specific typed failures".
- `AGENTS.md:60`: a new contract needs a "typed `Invalid<Contract>SchemaError`".
- `runtime-kotlin/ARCHITECTURE.md:380`, `:394`, `:636`: the `skillbill.error` "runtime exception taxonomy".
- Retentions: SKILL-349 investigation line 45 ("Typed errors ... are useful"), SKILL-349 spec AC 8 ("Keep ...
  typed errors"), SKILL-374 and SKILL-391 ("the other 93 single-owner errors" stay).
- `runtime-kotlin/agent/history.md:845` records the fix pattern "map broad catch to the boundary's typed failure
  family", which detekt's default `TooGenericExceptionCaught` pushes toward.

**Supersedes:** the "typed errors" retention of SKILL-349, SKILL-374 and SKILL-391. New evidence: 62% of the types
are never discriminated, both edges discard the type, and the user set the opposite principle on 2026-10-01. The
SKILL-391 placement decisions (which module owns which vocabulary) stay; this bundle keeps codes with the same
owners.

Fix: subtask 1.

### F-002 (P1) - Nothing stops a new exception type

No architecture test or detekt rule counts `Throwable` declarations. The existing two-sided baseline mechanism
(`ArchitectureBaselineSupport.kt`, stale rows fail in `ArchitectureScanGuardSupport.kt:307`) fits: a baseline that
lists today's declarations, rejects new ones and forces deleted ones out.

Fix: subtask 1.

### F-003 (P1) - Expected outcomes are thrown and turned back into results

- **Operations.** `runtime-engine/src/main/kotlin/skillbill/engine/operation/core/Operation.kt:16-17` documents
  that "A pre failure throws an `OperationRefusalError` ... or an `OperationUsageError`". 17 refusal subclasses
  (dirty worktree, PR not found, branch behind remote, stale token) and 9 usage subclasses live in
  `engine/operation/core/OperationErrors.kt`; the usage base is in
  `runtime-contracts/.../error/operation/OperationUsageError.kt`. `OperationExecutor.kt:49` catches the refusal base
  and returns `OperationOutcome.Blocked(refusal.message)`, so the result type already exists.
  `OperationConfirmationGate.kt:63` and `PrReviewFixOperation.kt:44` already return the exception objects as
  values. `runtime-cli/.../operation/OperationCommand.kt:79` catches the usage base.
- **Rejected-output diagnostics.** `RejectedOutputDiagnosticError` (sealed, 11 variants,
  `runtime-contracts/.../error/core/RejectedOutputDiagnosticError.kt`) is thrown by
  `runtime-infra/sqlite/.../SqliteRejectedOutputDiagnosticRepository.kt` (`Absent`, `Expired`, `Conflict`,
  `Persistence`, `Corrupt`) and `runtime-application/.../diagnostics/RejectedOutputDiagnosticService.kt`
  (`InvalidRequest`). `runtime-engine/.../featuretask/lifecycle/core/FeatureTaskRuntimeRejectedOutputRecorder.kt:35-45`
  and `:187-221` catch them and convert to the sealed `Unreadable` / `DiagnosticWriteOutcome.Degraded` results.
  `Absent`, `Expired`, `Oversized` and `Conflict` are normal outcomes of a read or insert. The port
  `runtime-ports/.../diagnostics/RejectedOutputDiagnosticRepository.kt:10-14` declares `read(identity):
  RejectedOutputDiagnosticRecord`, which can only report absence by throwing.
  `RejectedOutputDiagnosticAmbiguousSelectorError` (application model) is the same pattern.
- **Required phase writes.** `RequiredPhaseWriteRejected : IllegalStateException`
  (`runtime-engine/.../featuretask/slot/state/RequiredPhaseWriteRejected.kt:8`) is thrown by
  `FeatureTaskRuntimePhaseStateRecorder.kt:56` and `FeatureTaskRuntimePhaseBriefingRecorder.kt:42`, then caught or
  `is`-checked in `GoalPlanningSharedPreplanSettlement.kt:266`, `GoalPlanningSharedPreplanProduction.kt:41`,
  `GoalPlanningPhaseAttemptGate.kt:35`, `GoalPlanningStepAttempts.kt:38` and
  `FeatureTaskRuntimeRunLoopStepBindings.kt:343` to steer the planner. It extends `IllegalStateException`, so
  every catch also catches real defects.

Fix: subtask 2.

### F-004 (P1) - Local control-flow exceptions and branching on message text

Each of these is thrown and caught inside the runtime to carry an expected outcome:

| Type | Thrown | Caught |
|---|---|---|
| `RefreshRefused` | `GoalPlanningSharedPreplanSettlement.kt:230` | same file `:121` (reads `reason`) |
| `GoalRunnerExecutionAlreadyRunningException` | `GoalRunnerExecutionCoordinator.kt:105`, `:360` | `GoalRunner.kt:61` |
| `GoalRunnerLaunchAuthorizationDeniedException` | `GoalRunnerControlCoordinator.kt:222`, `:265` | `GoalPlanningPhaseAttemptGateBurstCap.kt:36` (reads `pauseReason`), `GoalRunnerSelectedSubtaskLoop.kt:186` |
| `UnaddressedFindingsLedgerAbsentError` | `UnaddressedFindingsLedgerService.kt:28`, `:44`, `:79` | `ResolveUnaddressedFindingsLedger.kt:14`, `GoalRunnerFinalization.kt:373` |
| `SpecIntentSourceUnavailable` | `SpecIntentProjectionExtractor.kt:124` | `SpecIntentProjectionResolver.kt:90`, `:126`, `:156` (reads `specPath`) |
| `MissingCarriedForwardGoalReviewResultException` | `InlineReviewPreparation.kt:238` | same file `:178` |
| `UsageValidationException`, `StackDetectionException`, `DiffResolutionException` | `application/review/parallel/planning/*` and `reviewevidence/SharedReviewEvidenceAssembly.kt` (17 sites) | `CodeReviewCommand.kt:259-263`, `CodeReviewStep.kt:385-387`, `FeatureTaskRuntimeSharedReviewEvidenceResolver.kt:40` |
| `ReviewContextBudgetExceededException` | `runtime-domain/.../review/context/model/hunk/ReviewContextBudgetModels.kt:74` | `CodeReviewStep.kt:389` |
| `ReviewRegisterParseSeamException` | `ParallelCodeReviewRunnerFailureAdmission.kt:145`, `:147` | same file `:45`, `:189` |
| `ClaudeMcpProfileFailure : IllegalArgumentException` | `McpRegistrationOperations.kt:139` | `UninstallMutations.kt:96`, `InstallMcpCliCommands.kt:64` (read `succeeded`) |
| `SkillRemovalRefusedException` | `runtime-domain/.../skillremove/SkillRemovalRefusal.kt:9`, `TargetValidation.kt:24` | `RemoveCliCommandExecution.kt:41` |
| `InvalidExecutionMatrix`, `InvalidCompactionSettings`, `InvalidValidationGateRepoConfig` | `runtime-domain/.../config/model/*Models.kt` | the same file, to build the existing `Invalid` parse result (`ExecutionMatrixModels.kt:77`, `CompactionSettingsModels.kt:59`, `ValidationGateRepoConfigModels.kt:27`) |

Three sites decide behaviour from message text, which shows a missing result or code:

- `runtime-engine/.../goalrunner/persist/GoalContinuationArtifactCodec.kt:28`: `error.message.orEmpty().contains("mode='")`.
- `runtime-engine/.../goalrunner/planning/outcome/GoalPlanningSweepOutcomeDerivationTerminalClass.kt:48`:
  `error.message?.contains("must be completed with non-empty produced_outputs")`.
- `runtime-infra/contracts/.../workflow/decomposition/DecompositionManifestSchemaValidator.kt:122`:
  `error.message.orEmpty().contains("duplicate", ignoreCase = true)`.

Fix: subtask 3.

### F-005 (P2) - Most types are message templates

143 types are never caught by their own type. Typical shape (`InstallShellContentErrors.kt:5-12`): a class whose
only job is to format "Install plan fails schema validation at '<path>': <reason>". The 31 `Invalid*SchemaError`
classes differ only in the contract name inside the message. `InvalidWorkflowStateSchemaError` alone has 112 throw
sites and 14 catch sites, most of which turn it into `null`, `emptyMap()` or an `Error` result
(`WorkflowService.kt:143`, `:165`, `:173`; `VerifyWorkflowStore.kt:59`;
`WorkflowStateRepositoryParentDiscovery.kt:76`).

Some types report code defects, not input: registry and wiring failures in
`runtime-contracts/.../error/featuretask/PhaseSlotContractErrors.kt` (`UnknownPhaseStepError`,
`DuplicatePhaseStrategyError`, `InvalidSkeletonDefinitionError`), `DuplicateOperationIdError`
(`OperationErrors.kt`), `InvalidPhaseStrategyCompositionError : IllegalArgumentException`. Those are tier 1.

Split for review size: the 96 types in `skillbill/error/shellcontent/` (subtask 4) and the remaining 76 (kernel
`error.core`, `error.featuretask`, `error.goalrunner`, `error.learning`, and module-local types in engine, infra,
domain, application, mcp; subtask 5).

### F-006 (P2) - `require` used as input validation, then caught

97 `catch (IllegalArgumentException | IllegalStateException)` sites. A scripted split by the `try` body: about 45
wrap a runtime `require`, `check` or decoder; 6 wrap a JVM or library API with no non-throwing form
(`HttpRequestUri.kt:11`, `JsonCodec.kt:111`); about 46 need reading. Examples:

- `runtime-domain/.../goalrunner/AttemptLedgerDecoding.kt:73` catches the decoder's `IllegalArgumentException` and
  rethrows `InvalidGoalProgressEventSchemaError`.
- `runtime-domain/.../review/parallel/ParallelReviewFindingParser.kt:203`, `:221` call
  `requireRepositoryRelativePath` and turn the exception into a rejection value.
- SKILL-392 (investigation lines 122, 355, 377) records that the CLI catches `IllegalArgumentException` at 11 sites
  because `RuntimeOwnedReviewMode.parse`, `decodeScaffoldPayloadObject` and `validateReleaseRef` report malformed
  input with `require`, and leaves the fix to the throwing owners as a follow-up. Subtask 6 is that follow-up.

Each such catch also catches real defects from the same call tree. Fix: subtask 6.

### F-007 (P2, recorded, not in this bundle) - `runCatching` catches everything

394 `runCatching` calls in main. About 258 turn the failure into a value (`getOrElse` 89, `getOrNull` 70,
`onFailure` 38, `fold` 22, `getOrDefault` 20, `exceptionOrNull` 19). `runCatching` catches `Throwable`, including
`CancellationException`, `InterruptedException` and `Error`. Only 5 use
`runtime-application/.../CooperativeFailurePropagation.kt:13` `getOrElseUnlessCooperative`, which infra and domain
cannot reach. This is a per-site judgment sweep (what can each block throw?) across 15 modules, so it is left for a
follow-up issue. Subtasks 2-6 replace any `runCatching` they touch with a direct call or a narrow catch.

### F-008 (P2, recorded, not in this bundle) - The edges report defects as user errors

`CliRuntime.kt:71` reports any `IllegalArgumentException` as "Invalid command argument."; `McpToolDispatcher.kt:29-30`
returns an ordinary tool error for `IllegalArgumentException` and `IllegalStateException` and skips the telemetry
capture that its `is Exception` arm does. Under tier 1 both should treat them as internal errors. That is only safe
once no user-reachable path reports input through `require`, which needs a census of the 1,502
`require`/`check`/`requireNotNull`/`checkNotNull` calls by reachability. Subtask 6 removes the known user-input
`require` paths (F-006); the edge arms change in a follow-up.

## What stays unchanged

| Item | Reason |
|---|---|
| `CancellationException` and `InterruptedException` propagation, including `CooperativeFailurePropagation.kt` | Correct today; the policy keeps it. |
| The four `FailureWireCode` enums and `FailureCodeTotalityArchitectureTest` | They are already codes. Phase-output and decomposition-manifest failures reuse them as the tier-3 code. |
| `TypedParseBoundaryArchitectureTest` and `PrincipleEnforcementInventory.parseBoundarySites` | Still right under tier 1: a parse boundary must not report untrusted input with `error()` or `require`. Only its message wording changes ("typed contract failure" becomes "a result or a `SkillBillRuntimeException` code"). |
| CLI and MCP output text, exit codes and MCP `isError` payloads | Every message keeps its exact text; the edges see the same message through the same base. |
| Module ownership of failure vocabulary from SKILL-374 and SKILL-391 | Codes live with the owner that declared the classes (for example `InvalidMcpToolArgumentError` stays MCP vocabulary as an MCP-owned code). |
| Library and framework exceptions (`CliktError`, clikt `UsageError`, `SerializationException`, `IOException`, `SQLException`) | Not ours. |
| Test-source `Throwable` subclasses (3 in runtime-engine tests) | Test doubles, out of scope. |
| `runCatching` outside touched code (F-007), edge arms (F-008) | Recorded follow-ups. |

## Over-engineering register

| Candidate | Decision |
|---|---|
| One global code enum in runtime-contracts | Rejected: it would pull MCP, infra and engine vocabulary back into the kernel against SKILL-391 F-001 and F-007. Codes are owner-declared enums implementing one marker interface. |
| A `Result<T, E>` / `Either` library or a custom monad | Rejected: Kotlin sealed interfaces and nullables cover every case here, and 69 sealed result families already exist. |
| A shared test helper module for asserting codes | Rejected: `assertFailsWith<SkillBillRuntimeException>` plus one `assertEquals` on `code` is two lines. |
| Exception "family" metadata on codes | Rejected: catch sites that need a family check the owner enum type (`code is RejectedOutputDiagnosticFailureCode`). |
| Typealiases from old class names to the new type | Rejected: a typealias keeps the old vocabulary alive and evades the baseline. |
| Keeping one code per former class when nothing reads it | Rejected: leaves that no main code discriminates and no test asserts share their file's family code. |

## Rejected options

- **`IllegalStateException` carrying a code** (the user's first sketch). Rejected for the reason in "Intended
  principle": it merges defects with anticipated failures. Agreed with the user before this bundle was written.
- **Keep the classes, add a guard only.** Rejected: it freezes 231 types without addressing F-003 to F-006.
- **Convert every tier-3 failure into a result.** Rejected: corrupt durable state and I/O failure end the run;
  threading them through every return type would bury the expected outcomes the user wants visible. Kotlin's own
  guidance (exceptions for failures the caller cannot handle locally, results for the rest) matches the tier split.

## Coordination with concurrent bundles

| Bundle | Overlap | Rule |
|---|---|---|
| SKILL-391 (runtime-contracts kernel ownership) | Moved the operation errors into `runtime-engine/.../operation/core/OperationErrors.kt` and `InvalidMcpToolArgumentError` into runtime-mcp; committed on its feature branch at `8cea54baf`. | Subtasks 2 and 5 edit those classes where they are on the tree they run on. |
| SKILL-392 (runtime-cli) | Its F-001 moves the feature-task run into an engine entry; it recorded the IAE follow-up that subtask 6 performs. Its subtask 2 (confirmed by its planning session on 2026-10-01) also (a) moves identity derivation into the engine `FeatureTaskRuntimeExecutionEntry`, which throws `InvalidFeatureTaskExecutionIdentitySchemaError` through `FeatureTaskExecutionIdentityPolicy.normalizeIssueKey`, and (b) carries a `WorkflowOpenResult.Error` from the engine to a CLI `UsageError` without a new type. | Second lander keeps both edits; subtask 6 edits whichever CLI files still catch IAE. Whichever of SKILL-392 subtask 2 and SKILL-398 subtask 4 lands second rechecks both sites: the identity throw becomes the `FeatureTaskRuntime` area code, and the `WorkflowOpenResult.Error` mapping keeps its `UsageError` text. SKILL-398 does not change the `SkillBillRuntimeException` arm at `CliRuntime.kt:72`. |
| SKILL-390 (runtime-engine) | Touches many engine files, counts 127 engine `runCatching`. | Second lander rebases and keeps both edits. |
| SKILL-396 (runtime-infra) | Converts 4 `catch (_: Exception)` sites to typed errors. | If 396 landed, its new typed errors fall under subtask 5's rule (become codes) when subtask 5 runs; if not, nothing to do. |
| SKILL-397 (runtime-domain) | Non-goal: "Replacing `require` and runCatching error reporting in domain". | Subtask 6 is that work, applied to the domain files present when it runs. |

## Limits

- Throw, catch and test counts come from regex scans, not a compiler. Nested names (for example
  `RejectedOutputDiagnosticError.Absent`) and helper factories (`journalError`, `failure`) blur per-class throw
  counts. Subtask criteria therefore name rules and anchors, not numbers.
- Reachability of `require` from user input (F-008) was not measured.
- The 46 unclassified IAE/ISE catches (F-006) were not read one by one; subtask 6 states the rule per kind.
