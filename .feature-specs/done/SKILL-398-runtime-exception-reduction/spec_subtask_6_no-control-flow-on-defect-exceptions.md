# SKILL-398 Subtask 6 - no-control-flow-on-defect-exceptions

Parent spec: [.feature-specs/SKILL-398-runtime-exception-reduction/spec.md](spec.md)
Issue key: SKILL-398

## Scope revision (2026-10-02)

The first implement attempt blocked as too large. Its scope was about 85 IAE/ISE catch sites in domain, infra, application, engine, cli, ports and contracts, plus four port reshapes and the heartbeat arm. That is too wide for one implement phase that cannot compile. This subtask now does two things:

- the SKILL-392 follow-up (scope item 4 and AC-003 below);
- the four shared domain validators the attempt already added.

The catch-site conversions moved to the follow-up bundle `../../SKILL-401-defect-exception-control-flow`. It has 7 independent subtasks: two domain decoder slices, engine/ports/contracts, config and telemetry reads, application and infra adapters, infra skills, and the CLI install/scaffold/agent add-on sites. Leave those sites unchanged here. `spec.md` was not changed, so the shared preplan's parent-spec hash stays valid.

**Work already on the tree.** The blocked attempt left these uncommitted edits, which were never compiled:

- domain validators `CodeReviewExecutionMode.fromWireOrNull`/`unknownWireValueMessage`, `ValidationDepth.fromWireOrNull`/`unknownWireValueMessage`, `parsePersistedInstantOrNull` and `repositoryRelativePathViolation`, with the old throwing forms delegating;
- `RuntimeOwnedReviewMode.parse` returning a nullable plus `unknownModeMessage`;
- `decodeScaffoldPayloadObject` returning a nullable plus `SCAFFOLD_PAYLOAD_NOT_OBJECT_MESSAGE`;
- `RepoValidationGateway.validateReleaseRef` returning a sealed `ReleaseRefValidation`, with `ReleaseLicensePolicyError` and its baseline row deleted;
- the CLI review-mode sites throwing `UsageError` directly;
- repo validation mapping `Valid`/`Rejected`;
- scaffold payload readers throwing `InvalidScaffoldPayloadError`, with `runPayload` branching on null;
- `RuntimeOwnedReviewModeTest` and `RepoValidationReleasePolicyTest` edited for exception type only.

Keep every edit that matches this spec, fix what does not compile, and finish the rest.

## Scope

(F-006, SKILL-392 follow-up slice.) Paths are relative to `../../../runtime-kotlin`, and `.../` stands for `src/main/kotlin/skillbill/`. Apply the ground rules in `.feature-specs/SKILL-401-defect-exception-control-flow/spec.md` "Ground rules".

1. **Shared domain validators.** Add or keep exactly as SKILL-401 `spec.md` "Shared validators" defines them:
   - `CodeReviewExecutionMode.fromWireOrNull`;
   - `ValidationDepth.fromWireOrNull`;
   - `parsePersistedInstantOrNull`;
   - `repositoryRelativePathViolation`.

   Each throwing form delegates to its new form, so the text has one source. No catch site is converted here; SKILL-401 converts them.
