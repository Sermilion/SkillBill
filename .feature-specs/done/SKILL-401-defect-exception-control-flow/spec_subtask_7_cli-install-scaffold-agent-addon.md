# SKILL-401 Subtask 7 - cli-install-scaffold-agent-addon

Parent spec: [.feature-specs/SKILL-401-defect-exception-control-flow/spec.md](spec.md)
Issue key: SKILL-401

## Scope

Remove the IAE catches left in runtime-cli after SKILL-398 subtask 6: `InstallCliCommands`, `NativeScaffoldPayloadRun`, `ScaffoldWizardRun` and `AgentAddonSelectionParsing`, plus the scaffold infra input `require`s those catches depend on. The CLI sites are in `runtime-cli/.../cli/`. The review-mode, repo-validation and scaffold-payload-object paths belong to SKILL-398 subtask 6; keep what it landed.

**`install/core/InstallCliCommands.kt:217`**

- Replace the `require(staleSlugs.isEmpty())` with an explicit branch that calls the same `completeText("Saved install selection references unavailable platform pack slug(s): ….\n", emptyMap(), exitCode = 1)`.
- Drop the IAE arm.
- If the census finds other input IAE sources in the `try` body, convert them to the coded failures that the sibling `SkillBillRuntimeException` arm already prints.

**`scaffold/payload/NativeScaffoldPayloadRun.kt:41`, `:161`, `:177`** and **`scaffold/wizard/ScaffoldWizardRun.kt:41`**

- Census the reachable IAE sources:
  - `decodeScaffoldPayloadObject`, which now returns null and is mapped to the constant message in both `runPayload` and `ScaffoldPayloadInputs.readScaffoldPayload`.
  - `readScaffoldPayloadText`'s `"--payload is required for this command."`.
  - `Path.of` in `readCliTextFile`, which throws `InvalidPathException`.
  - The wizard prompt and normalization `require`s (`ScaffoldWizardPrompts`, `ScaffoldWizardValueNormalization`).
  - The scaffold infra `require`s on payload values (`ScaffoldService*`, `PointerOperations`, `PointerRendering`, `FileSystemScaffoldGateway`).
- Convert each input source to the scaffold payload failure code (`InvalidScaffoldPayloadError`, or what SKILL-398 subtask 4 or SKILL-399 made it). The sibling `SkillBillRuntimeException` arm already prints that through `completeScaffoldError` with the same text and exit code.
- Handle `InvalidPathException` with a narrow catch that calls the same `completeScaffoldError`.
- MCP check (ground rule 1): the scaffold infra sources are also reached through MCP `new_skill_scaffold`.
  - Today's IAE path doesn't capture, while scaffold codes that used to be `SkillBillRuntimeException` subclasses do.
  - Give the converted input sources their own entry (for example `INVALID_INPUT`) and add that entry to `uncapturedAtMcp()`, so MCP still captures none of them.
  - If that entry cannot be named from runtime-mcp, don't change MCP telemetry. Report it as an implementation obstacle.

**`kernel/agent/AgentAddonSelectionParsing.kt:67`**: apply the shared validators before construction, and call `invalidAgentAddonSelection("Invalid agent add-on selection: <violation>")`.

## Acceptance Criteria

1. No main source in runtime-cli outside `CliRuntime.kt` catches or `is`-checks `IllegalArgumentException` or `IllegalStateException`, except a narrowed `InvalidPathException` catch.
2. Install, scaffold payload, scaffold wizard and agent add-on selection commands print the same texts on the same streams with the same exit codes.
3. MCP `new_skill_scaffold` captures telemetry for exactly the failures it captured before.

## Non-Goals

The review-mode, repo-validation and scaffold-payload-object CLI paths (SKILL-398 subtask 6).

## Test obligations

- The `AgentAddonSelection` duplicate-slug violation, through `AgentAddonSelectionParsing`.

Add it only if no existing test drives that branch.

## Shared Rules

Apply `spec.md` "Shared validators", "Ground rules", "Site classification", "Test rules" and "Execution Rule". If a shared validator this subtask needs is missing, add it as written there.

## Common Acceptance Criteria

