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

## Next Path

skill-bill goal SKILL-400

## Spec Path

.feature-specs/SKILL-400-runtime-error-codes/spec_subtask_2_external-platform-pack-and-addon-codes.md