2. **SKILL-392 follow-up.** `RuntimeOwnedReviewMode.parse` (`runtime-application/.../review/service/RuntimeOwnedReviewMode.kt`), `decodeScaffoldPayloadObject` (`runtime-application/.../scaffold/ScaffoldCommandRequestDecoder.kt`) and `validateReleaseRef` (`runtime-infra/skills/.../scaffold/runtime/validation/RepoValidationRuntime.kt`, behind the `RepoValidationGateway` port) stop reporting malformed user input with `require`:
   - `RuntimeOwnedReviewMode.parse(value)` returns `CodeReviewExecutionMode?`. Add `unknownModeMessage(value)`, which returns `"Unknown code-review execution mode '$value'. Allowed: auto, inline."`.
   - `decodeScaffoldPayloadObject(payloadText)` returns `JsonObject?`. Add a `const val` holding `"Invalid JSON payload: expected an object."`. `decodeScaffoldCommandRequest(payloadText)` is reached through MCP `ScaffoldInvocation` and is handled only at the edge, so it keeps throwing IAE with that same constant.
   - `validateReleaseRef`:
    - Change `RepoValidationGateway.validateReleaseRef` (`runtime-ports/.../ports/validation/`) to return a sealed `ReleaseRefValidation { Valid(metadata); Rejected(message) }` in `ports/validation/model`.
    - In `RepoValidationRuntimeReleasePolicy`:
      - Add a non-throwing `parseReleaseRefOrNull`, keeping `"Release tag must match canonical vMAJOR.MINOR.PATCH with optional SemVer prerelease/build metadata."`.
      - The force-prerelease check and `RepoValidationRuntimeReleasePolicyGate` return `Rejected` with their current texts.
    - `FileSystemRepoValidationGateway` and `RepoValidationRuntime.validateReleaseRef` pass the result through.
    - Delete `ReleaseLicensePolicyError` and its baseline row if this removes its last use (SKILL-401 ground rule 10).
3. **CLI sites** (`runtime-cli/.../cli/`).
   - **Review mode:** `featuretask/FeatureTaskRuntimeRunRequestAssembly.kt:142` and `goal/run/GoalCliRunCommands.kt:94` each become `RuntimeOwnedReviewMode.parse(raw) ?: throw UsageError(RuntimeOwnedReviewMode.unknownModeMessage(raw))`. Both stop calling `usageError(error)`, so their wrap-and-`initCause` path is gone. The shared `usageError` in `kernel/cli/DocumentedCliCommand.kt` stays for its other callers (`OperationCommand`, `PhaseCommand`, `CodeReviewCommand`), which don't catch IAE.
   - **Release ref:** `repovalidation/RepoValidationCliCommands.kt:116` becomes a `when` over `ReleaseRefValidation`. `Rejected` produces exactly today's JSON payload `{status: failed, error: message}` or the text `"$message\n"`, with exit code 1.
   - **Scaffold payload object:** `runPayload` and `ScaffoldPayloadInputs.readScaffoldPayload` map a null `decodeScaffoldPayloadObject` result to the constant message through the existing scaffold payload failure, which `completeScaffoldError` prints with the same text and exit code. The other IAE sources behind the `NativeScaffoldPayloadRun` and `ScaffoldWizardRun` catches belong to SKILL-401 subtask 7, so those catches stay here.
   - If the two wrap-and-`initCause` bodies (`runCatching { usage.initCause(error) }` in `FeatureTaskRuntimeRunRequestAssembly.kt` and `GoalCliRunCommands.kt`) still exist, they go.
4. **Tests.**
   1. A CLI test for the review mode. Run `goal` (or the phase-agent command) with `--code-review-mode delegated`. Assert the clikt `UsageError` stderr text containing `"Unknown code-review execution mode 'delegated'. Allowed: auto, inline."` and its exit code, using the existing CLI test harness. Bug it catches: the null branch maps to the wrong message, or falls through to the CliRuntime arm.
   2. A CLI test for the scaffold payload. Use a scaffold payload command with a `--payload` file containing `[]`. Assert `"Invalid JSON payload: expected an object."` through `completeScaffoldError`, with the exit code and output stream unchanged.
   3. A repoTest for the release ref, next to the existing tests in `runtime-cli/src/repoTest/.../cli/CliRepoValidationRuntimeTest.kt`. Run `validate-release-ref not-a-tag` in text format and assert exit 1 and stdout `"Release tag must match canonical vMAJOR.MINOR.PATCH with optional SemVer prerelease/build metadata.\n"`. The existing license-policy test covers the `Rejected` policy branch.
   - Edit only exception types, never expected text: `RuntimeOwnedReviewModeTest` (`assertFailsWith<IllegalArgumentException>` becomes `assertNull(parse(value))` plus an `assertEquals` on `unknownModeMessage(value)`), tests of `decodeScaffoldPayloadObject` and `parseReleaseRef`/`validateReleaseRef`, and the `RepoValidationGateway` test fakes.

