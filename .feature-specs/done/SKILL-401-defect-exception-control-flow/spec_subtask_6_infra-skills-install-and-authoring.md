# SKILL-401 Subtask 6 - infra-skills-install-and-authoring

Parent spec: [.feature-specs/SKILL-401-defect-exception-control-flow/spec.md](spec.md)
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

## Implementation Details

This plan uses only the upstream preplan digest at checkout HEAD `5a17798c15c908c1d5a9c40ca025f0cb1bcb50d6`. Historical scope line numbers are anchors, not current locations. No new decomposition or dependency work is required. The runtime owns branch preparation. Planning reads and edits this sub-spec only.

Paths below are relative to `../../../runtime-kotlin`. Infra skills production paths begin `runtime-infra/skills/src/main/kotlin/skillbill/infrastructure/skills/`; their tests begin `runtime-infra/skills/src/test/kotlin/skillbill/infrastructure/skills/`. Use the digest's named symbols to confirm the owning package during implement if a shortened path differs. That confirmation is implementation work, not a reason to repeat preplan.

1. Convert staging input rejection before removing its broad catch. Serves AC-001 and the common message and parse-boundary criteria. In `install/staging/InstallStaging.kt`'s `stageInstalledSkill` and `install/staging/InstallStagingPrepare.kt`'s preparation chain, include input checks reached through `InternalSidecarTarget.kt`, `content/InstallContentHash.kt`, and support-pointer preparation. Emit the existing `InvalidInstallStagingError` framing directly for rejected input, using its current coded owner form. Preserve rejection order and reason text. Keep `require` and `check` for true runtime-derived staging invariants. Narrow catches around actual path parsing to `InvalidPathException`; do not wrap defects in staging failures. Reuse `install/InstallStagingTest.kt` and `InstallApplyReplacementCleanupTest.kt` as later validation evidence for invalid staging and replacement behavior. Assumption for implement to confirm: the digest's reachable preparation chain identifies all input sources handled by this catch; any additional source found inside that chain must receive the same classification without expanding to unrelated staging invariants.

2. Narrow native-agent inventory path handling. Serves AC-001 and message preservation. In `install/nativeagent/inventory/NativeAgentLinkInventoryDecode.kt`'s `decodeEntries`, catch `InvalidPathException` at `Path.of`, retain existing `IOException` handling, and preserve `Delete it and reinstall.` Most semantic checks already call `invalid(path, reason)`; retain those conversions, duplicate-entry rejection, and trusted-target validation rather than recreating historical `require` replacements. No new test obligation follows from library narrowing. Retain existing inventory coverage and use the native-agent suites listed below for later behavioral validation.

3. Remove defect cleanup arms according to the filesystem call each boundary actually owns. Serves AC-001 and AC-002. Touch `install/staging/InstallStagingPrune.kt`, `InstallStagingAtomicMoves.kt`, `install/scaffold/ScaffoldRollbackBridge.kt`, and `scaffold/runtime/service/ScaffoldServiceRollback.kt`. Use `UncheckedIOException` only where cleanup iterates filesystem streams, including `deleteInstallStagingDirectory` and the call to host `runtime-infra/host/src/main/kotlin/skillbill/infrastructure/host/jvm/FsContentPrimitives.kt`'s `rollbackDeleteEmptyDirectory`. The digest establishes that host primitive as the stream source; modify it only if required for this caller conversion, preserving its ownership. Drop the ISE arm from `restoreInstallStagingBackup`, which performs an atomic move, without adding a stream catch there. Preserve the existing I/O catches, log levels, labels, suppression, and accumulated error strings. Reuse `InstallApplyReplacementCleanupTest.kt`, `scaffold/AuthoringOperationsTest.kt`, and `AuthoringContentMutationTest.kt`. No cleanup mock-interaction tests or tests solely for catch narrowing are planned.

4. Make authoring and native-agent cleanup preserve the initiating failure. Serves AC-001 and AC-002. In `scaffold/authoring/AuthoringDiscovery.kt`'s `runWithUpgradeRollback` and `AuthoringMutation.kt`'s `runWithContentRollback`, establish cleanup ownership immediately after acquiring rollback state. Use a success flag with `use` or equivalent failure-safe cleanup and roll back only before success. A bare `finally` that can replace the primary failure is insufficient, even though the historical scope suggests that shape. In `nativeagent/rendering/NativeAgentOperations.kt`, wrap staging and promotion in `Closeable { deleteNativeAgentRenderStaging(staging) }.use { ... }`. Remove the initiating IAE/ISE catches and cleanup `runCatching`. Preserve the original failure as primary and attach cleanup failures as suppressed, including on cancellation or interruption exits. Reuse `AuthoringOperationsTest.kt`, `AuthoringContentMutationTest.kt`, and `nativeagent/NativeAgentOperationsTest.kt` for rollback outcomes; retain `NativeAgentRenderSnapshotTest.kt` for generated bytes. Assumption for implement to confirm: these existing rollback suites provide observable coverage of the affected cleanup paths. Do not claim they prove double-failure suppression until their assertions have been inspected.

