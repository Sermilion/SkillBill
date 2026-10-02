# SKILL-401 Subtask 6 - infra-skills-install-and-authoring

Parent spec: [.feature-specs/SKILL-401-defect-exception-control-flow/spec.md](./spec.md)
Issue key: SKILL-401

## Scope

Remove the IAE/ISE control flow at the runtime-infra/skills sites other than the config stores (subtask 4) and `validateReleaseRef` (SKILL-398 subtask 6).

- **`install/staging/InstallStaging.kt:152`**: own `require`s in `prepareStageInstalledSkill` throw `invalidStageInstalledSkill` directly. Catch `InvalidPathException` if path parsing is involved.
- **`install/nativeagent/inventory/NativeAgentLinkInventoryDecode.kt:35`**: own `require`s in `decodeEntries` and `validateDecodedEntries` throw `throwDecodeError(path, …)` directly.
- **`install/staging/InstallStagingPrune.kt:79`**, **`install/staging/InstallStagingAtomicMoves.kt:53` and `:67`**, **`install/scaffold/ScaffoldRollbackBridge.kt:25`** and **`scaffold/runtime/service/ScaffoldServiceRollback.kt:87`**
  - No ISE source is visible: `deleteInstallStagingDirectory` and `rollbackDeleteEmptyDirectory` iterate `Files.walk` and `Files.list`.
  - Replace each ISE arm with `UncheckedIOException`, which keeps the logging and error accumulation for real I/O failures.
  - Drop the arm wherever no stream is iterated.
- **`scaffold/authoring/AuthoringDiscovery.kt:49`** and **`AuthoringMutation.kt:60`**: rollback-and-rethrow becomes a success flag with `finally { if (!committed) rollback… }`.
- **`nativeagent/rendering/NativeAgentOperations.kt:161` and `:163`**
  - Use `Closeable { deleteNativeAgentRenderStaging(staging) }.use { stageAndPromote… }`. This keeps the initiating-failure-plus-suppressed-cleanup order and removes the touched `runCatching`.
  - Cleanup now also runs on failures it used to skip. That is a strict improvement.
- **`scaffold/runtime/service/ScaffoldServicePlanningPayloadMerge.kt:141`**: use `AgentAddonConsumer.fromIdOrNull`, and keep `"Unknown agent add-on consumer…"` exactly. Also convert `AuthoringRenderOutput.kt:110`.
- **`nativeagent/composition/NativeAgentBundle.kt:17`**: the `require`s in `parseValidatedNativeAgentBundle`, `requireSupportedKeys` and the entry parsers throw `InvalidNativeAgentCompositionSchemaError(sourceLabel = path, reason = <same text>)` or its code directly. Check whether `invalidBundle` itself throws IAE.
- **`scaffold/validation/review/ReviewSkillStructureValidatorContent.kt:92`**: catch the composition schema failure or its code that `parseNativeAgentBundle` now throws, instead of IAE.

## Acceptance Criteria

1. No main source in runtime-infra/skills catches or `is`-checks `IllegalArgumentException` or `IllegalStateException`, except catches narrowed to `InvalidPathException` or `UncheckedIOException` at the library calls named above, and the config stores subtask 4 owns.
2. Rollback and cleanup keep the primary failure and add cleanup failures as suppressed; logging and error accumulation for real I/O failures are unchanged.
3. `"Unknown agent add-on consumer…"` and the native-agent composition schema texts are unchanged.

## Non-Goals

The external platform-pack and add-on config stores (subtask 4); `validateReleaseRef` (SKILL-398 subtask 6).

## Test obligations

None beyond the shared test rules.

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

.feature-specs/SKILL-401-defect-exception-control-flow/spec_subtask_6_infra-skills-install-and-authoring.md