## Acceptance Criteria

1. The four shared domain validators exist as SKILL-401 `spec.md` defines them, and each throwing form delegates to its new form.
2. `RuntimeOwnedReviewMode.parse`, `decodeScaffoldPayloadObject` and `validateReleaseRef` do not throw `IllegalArgumentException` for malformed input. runtime-cli has no `IllegalArgumentException` catch for the review-mode or release-ref paths. The CLI prints the same `UsageError` texts and exit codes as before.
3. `ReleaseLicensePolicyError` and its `custom-throwable-baseline.txt` row are gone if nothing else uses it.
4. Every user-visible message and every persisted byte is unchanged. Existing tests pass with only exception-type assertion edits where a validator now returns a value.
5. `TypedParseBoundaryArchitectureTest` and detekt pass, and no `ParseBoundarySite` entry is dropped.
6. The SKILL-401 catch sites are unchanged.

## Non-Goals

- The IAE/ISE catch-site conversions in domain, engine, ports, contracts, application, infra and the remaining CLI sites (SKILL-401).
- Changing `CliRuntime.kt` or `McpToolDispatcher.kt` classification (F-008).
- The repo-wide `runCatching` sweep (F-007).
- Replacing `require`/`check` that guard true invariants.

## Dependency Notes

Depends on: none. It performs the follow-up SKILL-392 recorded, and applies to the files as they are after SKILL-399, SKILL-400 and SKILL-401 if any of them landed first. The second lander keeps both edits.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite. Test obligations: the three CLI tests in scope item 4. Run nothing in implement; build, tests, detekt and repoTest belong to the build and validate phases.

## Implementation Details

Planned on the working tree over `b29f42907` (subtask 5 landed). Most of the scope is already in the uncommitted edits the blocked attempt left. The plan keeps those edits, fixes the few that break the build or drift out of scope, and adds the three tests. Paths are relative to `../../../runtime-kotlin`, and `.../` stands for `src/main/kotlin/skillbill/`.

### What the census found

- **Callers.** `RuntimeOwnedReviewMode.parse` has two callers, both in runtime-cli. `RepoValidationGateway.validateReleaseRef` has one caller (`ValidateReleaseRefCommand`) and one implementation (`FileSystemRepoValidationGateway`), with no test fakes. `decodeScaffoldPayloadObject` has three callers: `NativeScaffoldPayloadRun.runPayload(Map)`, `ScaffoldPayloadInputs.readScaffoldPayload`, and `decodeScaffoldCommandRequest(String)` on the MCP path. So no MCP path changes type (ground rule 1).
- **`ReleaseLicensePolicyError`** has no reference left in main, test or repoTest. Its baseline row is already removed.
- **`InvalidScaffoldPayloadError`** still exists in `runtime-contracts/.../error/shellcontent/ScaffoldShellContentErrors.kt` as a `ScaffoldError : SkillBillRuntimeException`, with a baseline row. SKILL-399 owns collapsing it, so it stays here.
- **`readScaffoldPayload`** is called only from `NativeScaffoldPayloadRun.runPayloadFile`. That function catches `SkillBillRuntimeException` and IAE, and maps both to `completeScaffoldError(message)`. So the null branch keeps the same stdout and exit code whether it throws `InvalidScaffoldPayloadError` or IAE.
- **Parse-boundary sites.** None of the touched files is a `ParseBoundarySite` in `runtime-core/src/repoTest/.../architecture/PrincipleEnforcementInventory.kt`. No entry is edited or dropped.
- **Ports guard.** `PortsDeclarationArchitectureTest.forbiddenTopLevelClassViolations` flags only lines that start with `class`, `internal class`, `abstract class` or `open class`. So `sealed interface ReleaseRefValidation` with nested `data class` variants is accepted.
- **Line length.** The limit is 120 (`.editorconfig` `max_line_length` and detekt `MaxLineLength`). Two added lines exceed it: `PersistedInstant.kt` (121) and `RepoValidationRuntime.kt` `validateReleaseRef` (124).
- **Remaining `usageError` callers.** The shared `usageError` in `kernel/cli/DocumentedCliCommand.kt` is still called by `PhaseCommand.kt:96` and `CodeReviewCommand.kt:264`, so it stays.
- **Quality-gate `initCause`.** The `runCatching { usage.initCause(error) }` left in `FeatureTaskRuntimeRunRequestAssembly.kt:96` belongs to `parseQualityGateSelection`, which catches `UnknownQualityGateSelectionError` and not IAE. It is not a review-mode body and is not touched (F-007 is a non-goal).

