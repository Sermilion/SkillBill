# SKILL-391 - runtime-contracts-kernel-ownership-cleanup

## Mode

decomposed

## Intended Outcome

`runtime-contracts` is the shared kernel that 14 runtime modules compile against. The 2026-09-30 investigation (`investigation.md`, baseline `ae23f4f28`) found it healthy. It is an acyclic leaf with no DI, no mutable state and no I/O, and SKILL-374's cleanup landed. What remains is single-owner and adapter vocabulary that crept back in after the placement rule was written, plus small hygiene items.

After this change:
- the kernel holds no MCP-named error;
- the kernel holds no runtime-operations error that only runtime-engine reads;
- shared wire values are referenced instead of restated;
- dead and over-visible declarations are gone;
- no infra test declares a kernel package.

No module, framework, port, dependency bag, architecture-test class or baseline line is added. No module edge changes, and wire and CLI output stay byte-identical.

## Findings

Summarized from `investigation.md`, which has the census, the SKILL-374 landing audit, the principle table, the guard matrix and the evidence.

1. F-001 (P2): `InvalidMcpToolArgumentError` is MCP entry-point vocabulary, and runtime-mcp is its only reader. It moves to runtime-mcp.
2. F-002 (P2, handover): `McpToolPayloadKeys` belongs to runtime-mcp. SKILL-395 owns that move; this bundle does not touch it.
3. F-003 (P3): two wire tokens are restated as literals: `"none"` for `NO_APPLIED_LEARNINGS`, and `schema_invalid` for `FeatureTaskRuntimePhaseOutputFailureCode.SCHEMA_INVALID`.
4. F-004 (P3): the `Array<E>.failureWireByValue` overload has no callers.
5. F-005 (P3): six public declarations are read only inside their own file.
6. F-006 (P3): six runtime-infra/contracts repoTests declare `skillbill.contracts.*` packages, which only runtime-contracts may declare.
7. F-007 (P2): 28 of the 29 classes in `skillbill.error.operation` are read only by runtime-engine. They arrived after SKILL-374 wrote the placement rule. `OperationUsageError` stays because runtime-cli catches it as a base type.

What stays unchanged, with reasons, is listed in the investigation. In short: the module graph and `api` edges; contract versions and schema IDs; the other 93 single-owner errors (SKILL-374 retention); `JsonCodec`; `JsonPayloadContract`; `FailureWireCode`; `DecompositionPlanningResult`; the issue-key functions; `JvmSystemClock`; `GOAL_PLANNING_WAVE_CAP`; `Map<String, Any?>` plus `*Keys` as the wire representation.

## Acceptance Criteria

1. No file under `../../../runtime-kotlin/runtime-contracts/src` declares `InvalidMcpToolArgumentError`. runtime-mcp main declares it once, `internal`, in `skillbill.mcp.shared`, with the same constructor parameters, base class and message template.
2. runtime-mcp `McpLearningsSkippedContract` writes `applied_learnings` through `NO_APPLIED_LEARNINGS`. `InvalidFeatureTaskRuntimePhaseOutputSchemaError` defaults `failureCode` to `FeatureTaskRuntimePhaseOutputFailureCode.SCHEMA_INVALID.wireValue`. Both emitted values are unchanged.
3. `FailureWireCodeContract.kt` declares no `Array<E>.failureWireByValue` overload. The `EnumEntries` overload is unchanged.
4. The six declarations listed in investigation F-005 are `private`, with unchanged values. `UpdateCheckPayloadKeys` and `WorkflowContinueSessionSummaryPayloadKeys` stay public.
5. No Kotlin file outside runtime-contracts declares `package skillbill.contracts` or a subpackage of it. The six repoTests live in `skillbill.infrastructure.contracts.review` and `skillbill.infrastructure.contracts.workflow.featuretask`, with unchanged class and test names.
6. `runtime-contracts/src/main/kotlin/skillbill/error/operation` declares only `OperationUsageError`. The other 28 operation errors are declared `internal` in runtime-engine main, package `skillbill.engine.operation.core`, with unchanged names, constructor parameters, base classes and message templates. No source outside runtime-engine references them.
7. No `build.gradle.kts`, architecture-test class or architecture baseline file differs from the base branch, and the four runtime-contracts baselines stay empty.
8. CLI and MCP output is byte-identical: the existing MCP tools-list golden, the review and learning tool payload tests, and the CLI and engine operation tests pass unchanged.

