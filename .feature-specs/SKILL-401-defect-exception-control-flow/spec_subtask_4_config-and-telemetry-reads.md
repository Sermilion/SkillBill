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
