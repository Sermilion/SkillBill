# SKILL-401 Subtask 4 - config-and-telemetry-reads

Parent spec: [.feature-specs/SKILL-401-defect-exception-control-flow/spec.md](./spec.md)
Issue key: SKILL-401

## Scope

Remove the IAE control flow around telemetry config and settings reads: the 4 runtime-application sites in `config/ConfigResolutionService.kt` and `telemetry/settings/TelemetrySettingsLoading.kt`, `FileTelemetryConfigStore` in runtime-infra/host, and the 5 runtime-infra/skills config-read sites that reuse the telemetry config reader.

**`config/ConfigResolutionService.kt:30` and `:56`, plus the telemetry config read.**

- Change `TelemetryConfigStore.read()` (`runtime-ports/.../ports/telemetry/transport/`) to return a sealed `TelemetryConfigRead { Absent; Malformed(reason); Present(document) }` in the port's model package.
- `infra/host/.../FileTelemetryConfigStore.kt`:
  - `readTelemetryConfigFile` becomes the non-throwing `readTelemetryConfigFileRead(path)`.
  - The reasons stay `"Telemetry config at '<path>' is not valid JSON."` and `"... must contain a JSON object."`.
  - `ensureTelemetryConfigFile` and the edge-only callers keep throwing IAE with the same reason.
- `ConfigResolutionService` maps `Malformed` to today's `MalformedMachineConfigError` text, or its code.
- `TelemetrySettingsFromStore.loadTelemetrySettingsFromStore` keeps throwing IAE on `Malformed` for `load()` callers, which are handled only at the edge.

**`telemetry/settings/TelemetrySettingsLoading.kt:27` and `:29`.**

- Add a non-throwing `resolveTelemetrySettingsFromStore(...)` that returns a sealed `TelemetrySettingsLoad { Loaded(settings); Unavailable(reason) }`. It covers malformed config, the `install_id` `require`, and any other input `require`/`check` the census finds in the chain.
- `loadTelemetrySettingsFromStore` becomes that function plus `throw IllegalArgumentException(reason)`, so edge behaviour is unchanged.
- Add `loadOrUnavailable(materialize)` to `TelemetrySettingsProvider`. `DefaultTelemetrySettingsProvider` implements it, and about 8 test fakes return `Loaded(settings)`.
- `telemetrySettingsOrNull` branches on the result and still emits `diagnostics.error(TELEMETRY_SETTINGS_LOAD_FAILURE_MESSAGE)`. The degrade record stays.

**Skills config reads**

- **Five config-read sites**: `externalplatformpack/FileExternalPlatformPackSourceConfigStore.kt:36`, `:63`, `:100`, plus `externaladdon/FileExternalAddonSourceConfigStore.kt:37` and `externaladdon/ExternalAddonSourceEntries.kt:26`.
  - Use `readTelemetryConfigFileRead` and map `Malformed(reason)` to the same `ExternalPlatformPackConfigError` or `ExternalAddonConfigError` message, or their codes.
- **`FileExternalPlatformPackSourceConfigStore.kt:151`**: catch `InvalidPathException` from `Path.of` in `resolveExternalPlatformPackSourcePath`, keeping the text.

## Acceptance Criteria

1. No main source in `ConfigResolutionService`, `TelemetrySettingsLoading`, `FileTelemetryConfigStore` or the external platform-pack and add-on config stores catches or `is`-checks `IllegalArgumentException` or `IllegalStateException`, except a narrowed `InvalidPathException` catch for `Path.of`.
2. `TelemetryConfigStore.read()` returns `TelemetryConfigRead`, and `TelemetrySettingsProvider` offers `loadOrUnavailable`; every implementation and test fake is updated.
3. Edge-only callers (`ensureTelemetryConfigFile`, `load()`) still throw IAE with the same reason, and `telemetrySettingsOrNull` still emits `TELEMETRY_SETTINGS_LOAD_FAILURE_MESSAGE`.

## Non-Goals

Other runtime-application sites (subtask 5); other runtime-infra/skills sites (subtask 6).

## Test obligations

- `TelemetrySettingsLoad.Unavailable`: `telemetrySettingsOrNull` returns null and records the degrade.

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

.feature-specs/SKILL-401-defect-exception-control-flow/spec_subtask_4_config-and-telemetry-reads.md

## Implementation Details

This plan uses the upstream preplan digest as its only repository evidence. It requires no dependency work or further decomposition. Historical line numbers and fake counts are not authoritative; use the digest's current symbols and caller inventory. Paths below are relative to `runtime-kotlin/`. Production paths use `<module>/src/main/kotlin/skillbill/` and test paths use `<module>/src/test/kotlin/skillbill/`, with infrastructure modules nested under `runtime-infra/<owner>`.

