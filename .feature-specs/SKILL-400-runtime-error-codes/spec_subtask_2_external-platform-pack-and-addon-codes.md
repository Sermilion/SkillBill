# SKILL-400 Subtask 2 - external-platform-pack-and-addon-codes

Parent spec: [.feature-specs/SKILL-400-runtime-error-codes/spec.md](./spec.md)
Issue key: SKILL-400

## Scope

Convert `ExternalPlatformPackErrors.kt` (`ExternalPlatformPackConfigError`, `AmbiguousExternalPlatformPackError`, `ExternalPlatformPackOverlayError`, `ExternalPlatformPackPublishError`) and `ExternalAddonErrors.kt` (`ExternalAddonConfigError`, `ExternalAddonOverlayError`) in runtime-contracts `skillbill.error.core`. All six extend `ShellContentContractException`, and CLI config and install commands discriminate them, so the enums stay in the kernel.

- **Codes:** `ExternalPlatformPackFailureCode { CONFIG, AMBIGUOUS, OVERLAY, PUBLISH }` and `ExternalAddonFailureCode { CONFIG, OVERLAY }`, with message functions per the conversion rules. Rename the files to the enum names.
- Add both enums to `isShellContentContractFailure()`, so the guarded CLI `Config*`, `InstallApplyExternalAddonsCommand` and `AgentAddonCliCommands` catches keep handling them.
- **Throw sites:** in runtime-infra/skills `externalplatformpack/` and `externaladdon/` (`FileExternalPlatformPackSourceConfigParsing`, `ExternalAddonSourceEntries`, `FileSystemExternalAddonOverlay*`, and the rest the census finds).
- **Catch and `is` sites:**
  - `FileExternalPlatformPackSourceConfigStore.kt:149` becomes a `CONFIG` code check.
  - `InstallNativeAgentOperationsLinkCatalog.kt:46,71` become `PUBLISH` code checks.
  - The family branch of `ExternalPlatformPackTelemetryPolicy.kt:25-26` (domain) checks `code` (`AMBIGUOUS`, `CONFIG`). Keep any manifest-schema arm SKILL-399 added; the family strings are unchanged.
- **`remotePayload`.** `ExternalPlatformPackPublishError.remotePayload` has no production reader; only `ExternalPlatformPackCatalogIntegrationTest:406-409` reads it.
  - Drop the payload construction in `InstallNativeAgentOperationsLinkCatalog.kt`, and replace those four assertions with a `PUBLISH` code assertion.
  - This is a deliberate edit beyond type-to-code. Name it in the summary, and record it in the decision entry below.
- **Pinned labels:** `ExternalPlatformPackTelemetryPolicyTest:27` and `ConfigExternalPlatformPackCommandTest:111` assert the `failureCodeLabel()` value where they pinned a class name.
- **Decision:** add a newest-first entry to `runtime-kotlin/agent/decisions.md`, "SKILL-400 subtask 2: external pack codes and the dropped publish payload".

## Acceptance Criteria

1. No main source declares the six classes. The two enums and their message functions replace them.
2. Each former failure throws `SkillBillRuntimeException` with an entry of `ExternalPlatformPackFailureCode` or `ExternalAddonFailureCode`.
3. CLI config, install and agent add-on commands print the same stdout, stderr and exit codes for these failures. The external-platform-pack telemetry family values are unchanged.
4. No main code reads `remotePayload` or any other typed property from a caught failure of these classes.

## Non-Goals

Other `skillbill.error.core` classes; the SKILL-399 Install and Manifest areas.

## Test obligations

None beyond the converted assertions and the four replaced `remotePayload` assertions.

## Shared Rules

Apply `spec.md` "Conversion rules", "Shared pieces", "Transition finish" and "Execution Rule". If a shared piece is missing, add it as written there. After this subtask's edits, check the transition-finish condition and finish the transition if it holds.

## Common Acceptance Criteria

- Every user-visible message is byte-identical. No expected-output, wire-fixture or payload assertion is edited, other than replacing an exception-type assertion with a code assertion, or a pinned class name with the code label.
- MCP telemetry capture happens for exactly the failures it happened for before, and no unguarded `SkillBillRuntimeException` catch absorbs a failure it did not catch before.
- No main source declares a typealias named after a deleted class.
- `custom-throwable-baseline.txt` lists none of this subtask's deleted classes, and `FailureCodeTotalityArchitectureTest` passes.
- Classes this subtask does not own are unchanged, except for catch sites that must accept a code this subtask introduced.

## Validation Strategy