- Every user-visible message and every persisted byte is unchanged. Existing tests pass with only exception-type assertion edits where a validator now returns a value.
- The exception type that reaches `CliRuntime.run` or `McpToolDispatcher.dispatch` for a given input is unchanged, or is a code on the MCP no-capture list.
- `TypedParseBoundaryArchitectureTest` and detekt pass. No `ParseBoundarySite` entry is dropped.
- Sites outside this subtask's scope are unchanged, except for callers of a port or validator this subtask changed.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite. Add only the tests listed under Test obligations, and only where no existing test already drives the branch. Run nothing in implement; build, tests, detekt and repoTest belong to the build and validate phases.

## Next Path

skill-bill goal SKILL-401

## Spec Path

.feature-specs/SKILL-401-defect-exception-control-flow/spec_subtask_7_cli-install-scaffold-agent-addon.md

## Implementation Details

This plan uses only the upstream preplan digest against checkout HEAD `5a17798c15c908c1d5a9c40ca025f0cb1bcb50d6`. AC-001, AC-002 and AC-003 below refer to this sub-spec's three numbered acceptance criteria. No dependency work is required. Paths below are relative to `../../../runtime-kotlin`; production paths use each module's `src/main/kotlin/skillbill/` root. Test names identify the existing suites supplied by preplan, without assuming locations the digest did not provide.

1. Establish the exact scaffold input failure classification before converting shared sources. This serves AC-002 and AC-003. In `runtime-contracts/.../error/shellcontent/ScaffoldFailureCode.kt`, add a distinct input entry, using `INVALID_INPUT` if that name is still available. In `error/shellcontent/ShellContentContractFailures.kt`, extend `isShellContentContractFailure()` with an exact match for that entry only. Provide the owning coded failure construction with the unchanged input reason. Do not classify the whole scaffold family or reuse `INVALID_PAYLOAD`; existing scaffold codes must remain captured. Keep other consumers of the shared predicate guarded by their exact owned codes or existing predicates, so this addition cannot broaden their handled failures. Leave `McpToolDispatcher.kt` untouched. The digest resolves the older scope note's proposed dispatcher edit and obstacle: its existing `uncapturedAtMcp()` already uses this shared predicate. Preserve the distinction recorded by `runtime-contracts/agent/decisions.md#b0eed4dbad57` and `runtime-kotlin/agent/decisions.md#a34bb8414a73`; a later history phase owns any new decision record. Validation will run `FailureCodeTotalityArchitectureTest` and existing telemetry classification coverage. The digest does not name that coverage's suite, so implement must identify it without adding a duplicate test or claiming capture evidence from inspection alone.

2. Replace install replay's expected rejection with an explicit result branch. This serves AC-001 and AC-002. In `runtime-cli/.../cli/install/core/InstallCliCommands.kt`, update `InstallReplayLastSelectionCommand.run` to branch on stale slugs and call the existing `completeText` path. Retain the sorted slug list, exact message and terminal newline, empty payload, exit code 1, output stream and text/JSON behavior. Narrow local discovery-option `Path.of` handling to `InvalidPathException`, and remove the broad IAE arm. Keep the coded catch's `rethrowIfDatabaseFailure()` and unrelated failure propagation. Reuse `CliInstallReplayLastSelectionRuntimeTest.kt` during validate; add no test merely for library narrowing or removal of a defect arm.

3. Convert the shared scaffold input sources that the CLI catches can reach. This serves all three acceptance criteria. In `runtime-infra/skills/.../infrastructure/skills/scaffold/runtime/service/ScaffoldService.kt`, convert the empty-payload rejection to the new input code. In the same owner's `scaffold/pointer/PointerOperations.kt` and `PointerRendering.kt`, convert containment, target-file, symlink and self-pointer input checks to that code, retaining each current reason and its validation order. Keep true generated-plan invariants such as `requireNotNull(plan.contentFile)`. The digest's current anchors supersede the historical `ScaffoldService*` and `FileSystemScaffoldGateway` examples; implement must confirm the affected call chain and preserve already converted paths rather than expand into unrelated scaffold work. Do not wrap an already framed failure or absorb unrelated coded failures. Reuse existing scaffold and partial-outcome coverage in the validation task below, including its stdout/stderr and persisted-byte assertions.