### Ordered implementation tasks

1. Introduce the configuration read result and convert the file boundary. Serves AC-001, AC-002 and AC-003. In `runtime-ports/.../ports/telemetry/model`, declare `TelemetryConfigRead` as a sealed interface with nested `Absent`, `Malformed(reason)` and `Present(document)` variants. Change `runtime-ports/.../ports/telemetry/transport/TelemetryConfigStore.kt` so `read()` returns that result. In `runtime-infra/host/.../FileTelemetryConfigStore.kt`, add `readTelemetryConfigFileRead(path)` and make the store return its result. Distinguish absence, malformed JSON, a non-object root and a valid document without catching defect exceptions. Retain the existing two malformed-document reasons verbatim. Keep `readTelemetryConfigFile` as a throwing compatibility wrapper for edge-only consumers and have `ensureTelemetryConfigFile` preserve IAE and its reason for malformed input. Preserve defaults, normalization, unknown keys, output ordering, newline and materialization behavior. Reuse `runtime-infra/host/.../FileTelemetryConfigStoreTest.kt` for these boundary outcomes and emitted bytes; adapt assertions to the result where appropriate without changing expected text. No separate test for trivial result forwarding is warranted.

2. Convert machine-config and external-source consumers to explicit read outcomes. Serves AC-001, AC-002 and AC-003. In `runtime-application/.../application/config/ConfigResolutionService.kt`, replace both read catches with result branches. Map `Malformed` to `malformedMachineConfigError` with key `""`, value `<document>` and reason `is not valid JSON.`, rather than exposing the lower-level reason. Update `TelemetryConfigStore.writeTelemetryLevel` in its existing owning port file to handle all read variants explicitly and retain absent-file behavior. Preserve declaration-only port rules; the digest identifies this consumer in the port file but does not establish its declaration shape, so implement must retain an extension or other already authorized shape rather than introduce interface behavior. In `runtime-infra/skills/.../externalplatformpack/FileExternalPlatformPackSourceConfigStore.kt`, `externaladdon/FileExternalAddonSourceConfigStore.kt` and `externaladdon/ExternalAddonSourceEntries.kt`, use the shared file-read result at all five read sites. Preserve `ExternalPlatformPackConfigError` and `ExternalAddonConfigError` framing and their existing owner-code classification. Narrow only the `Path.of` catch in `resolveExternalPlatformPackSourcePath` to `InvalidPathException`, retaining its message. Keep absence policies and unrelated I/O or coded failures unchanged. Reuse the existing external platform-pack and add-on store tests for malformed and absent documents. No new test is needed for library catch narrowing.

3. Make telemetry input parsing return values with shared reasons. Serves AC-001, AC-002 and AC-003. In `runtime-domain/.../telemetry/TelemetryConfigRules.kt`, expose non-throwing forms and single-source message helpers for level, legacy boolean and positive-integer parsing. Existing throwing entry points delegate to those forms so edge reasons and validation order cannot drift. In `runtime-application/.../application/telemetry/config/TelemetrySettingsFromStore.kt`, add `resolveTelemetrySettingsFromStore(materialize, environment, configStore)` and declare its `TelemetrySettingsLoad` result beside the telemetry port models, with nested `Loaded(settings)` and `Unavailable(reason)` variants. Branch explicitly for malformed config, a non-object telemetry block, invalid level, invalid legacy boolean, invalid positive integer and enabled telemetry without an install id. Preserve trimming, case normalization, legacy enabled handling, environment precedence and numeric behavior. Leave `TelemetryProxyUrl.kt` normalization unchanged. Keep `loadTelemetrySettingsFromStore` as the edge wrapper that converts only `Unavailable` to IAE with the same reason. The digest supplies the rejection categories but not the exact parser signatures or individual messages; implement must derive those from the existing owners and preserve them, without inventing replacements. Reuse existing boundary coverage rather than add one test per helper. Confirm during implement whether the named store and settings tests already drive these rejection branches; this plan does not authorize duplicate parser tests.