### Tasks, in order

1. **Domain validators** (AC-001, AC-004, AC-005).
   - Keep these edits:
     - `CodeReviewExecutionMode.fromWireOrNull`/`unknownWireValueMessage`, with `fromWire` delegating (`runtime-domain/.../review/context/model/execution/CodeReviewExecutionMode.kt`);
     - `ValidationDepth.fromWireOrNull`/`unknownWireValueMessage`, with `fromWire` using `requireNotNull(fromWireOrNull(value)) { unknownWireValueMessage(value) }` (`runtime-domain/.../workflow/model/ValidationDepth.kt`);
     - `parsePersistedInstantOrNull`, with `parsePersistedInstant` delegating and keeping `"Timestamp is not a supported persisted instant."` (`runtime-domain/.../workflow/time/PersistedInstant.kt`);
     - `repositoryRelativePathViolation`, which keeps the same four messages in the same order, with `requireRepositoryRelativePath` as `require(violation == null) { violation.orEmpty() }` (`runtime-domain/.../review/model/ReviewRepositoryRelativePath.kt`).
   - Fix: in `PersistedInstant.kt`, wrap the `?: throw IllegalArgumentException(...)` in `parsePersistedInstant` onto its own continuation line so the line fits 120.
   - The IAE in `parsePersistedInstant` loses its `DateTimeParseException` cause, which ground rule 2 allows. Nothing prints or persists the cause.
   - Leave the re-export in `review/context/model/hunk/ReviewContextCanonical.kt` alone. Convert no catch site; SKILL-401 does that.
   - Test obligation: none. Each throwing form delegates to its new form, so existing tests of `fromWire`, `parsePersistedInstant` and `requireRepositoryRelativePath` already drive the new forms' branches and texts. SKILL-401 adds caller-level tests when it converts the catch sites.
2. **Application validators** (AC-002, AC-004).
   - Keep `RuntimeOwnedReviewMode.parse` returning `CodeReviewExecutionMode?`, and `unknownModeMessage(value)` building `"Unknown code-review execution mode '$value'. Allowed: auto, inline."` from `allowed` (`runtime-application/.../application/review/service/RuntimeOwnedReviewMode.kt`).
   - Keep `SCAFFOLD_PAYLOAD_NOT_OBJECT_MESSAGE` and the nullable `decodeScaffoldPayloadObject`. Both `decodeScaffoldCommandRequest` overloads keep throwing IAE with that constant for the MCP edge (`runtime-application/.../application/scaffold/ScaffoldCommandRequestDecoder.kt`).
   - Keep the `RuntimeOwnedReviewModeTest` edit: `assertNull(parse(value))` plus `assertEquals` on `unknownModeMessage(value)`, with the expected text unchanged.
