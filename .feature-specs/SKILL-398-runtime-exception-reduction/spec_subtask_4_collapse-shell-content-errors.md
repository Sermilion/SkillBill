# SKILL-398 Subtask 4 - collapse-shell-content-errors

Parent spec: [.feature-specs/SKILL-398-runtime-exception-reduction/spec.md](./spec.md)
Issue key: SKILL-398

## Scope

(F-005) Replace the classes declared in `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/error/shellcontent/` (96 at the census tree, in 9 files: AgentAddon, FeatureTaskRuntime, GovernedReview, Install, Manifest, ReviewContext, Scaffold, SkillStaging and Workflow `*ShellContentErrors.kt`) with `SkillBillRuntimeException` and owner codes, following the parent spec's "Target failure model".

- One code enum per file, in the same package and named for the file's area, for example `InstallFailureCode`, `WorkflowFailureCode`, `ReviewContextFailureCode`, `ScaffoldFailureCode`. Each implements `RuntimeFailureCode`. An enum entry exists per former class that main code discriminates or a test asserts; the others share the area's family entry.
- Where a class already carries a `FailureWireCode` (for example `InvalidFeatureTaskRuntimePhaseOutputSchemaError.failureCode: FeatureTaskRuntimePhaseOutputFailureCode`, `InvalidDecompositionManifestSchemaError.failureCode`), that enum value is the `code`; do not add a parallel entry.
- Message formats move to one function per former class next to the code enum when thrown from more than one site, for example `fun invalidInstallPlanSchema(fieldPath: String, reason: String, cause: Throwable? = null): SkillBillRuntimeException`, and are inlined at a single throw site otherwise. Text is byte-identical, including the `<root>` and `<unknown>` placeholders.
- Typed properties that main code reads today (`reason`, `fieldPath`, `payloadFreeReason`, `projectionName`, `subtaskId`, `phaseId`, `allowedValues`, per investigation.md F-005 and the catch census) are no longer read from an exception. Where the reader needs the value to build a result, the throwing function returns that result instead (tier 2). Where the value only feeds a message or a diagnostic line, the reader uses `message`, or the factory passes the value as part of the message. Do not add properties to `SkillBillRuntimeException`.
- Classes whose condition only a code defect can trigger (the condition cannot arise from user, agent, file, process or network input) become `require`/`check`/`error()` with the same message. When unsure, keep the code.
- Catch sites: `catch (e: <FormerClass>)` becomes `catch (e: SkillBillRuntimeException)` that handles the expected code(s) and rethrows any other code. Where the handler turns the failure into `null`, `emptyMap()` or an `Error` result (for example `WorkflowService.kt:143`, `:165`, `:173`, `VerifyWorkflowStore.kt:59`, `WorkflowStateRepositoryParentDiscovery.kt:76` at the census tree), prefer making the decode boundary return that value and drop the catch.
- Tests: `assertFailsWith<FormerClass>` becomes `assertFailsWith<SkillBillRuntimeException>` plus an assertion on `code`; message assertions stay.
- Delete the shell-content files' classes. If no class in main extends `SkillBillRuntimeException` or `ShellContentContractException` afterwards, finish the transition as the parent spec describes.

Excluded: `UnaddressedFindingsLedgerAbsentError` (subtask 3). If it still exists, leave it.

If the custom-throwable baseline exists, remove the rows of every deleted class.

## Acceptance Criteria

1. No main source declares a class in `skillbill.error.shellcontent` other than `UnaddressedFindingsLedgerAbsentError` if subtask 3 has not removed it; the package holds only code enums and message functions.
2. Each former failure throws `SkillBillRuntimeException` whose `code` is an entry of an enum implementing `RuntimeFailureCode` declared in that package (or the existing `FailureWireCode` value it carried), or fails through `require`/`check`/`error()` where only a code defect can trigger it.
3. Every user-visible message is byte-identical; no expected-output, wire-fixture or payload assertion is edited other than replacing an exception-type assertion with a code assertion.
4. No main code reads a typed property from a caught exception that this subtask replaced.
5. No main source declares a typealias named after a deleted class.
6. If the custom-throwable baseline exists, it lists none of the deleted classes and `FailureCodeTotalityArchitectureTest` passes.

## Non-Goals

- Classes outside `skillbill.error.shellcontent` (subtask 5), and the classes named in subtasks 2 and 3.
- Renaming the `skillbill.error.shellcontent` package (SKILL-372 retention; it may hold only enums after this subtask).
- The CLI and MCP top-level arms (F-008).

## Dependency Notes

Depends on: none.
If subtask 1 has not landed, add the target-type pieces as the parent spec defines them. If subtask 5 finished first and left `ShellContentContractException` with only shell-content subclasses, this subtask finishes the transition. Coordinates with SKILL-391 (moved `InvalidMcpToolArgumentError` out; nothing to do here), SKILL-390, SKILL-396 and SKILL-397, which edit throw sites in their modules; the second lander keeps both edits. SKILL-392 subtask 2 throws `InvalidFeatureTaskExecutionIdentitySchemaError` from the engine `FeatureTaskRuntimeExecutionEntry` (via `FeatureTaskExecutionIdentityPolicy.normalizeIssueKey`) and maps a `WorkflowOpenResult.Error` to a CLI `UsageError`. If it has landed, convert that throw site to the code and confirm the `UsageError` text is unchanged; if not, SKILL-392 rechecks them when it lands.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite. Existing tests pass with type-to-code assertion edits only. No new behavioural test is required unless a catch site became a returned value, in which case one test asserts the caller's branch.

## Next Path

skill-bill goal SKILL-398

## Spec Path

.feature-specs/SKILL-398-runtime-exception-reduction/spec_subtask_4_collapse-shell-content-errors.md