4. Publish the settings result through the provider and retain the diagnostic fallback. Serves AC-001, AC-002 and AC-003. Add abstract `loadOrUnavailable(materialize: Boolean = false)` to `runtime-ports/.../ports/telemetry/transport/TelemetrySettingsProvider.kt`. Implement it in `runtime-application/.../application/telemetry/settings/DefaultTelemetrySettingsProvider.kt` through the resolver. Keep `load()` throwing IAE with the original rejection reason. In `telemetry/settings/TelemetrySettingsLoading.kt`, make `telemetrySettingsOrNull` branch on `Loaded` or `Unavailable`; the latter emits exactly `TELEMETRY_SETTINGS_LOAD_FAILURE_MESSAGE` and returns null through the existing degradation record. Remove IAE and ISE interception rather than moving it into another wrapper. Cancellation, interruption, unrelated coded failures and real defects propagate. Extend `runtime-application/.../application/telemetry/settings/TelemetrySettingsLoadFailureTest.kt` through the result contract. Its realistic regression is an explicit unavailable configuration either aborting optional telemetry or losing the existing diagnostic. Replace the fake using `error("config unreadable")` with `Unavailable` and retain the existing single-diagnostic, disabled-fallback, cancellation and interruption assertions. This satisfies the existing test obligation without a duplicate fallback test.

5. Update every implementation and fake with the port changes in this subtask. Serves AC-002 and preserves AC-003. Convert config fakes to `Absent`, `Malformed` or `Present` and settings fakes to `Loaded` or `Unavailable` according to their existing observable behavior. The digest's affected test inventory is application `updatecheck/UpdateCheckServiceTest.kt`, `ApplicationCooperativeFailureBoundaryTest.kt`, `review/service/ReviewServicePreviewImportTest.kt`, `telemetry/settings/TelemetrySettingsLoadFailureTest.kt` and `telemetry/service/TelemetryAutoSyncDiagnosticTest.kt`; engine `operation/updatecheck/UpdateCheckOperationTest.kt`, `featuretask/runner/SlotBaselineMcpLifecycleCapture.kt`, `FeatureTaskRuntimeTerminalFailureReasonTest.kt` and `FeatureTaskRuntimeRunnerTestSupport.kt`; infra skills `install/InstallTestSupport.kt`; and core `di/workflow/ApplicationPersistencePortTestSupport.kt`. Keep current package locations when the digest supplies only a filename, especially relocated engine helpers. Update these contracts without changing their expected messages or unrelated assertions. Run their affected suites only in validate. Mechanical fake changes have no separate test obligations.

6. Leave a complete implementation for the later audit and validation gates. Serves AC-001 through AC-003 and the unchanged common acceptance criteria. Implement removes the scoped forbidden catches and type checks, preserves throwing edge wrappers, updates callers and fakes together, and retains every governed parse-boundary function and guard entry. Audit inspects each criterion, message mapping, byte-preservation rule and cooperative propagation boundary without executing tests. Validate runs the existing host, external-source and settings suites plus affected caller suites, detekt and runtime-core repoTest, including `TypedParseBoundaryArchitectureTest`, `PortsDeclarationArchitectureTest`, `FailureCodeTotalityArchitectureTest` and `WireVocabularyArchitectureTest`. It also runs the repository's actual collect-all full gate and `scripts/validate_agent_configs` as required by the digest. Use the manifest-declared commands discovered by validate, including its cache-bypassing counterpart when the gate requires it; do not invent command argv here. Build proof belongs only to the runtime-owned build phase. Validation preserves the plain-clone Spotless constraint and may repair production wiring, test setup, formatting or lint within the required acceptance work. No validation claim follows from this plan.

### Constraints and settled assumptions

Expected configuration rejection is a value; IAE and ISE remain defects except for retained edge-only throwing wrappers. Preserve the exact handled failure set, reason order, user-visible messages, telemetry capture behavior and persisted bytes. Never replace removed arms with an unchecked catch of `SkillBillRuntimeException`, a broad catch or a new `runCatching`. Keep existing owner-code guards where applicable. The legacy external config classes are present according to the digest and remain in scope.

Keep domain helpers pure and free of ports or `java.nio` imports. Ports contain declarations only, with sealed results and nested variants beside their payloads. Use no new throwable, typealias, Result or Either abstraction. Apply architecture rules A1, A2, A4, A7, A10 and A11; G7 prohibits widened baselines and suppressions. Retain wire-key ownership, file-size limits, package ownership, comment and KDoc rules, and all parse-boundary coverage. Shrink a custom-throwable baseline only if its class loses its final production reader; the digest gives no evidence that these config error owners do, so retaining them is the default.

The digest does not name every successful-input test or exact parser signature. Implement confirms those details from the current owners while preserving the behavior described above. That bounded confirmation is implementation work, not a planning discovery pass. The only test obligation added by this plan is the existing unavailable-settings boundary, covered by extending its existing test. Retain all existing regression and governed parity coverage.

Plan changes only this sub-spec. Implementation, simplify, audit, review, finding verification, build evidence, validation commands, history, commit/push and PR work stay with their owning runtime phases. No installer refresh, workflow continuation, branch mutation, migration, feature flag, skill-source change or generic-runner change is part of this plan. No source discovery, build, test or repository check ran during planning.