3. **Release-ref result through the port** (AC-002, AC-003, AC-004).
   - Keep the port side: `sealed interface ReleaseRefValidation { Valid(metadata); Rejected(message) }` in `runtime-ports/.../ports/validation/model/RepoValidationGatewayModels.kt`, and `RepoValidationGateway.validateReleaseRef` returning it.
   - Keep the infra side:
     - `ReleaseRefValidationResult` next to the infra `ReleaseRefMetadata` in `RepoValidationRuntime.kt`.
     - In `RepoValidationRuntimeReleasePolicy`:
       - `MALFORMED_RELEASE_TAG_MESSAGE` holds the canonical text.
       - `parseReleaseRefOrNull` is added.
       - The throwing `parseReleaseRef` stays as a delegate; the repoTest `RepoValidationReleasePolicyTest` still uses it at lines 33–63, and those tests stay unchanged.
       - `validateReleaseRef` returns `Rejected` for a malformed tag, for the force-prerelease text, and for `releaseLicensePolicyViolation`.
     - `RepoValidationRuntimeReleasePolicyGate` returns the violation text instead of throwing.
     - `FileSystemRepoValidationGateway` maps the infra result to the port result.
   - Fix: in `RepoValidationRuntime.kt`, move the `validateReleaseRef` expression body onto a continuation line so the line fits 120.
   - Fix: in `FileSystemRepoValidationGateway.kt`, move `import skillbill.ports.validation.model.ReleaseRefValidation` out of the alias-import group into the plain group, after `import skillbill.ports.validation.RepoValidationGateway` and before `import java.nio.file.Path`. Otherwise it breaks ktlint import ordering.
   - Keep the `ReleaseLicensePolicyError` deletion and its removed baseline row (`runtime-core/src/repoTest/.../baselines/custom-throwable-baseline.txt`).
   - Keep the `RepoValidationReleasePolicyTest` edits. Only the type assertions change (`assertFailsWith<IllegalArgumentException>` becomes `assertIs<Rejected>`), and the `contains(...)` texts are unchanged.
4. **CLI sites** (AC-002, AC-004, AC-006).
   - **Review mode.** Keep `parseRequestedCodeReviewMode` (`featuretask/FeatureTaskRuntimeRunRequestAssembly.kt`) and `parseCodeReviewMode` (`goal/run/GoalCliRunCommands.kt`) as `RuntimeOwnedReviewMode.parse(raw) ?: throw UsageError(RuntimeOwnedReviewMode.unknownModeMessage(raw))`. The `usageError` imports and the wrap-and-`initCause` path are already gone. The UsageError text equals the former `error.message`, and clikt's stderr and exit code are unchanged.
   - **Release ref.** Keep the `when` over `ReleaseRefValidation` in `repovalidation/RepoValidationCliCommands.kt`. `Rejected` emits `{status: failed, error: message}` in JSON, or `"$message\n"` in text, with exit 1. That is byte-identical to the former `error.message` and `error.message.orEmpty()` uses. `run()` has two `return`s, within `ReturnCount` 4.
   - **Scaffold payload.**
     - Keep `NativeScaffoldPayloadRun.runPayload(Map)` branching on a null `decodeScaffoldPayloadObject` to `completeScaffoldError(SCAFFOLD_PAYLOAD_NOT_OBJECT_MESSAGE, format)`.
     - Keep `ScaffoldPayloadInputs.readScaffoldPayload` mapping null to `throw InvalidScaffoldPayloadError(SCAFFOLD_PAYLOAD_NOT_OBJECT_MESSAGE)`. It is caught by the `SkillBillRuntimeException` arm of `runPayloadFile`, so the output is unchanged.
   - **Revert one out-of-scope edit.** In `ScaffoldPayloadInputs.readScaffoldPayloadText`, restore `payloadPath == null -> throw IllegalArgumentException("--payload is required for this command.")`. SKILL-401 subtask 7 lists that IAE source as its own (its spec, line 20), so this subtask leaves it.
   - **Leave these catches unchanged** (SKILL-401 subtask 7): the IAE catches in `NativeScaffoldPayloadRun.kt` (`runPayloadFile`, `completeAuthoring`, `completeRenderText`), `ScaffoldWizardRun.kt:41`, `InstallCliCommands.kt` and `AgentAddonSelectionParsing.kt`.