5. Give agent add-on consumer lookup a shared nullable form. Serves AC-001 and AC-003. Add or retain `AgentAddonConsumer.fromIdOrNull` and one unknown-consumer message helper in `runtime-domain/src/main/kotlin/skillbill/domain/agentaddon/model/AgentAddonModels.kt`. Keep `fromId` as a throwing wrapper that delegates to that same message source, and preserve the legacy decode alias. Branch on the nullable result in `scaffold/runtime/service/ScaffoldServicePlanningPayloadMerge.kt`'s `validateAgentAddonConsumerId` and `scaffold/authoring/AuthoringRenderOutput.kt`'s `renderAgentAddonPointerBlocks`. Keep the explicit retired `bill-feature` rejection in scaffold planning and every existing unknown-consumer byte. Reuse `scaffold/AuthoringRenderOutputTest.kt` and relevant existing authoring rejection tests. Add a single caller-boundary invalid-consumer case only if existing coverage does not reach this branch. Its named realistic bug is an unknown consumer escaping as a defect or producing changed rejection text after the nullable conversion. That conditional obligation serves AC-001 and AC-003; do not add a helper-only test duplicating a covered caller branch.

6. Convert the complete native-agent composition input chain to its owned schema failure. Serves AC-001 and AC-003. In `nativeagent/composition/NativeAgentBundle.kt`, cover `parseNativeAgentBundle`, `parseValidatedNativeAgentBundle`, field and entry parsing, supported-key checks, and `invalidBundle`. Include blank and unknown composition-kind rejection in `NativeAgentComposition.kt`. Use `invalidNativeAgentCompositionSchemaError` with `InstallFailureCode.INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA` and unchanged reason text. Apply source-labelled framing once; do not wrap an already framed message. In `scaffold/validation/review/ReviewSkillStructureValidatorContent.kt`, catch only that exact owned code and rethrow unrelated coded failures. Preserve the existing handled failure set and edge classification; do not edit the protected CLI or MCP edge files or invent a new code. Reuse `nativeagent/NativeAgentCompositionSchemaViolationsTest.kt`, `NativeAgentCompositionTestSupport.kt`, `AuthoringRenderOutputTest.kt`, and `NativeAgentRenderSnapshotTest.kt`. Change exception assertions only where the conversion requires it, keeping expected text and bytes. Add one boundary test for an uncovered bundle-input rule only after naming its concrete bug, such as an unknown composition kind bypassing the schema code or receiving duplicated source framing. Existing tests covering that rule discharge the obligation.

7. Complete implementation evidence and hand off command execution to its owning phases. Serves all acceptance criteria and the common architecture criteria. During implement, inspect the touched call chains and changed validators' callers without starting a repository-wide `runCatching` sweep. Remove the forbidden catch and type-check branches in this subtask's scope while preserving the subtask-4 config-store exclusions and SKILL-398 release-ref exclusion. Keep every governed `ParseBoundarySite` function name, or update its location without dropping coverage. Audit must inspect each criterion, primary/suppressed ordering, exact schema and consumer text, generated-byte evidence, cancellation propagation, and exact coded catch guards. Review and validate may repair production wiring, test setup, formatting, or lint as needed while preserving behavior, assertions, and architecture rules.

The validate phase owns execution of the named behavioral suites, detekt, `TypedParseBoundaryArchitectureTest`, `FailureCodeTotalityArchitectureTest`, and the other full-gate architecture tests, including wire vocabulary, comment/KDoc, file-size, and module/package ownership guards. It must run the repository's actual collect-all gate and `../../../scripts/validate_agent_configs`, retain the cache-bypassing gate when required, and obey the plain-clone Spotless constraint. The build phase alone owns the declared build command. Plan and implement execute no tests, compilation, build, or full check suite; any phase receipt here leaves `tests_executed` empty.

Implementation constraints remain the failure model and rules A1, A2, A4, A7, A10, A11, and G7 identified by preplan. Expected input rejection branches on values or emits the existing owned coded failure; defects keep their invariant checks and propagate. Keep one source for each reason and preserve validation order. Domain helpers remain pure and free of `java.nio` and ports imports. Do not add generic catches, new `runCatching`, suppressions, wider baselines, new throwables, or new workflow commands. Preserve wire keys, field ordering, schema versions, generated snapshots, telemetry behavior, and cooperative cancellation. Keep authored Kotlin within the existing file ceiling and comment rules. Delete a custom throwable and tighten its baseline only if implementation proves its final production reader is gone.

No new cleanup tests are planned. Conditional input tests above are obligations only for branches not already covered by observable boundary assertions. Existing regression tests and governed parity or validator-backed guards remain mandatory. The digest leaves no product decision open; package locations and exact coverage not stated there are assumptions for implement to confirm, not planning blockers. No data migration, feature flag, skill-source change, installation refresh, history write, commit, push, or PR action belongs to this plan phase. Those later actions stay with their owning runtime phases or parent runtime.