Goal gates: build, unit tests, detekt and the runtime-core repoTest suite. Existing tests pass with type-to-code assertion edits only. Add only the behavioural tests listed under Test obligations, and only where no converted existing test already asserts the branch. Run nothing in implement; build, tests, detekt and repoTest belong to the build and validate phases.

## Implementation Details

This plan uses only the upstream preplan digest and this sub-spec. No discovery is repeated during planning. The digest identifies all six failures as input-driven shell-content failures, so all six remain coded rather than becoming defects. There are no dependencies or open external decisions. Main paths below are relative to `runtime-kotlin/`; test paths use the owning module's `src/test/kotlin/` and the same package unless stated otherwise.

### Ordered tasks

1. Replace the six throwable declarations with their owner enums and message factories. Serves AC-001 and AC-002.

   In `runtime-contracts/src/main/kotlin/skillbill/error/core/`, rename `ExternalPlatformPackErrors.kt` to `ExternalPlatformPackFailureCode.kt` and `ExternalAddonErrors.kt` to `ExternalAddonFailureCode.kt`. Declare `ExternalPlatformPackFailureCode` with `CONFIG`, `AMBIGUOUS`, `OVERLAY`, and `PUBLISH`, and `ExternalAddonFailureCode` with `CONFIG` and `OVERLAY`. Both implement the existing `RuntimeFailureCode`. Each factory returns the existing `SkillBillRuntimeException`, preserves the supplied message byte-for-byte, and retains the optional cause. The publish factory deliberately omits `remotePayload`.

   The digest does not prescribe factory names. Use `externalPlatformPackConfig`, `ambiguousExternalPlatformPack`, `externalPlatformPackOverlay`, `externalPlatformPackPublish`, `externalAddonConfig`, and `externalAddonOverlay`, subject to implement confirming that these names do not collide with existing declarations. Reuse the existing exception, `rethrowUnless`, and `failureCodeLabel` helpers. Do not recreate shared infrastructure. Converted existing tests assert the shared exception type and exact enum entry; no new factory-only test is needed.

2. Convert production construction sites and preserve catalog failure behavior. Serves AC-002 and AC-004.

   Update infra-skills sources under `runtime-infra/skills/src/main/kotlin/skillbill/infrastructure/skills/externalplatformpack/`, `externaladdon/`, `scaffold/platformpack/catalog/`, and `scaffold/runtime/service/externalpack/`, plus `install/nativeagent/link/InstallNativeAgentOperationsLinkCatalog.kt`. Anchors include `FileExternalPlatformPackSourceConfigParsing`, `FileExternalPlatformPackSourceConfigStore`, `ExternalAddonSourceEntries`, both external add-on config stores, the `FileSystemExternalAddonOverlay*` validation and staging files, `PlatformPackCatalogLoader`, `ExternalPlatformPackReadAllowlist`, and `ScaffoldExternalPlatformPackRegistration`.

   Replace every former construction or returned failure with the corresponding factory. Retarget `retainedCatalogFailure(...)` to return `SkillBillRuntimeException` and retain an existing publish failure by checking exactly `ExternalPlatformPackFailureCode.PUBLISH`, without wrapping it again. Keep `reviewCatalogStageFailure(...)` returning cancellation unchanged. Remove publish payload construction without replacing it with exception properties, message parsing, or a new result object. The digest establishes that no production reader needs that payload. Preserve all remaining messages, causes, suppression, cleanup, and rollback behavior. Existing catalog integration and config-store tests cover these boundaries.

3. Preserve handled sets at classification and command boundaries. Serves AC-002, AC-003, and AC-004.

   Register both enums in `runtime-contracts/src/main/kotlin/skillbill/error/shellcontent/ShellContentContractFailures.kt`, preserving every existing family and guard. Change the platform-pack config-store catch to a shared-exception catch guarded by the exact `CONFIG` entry. Convert catalog publish discriminators to exact `PUBLISH` checks. Merge typed catches only where needed, and rethrow unrelated codes through the existing helper. Preserve cancellation and interruption ordering and cooperative failure propagation.

   Apply the same code-based handling at the existing CLI `Config*`, `InstallApplyExternalAddonsCommand`, and `AgentAddonCliCommands` boundaries. The digest does not give their complete filenames; implement must confirm the current owner paths while applying these named anchors. Preserve stdout, stderr, exit codes, and message text, allowing only the specified pinned-class-label replacement. Shell-content registration preserves their existing MCP no-capture route; no separate MCP family registration is required.

   In `runtime-domain/src/main/kotlin/skillbill/scaffold/policy/platformpack/ExternalPlatformPackTelemetryPolicy.kt`, classify `AMBIGUOUS` as `ambiguous_external_platform_pack` and `CONFIG` as `external_platform_pack_config`. Preserve the existing `ManifestFailureCode.INVALID_MANIFEST_SCHEMA` branch and `failureCodeLabel()` rendering. Add no family metadata or competing classification helper.