4. Remove exception-driven CLI scaffold input handling. This serves AC-001 and AC-002. In `runtime-cli/.../cli/scaffold/payload/NativeScaffoldPayloadRun.kt`, update `runPayloadFile`, `completeAuthoring` and `completeRenderText` to render the converted owned input failures through the existing scaffold error path. In `ScaffoldPayloadInputs.kt`, convert the missing-payload and create-and-fill kind checks. In `scaffold/wizard/ScaffoldWizardRun.kt`, update `runCollected` and the unsupported assisted-mode and wizard-kind rejections. Convert required-value checks in `ScaffoldWizardPrompts.kt` and unsupported source/registration checks in `ScaffoldWizardValueNormalization.kt`. Use the new input classification for newly converted input and keep existing coded failures unchanged. Preserve every literal message, framing, stream and exit code, including `--payload is required for this command.`. Retain narrow `InvalidPathException` handling for payload and body-file paths. Keep the landed nullable scaffold-object decoding and constant message; do not recreate its validator or restore its catch. Retain exact owned-code guards and database propagation where applicable. Validation will reuse `CliScaffoldRuntimeTest.kt`, `CliScaffoldPartialOutcomeTest.kt`, `ScaffoldCliResultMappersTest.kt` and `scaffold/wizard/ScaffoldPlatformPackWizardPayloadTest.kt`, without adding tests for trivial rendering glue.

5. Validate persisted add-on selections before constructing their models. This serves AC-001 and AC-002. In `runtime-domain/.../agentaddon/model/AgentAddonModels.kt`, retain or add `PersistedAgentAddonSelectionEntry.violation(slug, sourceIdentity, contentSha256)` and `AgentAddonSelection.violation(entries)`. Entry checks run in slug, content-digest, source-identity order; selection checks reject duplicate slugs. Both model invariants delegate to the same reason helpers. In `runtime-cli/.../cli/kernel/agent/AgentAddonSelectionParsing.kt`, `parseAgentAddonSelection` branches on those violations before construction and retains `UsageError` through `invalidAgentAddonSelection`, with the exact `Invalid agent add-on selection: <violation>` prefix. Keep shared helper edits already landed by other subtasks. The sole new test obligation is one duplicate-slug case through `parseAgentAddonSelection`. It catches the realistic bug where two individually valid persisted entries with the same slug escape CLI validation or produce the constructor's defect error instead of the established usage error. Assert the observable rejection and unchanged message, not helper call order. Preplan found this branch uncovered; add the test in the owning CLI test package, whose exact file location implement must confirm. Use existing decoder/caller tests for other invalid-entry branches and add no redundant helper tests.

6. Complete the repository changes and leave execution evidence to the owning phases. This serves all acceptance criteria and the common architecture criteria. Implement removes the six CLI broad catch sites identified by preplan without moving them into wrappers, preserves touched functions' cooperative cancellation and interruption behavior, and confirms any touched `runCatching` callers use explicit validator outcomes or the existing cooperative propagation mechanism. Audit inspects the CLI census, ordered reasons, exact handled failure sets and MCP capture distinction per criterion. Preserve named `ParseBoundarySite` coverage in `runtime-core/src/repoTest/kotlin/skillbill/architecture/PrincipleEnforcementInventory.kt`; keep function names or update locations without dropping entries. Retain architecture rules A1, A2, A4, A7, A10 and A11, and G7's prohibition on widened baselines or suppressions. No new composition entry, collaborator bag, runtime exception type, dependency, schema version, migration, feature flag or workflow command is needed. Domain helpers stay pure and retain their owner packages. Keep wire vocabulary ownership, the 1,200-line ceiling, detekt limits and Kotlin comment/KDoc rules. Only shrink a custom-throwable baseline if its class loses its final production reader.

Validation will run the existing behavioral suites named above, the duplicate-selection test, detekt, and the full runtime-core repoTest coverage, including `TypedParseBoundaryArchitectureTest`, `FailureCodeTotalityArchitectureTest`, wire vocabulary, comment/KDoc, file-size and module/package ownership guards. It will discover the actual collect-all full gate and cache-bypassing argv from `../../../platform-packs/kotlin/platform.yaml` and retain `scripts/validate_agent_configs` from CI. The digest does not supply literal gate argv, so validate must resolve those commands rather than invent them. Spotless follows the plain-clone constraint and the retired ratchet-fetch guidance. Build proof remains exclusively with the build phase; all tests and full checks remain with validate. Required review or validation repairs may touch production wiring, test setup, formatting and lint while preserving behavior, assertions and architecture rules. No installer or install-sync work is planned. History, commit/push, PR and runtime settlement remain with their owning phases.

Planning changed only this section and ran no build, test or validation command. There is no unresolved product decision or concrete planning obstacle. Implement must confirm only details the digest omits, such as the available input-code name, owning error-factory placement and test-file locations, within the decisions above.
