# SKILL-395 - runtime-mcp-architecture

## Mode

decomposed

## Intended Outcome

runtime-mcp is a healthy entry adapter; SKILL-375's structure landed. This change fixes vocabulary ownership and small hygiene with byte-identical wire output.

1. McpToolPayloadKeys moves from runtime-contracts into runtime-mcp `skillbill.mcp.shared` as an internal object. Its only outside read (sqlite ReviewRowMappers, a SQL column label) switches to ReviewFinishedTelemetryPayloadKeys, which already declares the value.
2. The 11 constants that restate shared owners are deleted, and call sites reference the owners.
3. The existing WireVocabularyArchitectureTest gains one method so the MCP key object cannot restate shared values again.
4. MCP telemetry maps reference LifecycleTelemetryPayloadKeys instead of hand-typed keys.
5. The dispatcher rethrows CancellationException.
6. The four public review DTO classes become internal, with the unused parameter removed and `findingCount` typed.
7. Six forwarders are inlined, and `McpToolArguments.toolName` becomes private.

No new modules, ports, classes, frameworks or architecture-test classes; no baseline growth.

## Acceptance Criteria

The subtask spec (`spec_subtask_1_mcp-vocabulary-ownership-and-adapter-hygiene.md`) holds the checkable criteria. The feature is done when all of them hold:

1. `McpToolPayloadKeys` is an `internal object` in runtime-mcp `skillbill.mcp.shared`, no runtime-contracts file declares it, and runtime-infra/sqlite `ReviewRowMappers` reads the session column through `ReviewFinishedTelemetryPayloadKeys.REVIEW_SESSION_ID` (subtask AC 1-2).
2. `McpToolPayloadKeys` restates no value a runtime-contracts key object declares, and the existing `WireVocabularyArchitectureTest` enforces that with one added method (subtask AC 3-4).
3. `../../../runtime-kotlin/agent/decisions.md` records the new owner and supersedes the clause that kept the object in runtime-contracts (subtask AC 5).
4. MCP telemetry and orchestrated-payload maps use `LifecycleTelemetryPayloadKeys` constants instead of literal keys (subtask AC 6).
5. `McpToolDispatcher.dispatch` rethrows `CancellationException` before mapping `IllegalStateException` (subtask AC 7).
6. runtime-mcp main has no public top-level declaration other than `fun main`, the review import-skipped payload takes `findingCount: Int`, `McpResultMappers.kt` is gone, and `McpToolArguments.toolName` is private (subtask AC 8-9).
7. MCP wire output (tool names, argument keys, result keys, key order and error messages) is byte-identical to `ae23f4f28`.

## Constraints

- No new module, port, class hierarchy, framework or architecture-test class; no baseline growth.
- Byte-identical MCP wire output.
- Follow `../../../runtime-kotlin/ARCHITECTURE.md` Design Principles and `docs/code-principles.md`.

## Non-Goals

- Changing any MCP tool name, argument or result key value, key order or error message.
- Removing the verify-workflow `planningResult` parsing in MCP or CLI (recorded follow-up).
- Editing runtime-application or runtime-cli sources, or minting owners for scaffold-only keys and the ok/error/skipped status values.
- Splitting files by size, adding handler interfaces or replacing the JSON-RPC framer.

## Dependency Notes

This bundle runs on the current tree and waits for no other issue. Shared files follow the second-lander rule: whichever bundle lands second applies its edit to the text present at that point.
- SKILL-391 (runtime-contracts) moves `InvalidMcpToolArgumentError` into `skillbill.mcp.shared` and edits `McpToolArguments.kt`, `McpToolDispatcher.kt` and `McpAdapterContracts.kt` (`NO_APPLIED_LEARNINGS` reference). SKILL-391 keeps `UpdateCheckPayloadKeys` and `WorkflowContinueSessionSummaryPayloadKeys` public, so the reflection in subtask AC 4 compiles in either order.
- SKILL-396 (runtime-infra) may edit `ReviewRowMappers.kt`; keep both edits.
- SKILL-389 removes `RuntimeComponent` accessors that `McpComponent` does not read.

## Suggested landing order

Across the concurrent runtime architecture bundles, as of 2026-10-01:

1. SKILL-387 (prose phase output) and SKILL-388 (runtime-application): already launched, both blocked at audit on 2026-10-01. Unblock them first if possible.
2. SKILL-389 (runtime-core)
3. SKILL-393 (runtime-ports)
4. SKILL-395 (runtime-mcp) **(this bundle)**
5. SKILL-391 (runtime-contracts)
6. SKILL-392 (runtime-cli)
7. SKILL-396 (runtime-infra)
8. SKILL-397 (runtime-domain)
9. SKILL-390 (runtime-engine)

This order keeps rebases small. SKILL-390 goes last because it touches the most engine files. It is a preference, not a prerequisite: this bundle waits for no other issue and follows the second-lander rules in its Dependency Notes. Run the goals one at a time per checkout, because each one switches branches.

## Validation Strategy

Validate phase only. Checks:
- `:runtime-mcp:test` (stdio server, dispatch, argument-shape, review, scaffold, workflow mapper and parity suites) and the mcp-tools-list.json golden, as byte-identity evidence;
- `:runtime-mcp:repoTest`;
- `:runtime-core:repoTest`: WireVocabularyArchitectureTest including the new method, RuntimeEngineInboundApiTest, RuntimeLayerBoundaryArchitectureTest, ApplicationPackageAcyclicityArchitectureTest, and the MCP scaffold runtime guard;
- the runtime-infra sqlite review suites;
- spotless and detekt through the pack gate.

Test obligations: only the new zero-overlap method, which guards a rule that has no enforcement for runtime-mcp today. Existing suites are the behavior baseline; update only the McpStdioServerTest import.