4. Convert existing regression assertions without adding redundant coverage. Serves all four acceptance criteria.

   Update infra-skills `FileExternalPlatformPackSourceConfigStoreTest`, `ExternalPlatformPackCatalogIntegrationTest`, `FileExternalAddonSourceConfigStoreTest`, `FileExternalAgentAddonSourceConfigStoreTest`, `ExternalAddonOverlayTest`, and `ExternalPlatformPackOverlayPrecedenceTest`. Update domain `ExternalPlatformPackTelemetryPolicyTest` and `EffectivePlatformPackCatalogTest`, and CLI `ConfigExternalPlatformPackCommandTest` and existing affected install and agent add-on command assertions. Reuse `ExternalAddonOverlayTestSupport.kt`.

   Assert `SkillBillRuntimeException` plus the precise code wherever a former subclass was asserted or constructed. Preserve message, cause, output, exit-code, and overlay-precedence assertions. Replace the four catalog `remotePayload` assertions with a publish-code assertion. Change pinned class labels only to their corresponding `failureCodeLabel()` values.

   New test_obligations: []. Existing tests already cover the realistic regressions relevant to this conversion, including lost config classification, changed overlay precedence, altered CLI output, and lost publish classification. Preserve those observable assertions and all governed parity coverage. Add no helper module, structural test, relaxed mock, or duplicate case with different literals.

5. Remove obsolete baseline rows and inspect the resulting source state. Serves AC-001 and AC-004 and the common architecture criteria.

   Remove only the six deleted whole rows from `runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/custom-throwable-baseline.txt` by hand. Leave `ArchitectureScanSupport.kt`, unrelated rows, and the synthetic rejection fixture of `FailureCodeTotalityArchitectureTest` unchanged. Implement confirms that no former declarations, constructors, typed catches, typealiases, or caught-property readers remain for this slice. Audit independently inspects every acceptance criterion, handled sets, cancellation behavior, and the publish-payload exception.

   The digest reports many remaining classes owned by later subtasks, so this slice is expected to leave the transition bases open. Implement checks the parent transition-finish condition against the then-current source sets. If remaining subclasses or codeless callers exist, preserve the transition and identify them. Only if the complete condition holds may it retire the legacy bases and constructor and update their documentation, retaining all coded guards. Do not change another subtask's classes to force retirement.

6. Hand off validation and the required decision record to their owning phases. Serves AC-003 and the common validation criteria.

   Validate runs the affected existing tests named above, detekt, formatting, and runtime-core repoTest, including `FailureCodeTotalityArchitectureTest`, through the installed runtime's full validation strategy. It checks A1 and A2 ownership, A4 declaration-only ports, A7 propagation, A9 and A10 placement, and G7 baseline shrinkage. Preserve the nonempty real-tree throwable scan. Spotless runs in a plain clone. Build proof belongs only to build. Plan and implement run no compilation, tests, or check suite; validation repairs may touch required production wiring, test setup, formatting, or lint while preserving behavior and architecture rules.

   The write_history phase adds the requested newest-first decision in `runtime-kotlin/agent/decisions.md`, titled "SKILL-400 subtask 2: external pack codes and the dropped publish payload". It records the contracts ownership, preserved handled sets, unchanged telemetry families, and deliberate removal of the publish payload because only tests read it. The final implementation summary must name that payload removal. History, commit, push, and PR settlement remain with their owning phases and runtime.

### Constraints and rollout

Preserve earlier same-branch edits to shared predicates, enums, and baselines. No schema version or persisted wire-format change is needed. Add no module, dependency, typealias, exception property, code-family metadata, suppression, or new `runCatching`. Keep `skillbill.error.core` free of shell-content imports, domain free of `java.nio` and ports imports, and Kotlin comments within the interface-KDoc rule. Keep detekt's throw, return, length, and complexity limits and rename enum-only files as planned. This is an in-place conversion with unchanged external behavior apart from the accepted code labels and explicitly retired test-only publish payload. Installation stays outside this child plan.

## Next Path

skill-bill goal SKILL-400

## Spec Path

.feature-specs/SKILL-400-runtime-error-codes/spec_subtask_2_external-platform-pack-and-addon-codes.md