5. **Tests** (scope item 4; AC-002, AC-004). Use the existing harnesses, and add no helper, mock or fixture module.
   1. **Review mode**, in `runtime-cli/src/test/.../cli/featuretask/FeatureTaskRuntimePreparationTest.kt`. Run `CliRuntime.run(listOf("--db", db, "feature-task", "SKILL-348", "--code-review-mode", "delegated"), context)`.
      - The context passes an explicit non-empty `environment` map (not `emptyMap()`, which falls back to the host environment).
      - Assert exit code 1 and that `stderr` contains `"Unknown code-review execution mode 'delegated'. Allowed: auto, inline."`.
      - Bug it catches: the null branch maps to the wrong text, or the failure escapes to a `CliRuntime` arm instead of clikt's `UsageError`.
   2. **Scaffold payload**, in `runtime-cli/src/test/.../cli/CliScaffoldRuntimeTest.kt`. Write a temp file containing `[]` and run `new-skill --payload <file> --dry-run --format json` with `CliRuntimeContext(userHome = tempDir)`.
      - Assert exit code 1 and the stdout JSON `status == "error"` and `error == "Invalid JSON payload: expected an object."`, using the file's existing `parseJsonObject` and `stringValue` helpers.
      - Bug it catches: the null result from `readScaffoldPayload` escapes `runPayloadFile`'s catches, or prints different text or a different stream.
   3. **Release ref**, in `runtime-cli/src/repoTest/.../cli/CliRepoValidationRuntimeTest.kt`. Run `validate-release-ref not-a-tag --repo-root <tempDir> --format text`.
      - Assert exit code 1 and `stdout == "Release tag must match canonical vMAJOR.MINOR.PATCH with optional SemVer prerelease/build metadata.\n"`.
      - Bug it catches: the `Rejected` text branch drops the trailing newline or the message. The existing `validate-release-ref reports release policy errors as json failures` test covers the policy `Rejected` branch in JSON.
6. **Self-check before handing to build** (reading and grep only; no compile or test run).
   - No `catch (error: IllegalArgumentException)` remains in `FeatureTaskRuntimeRunRequestAssembly.kt`, `GoalCliRunCommands.kt` or `RepoValidationCliCommands.kt`.
   - No reference to `ReleaseLicensePolicyError` remains.
   - No added line is over 120 characters.
   - None of the touched code contains `@Suppress`, a new `runCatching`, a new throwable, a typealias or a `relaxed = true` mock.
   - The SKILL-401 catch sites listed in task 4 are byte-identical to `HEAD`.

### Acceptance-criteria coverage

- **AC-001:** task 1.
- **AC-002:** tasks 2–5. No IAE remains for malformed input on the three paths, and no runtime-cli IAE catch remains for review mode or release ref. Tests 5.1 and 5.3 pin the texts and exit codes.
- **AC-003:** task 3. The class and its row are deleted, and nothing else uses them.
- **AC-004:** all messages come from one source. Test edits change only assertion types, and test 5.2 pins the scaffold text.
- **AC-005:** no `ParseBoundarySite` file is touched, and tasks 1 and 3 keep lines within detekt's limits. `ReturnCount`, `ThrowsCount` and `CyclomaticComplexMethod` stay within bounds: `repositoryRelativePathViolation` is one `when`, and `validateReleaseRef` has two returns.
- **AC-006:** task 4, including the revert, and the task 6 check.

### Constraints

- Every message stays byte-identical.
- Add no new module, dependency, throwable, typealias, `Result`/`Either`, `@Suppress` or `runCatching`.
- Domain stays pure and keeps its imports.
- `CliRuntime.kt` and `McpToolDispatcher.kt` stay unchanged.
- Don't edit the parent spec, the sibling sub-specs or the SKILL-401 bundle.
- Implement runs no build, tests, detekt or check; the build and validate phases own them.

## Next Path

skill-bill goal SKILL-398

## Spec Path

.feature-specs/SKILL-398-runtime-exception-reduction/spec_subtask_6_no-control-flow-on-defect-exceptions.md