## Constraints

- No new module, framework, port, dependency bag, architecture-test class or scanner extension.
- No baseline growth and no `RuntimeModuleCatalog` edge change.
- Class names, member names, message templates and wire values are unchanged; only packages, modules and visibility change.
- Follow `../../../runtime-kotlin/ARCHITECTURE.md` Design Principles and `docs/code-principles.md`: no `//` comments, KDoc only on interfaces, no inline FQNs, package sibling limits.

## Non-Goals

- Moving `McpToolPayloadKeys` or switching sqlite `ReviewRowMappers` off it (SKILL-395).
- Moving the other 93 single-owner errors, or renaming or regrouping error packages.
- Enum-typing `failureCode` fields or moving `DecompositionManifestValidationFailureCode`.
- Replacing the failure-code literals in runtime-infra/contracts or runtime-domain (SKILL-396, SKILL-397).
- Editing the ARCHITECTURE.md EXACT_PACKAGE_SCC sentence (SKILL-392).
- Changing `JsonCodec`, `JsonPayloadContract`, `DecompositionPlanningContracts`, the issue-key functions, or any schema or contract version.
- Adding tests: every change is a move, a reference or a visibility narrowing that existing suites already pin.

## Executable Scope

One subtask. Every change is behavior-preserving. The largest, F-007, is an import-only relocation across about 18 engine files, which is small enough not to bury the other changes in review.

1. runtime-contracts kernel ownership cleanup (`spec_subtask_1_runtime-contracts-kernel-ownership-cleanup.md`).

## Dependency Notes

This bundle runs on the current tree and waits for no other issue. Prefer landing after SKILL-387 and SKILL-388; if they are still unlanded, implement on the current tree and the second lander rebases. Every other sibling bundle can land in either order under the second-lander rules in the investigation's "Coordination with concurrent bundles" section. Those rules cover SKILL-395 (shared runtime-mcp files), SKILL-390 (engine test packaging) and SKILL-396 (infra/contracts repoTests).

## Suggested landing order

Across the concurrent runtime architecture bundles, as of 2026-10-01:

1. SKILL-387 (prose phase output) and SKILL-388 (runtime-application): already launched, both blocked at audit on 2026-10-01. Unblock them first if possible.
2. SKILL-389 (runtime-core)
3. SKILL-393 (runtime-ports)
4. SKILL-395 (runtime-mcp)
5. SKILL-391 (runtime-contracts) **(this bundle)**
6. SKILL-392 (runtime-cli)
7. SKILL-396 (runtime-infra)
8. SKILL-397 (runtime-domain)
9. SKILL-390 (runtime-engine)

This order keeps rebases small. SKILL-390 goes last because it touches the most engine files. It is a preference, not a prerequisite: this bundle waits for no other issue and follows the second-lander rules in its Dependency Notes. Run the goals one at a time per checkout, because each one switches branches.

## Validation Strategy

- `:runtime-contracts:test`, `:runtime-mcp:test`, `:runtime-mcp:repoTest`, `:runtime-engine:test`, `:runtime-cli:test`, `:runtime-application:test`, `:runtime-domain:test`, `:runtime-infra:contracts:repoTest`.
- `:runtime-core:repoTest`, covering the purity lock, package ownership, package acyclicity, sibling counts, wire vocabulary, failure-code totality and the raw-map guard.
- spotless and detekt through the pack gate.

## References

- `investigation.md` (this bundle)
- `../SKILL-374-runtime-contracts-shared-kernel`
- `runtime-kotlin/runtime-contracts/**`, `runtime-kotlin/runtime-engine/src/{main,test}/kotlin/skillbill/engine/operation/**`, `runtime-kotlin/runtime-mcp/src/main/kotlin/skillbill/mcp/{shared,core,review}/**`, `runtime-kotlin/runtime-infra/contracts/src/repoTest/**`

## Next Path

Run `skill-bill goal SKILL-391`.
