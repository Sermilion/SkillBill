# SKILL-401 Subtask 7 - cli-install-scaffold-agent-addon

Parent spec: [.feature-specs/SKILL-401-defect-exception-control-flow/spec.md](./spec.md)
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
