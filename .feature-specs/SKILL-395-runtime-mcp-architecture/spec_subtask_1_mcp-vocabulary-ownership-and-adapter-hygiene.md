# SKILL-395 Subtask 1 - mcp-vocabulary-ownership-and-adapter-hygiene

Parent spec: [.feature-specs/SKILL-395-runtime-mcp-architecture/spec.md](./spec.md)
Issue key: SKILL-395

## Scope

Covers F-001 through F-005 from investigation.md.

(1) In runtime-infra/sqlite `review/stage/ReviewRowMappers.kt`, read the review session column through `ReviewFinishedTelemetryPayloadKeys.REVIEW_SESSION_ID`.
(2) Move `McpToolPayloadKeys` from runtime-contracts `skillbill/contracts/mcp/McpToolPayloadKeys.kt` to runtime-mcp `skillbill/mcp/shared/McpToolPayloadKeys.kt` as `internal object`. Delete the ISSUE_KEY, SESSION_ID, ERROR, ROUTED_SKILL, DETECTED_STACK, RESULT, REVIEW_RUN_ID, REVIEW_SESSION_ID, REASON, WORKFLOW and KIND constants, plus any other constant whose value a runtime-contracts key object declares. Point call sites at the shared owners in the investigation.md table. Update imports in the 12 runtime-mcp main files and McpStdioServerTest.
(3) Add one test method to the existing `WireVocabularyArchitectureTest`. It takes the `declarations` of `WireVocabularyArchitectureSupport.scanRuntimeMainSources()` whose owner is `skillbill.mcp.shared.McpToolPayloadKeys`, asserts there is at least one, and asserts an empty intersection with the reflected values of SharedPayloadKeys, LifecycleTelemetryPayloadKeys, ReviewFinishedTelemetryPayloadKeys, ReviewVerificationSignalKeys, ReviewAccountingPayloadKeys, UpdateCheckPayloadKeys, TelemetryProxyPayloadKeys, WorkflowWirePayloadKeys, LearningPayloadKeys and WorkflowContinueSessionSummaryPayloadKeys.
(4) Add a dated entry to runtime-kotlin/agent/decisions.md that supersedes the 2026-09-24 clause keeping McpToolPayloadKeys in runtime-contracts, citing the borrowed column label.
(5) In McpScaffoldRuntimeMaps.kt and the review orchestrated-payload builder, write the mode, telemetry_payload, skill, result, error and session_id keys through LifecycleTelemetryPayloadKeys.
(6) In McpToolDispatcher.dispatch, rethrow CancellationException before the arm that maps IllegalStateException to a client error.
(7) Make the four McpAdapterContracts.kt types internal, or fold them into private builders. Remove the `telemetrySkill` parameter and write bill-code-review directly. Type `findingCount` as Int.
(8) Delete McpResultMappers.kt and call the application payload functions at the six call sites. Remove `toStandardMcpMap` so the Standard arm calls `standardMcpContinueMap(view, dbPath)`. Make `McpToolArguments.toolName` private.

## Acceptance Criteria

1. runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/contracts/mcp/McpToolPayloadKeys.kt does not exist, and no runtime-contracts main file declares an object named McpToolPayloadKeys; runtime-kotlin/runtime-mcp/src/main/kotlin/skillbill/mcp/shared/McpToolPayloadKeys.kt declares `internal object McpToolPayloadKeys`.
2. No file outside runtime-kotlin/runtime-mcp/src references McpToolPayloadKeys or imports package skillbill.contracts.mcp; runtime-infra/sqlite ReviewRowMappers.kt reads the review session column through ReviewFinishedTelemetryPayloadKeys.REVIEW_SESSION_ID.
3. McpToolPayloadKeys declares none of the values issue_key, session_id, error, routed_skill, detected_stack, result, review_run_id, review_session_id, reason, workflow or kind, and no other value that a key object in runtime-contracts main declares; runtime-mcp call sites that used those constants reference the shared owner objects instead.
4. The existing WireVocabularyArchitectureTest class contains a test method that selects scanRuntimeMainSources() declarations with owner skillbill.mcp.shared.McpToolPayloadKeys, asserts the selection is non-empty, and asserts its values have an empty intersection with the reflected values of SharedPayloadKeys, LifecycleTelemetryPayloadKeys, ReviewFinishedTelemetryPayloadKeys, ReviewVerificationSignalKeys, ReviewAccountingPayloadKeys, UpdateCheckPayloadKeys, TelemetryProxyPayloadKeys, WorkflowWirePayloadKeys, LearningPayloadKeys and WorkflowContinueSessionSummaryPayloadKeys; no new architecture-test class and no baseline file is added.
5. runtime-kotlin/agent/decisions.md contains a dated entry that supersedes the clause keeping McpToolPayloadKeys in runtime-contracts because runtime-infra/sqlite reads REVIEW_SESSION_ID, states that the read was a SQL column label already owned by ReviewFinishedTelemetryPayloadKeys, and records the object's new owner module.
6. In McpScaffoldRuntimeMaps.kt and in the runtime-mcp review code that builds the orchestrated payload, no map key is a string literal equal to mode, telemetry_payload, skill, result, error or session_id; those keys are written through LifecycleTelemetryPayloadKeys constants.
7. McpToolDispatcher.dispatch has an arm that rethrows CancellationException, placed before the arm that maps IllegalStateException to mcpToolErrorResult.
8. runtime-mcp main has no public top-level declaration other than `fun main` in Main.kt; the review import-skipped payload takes findingCount as Int, and no runtime-mcp declaration has a telemetrySkill parameter.
9. runtime-kotlin/runtime-mcp/src/main/kotlin/skillbill/mcp/review/McpResultMappers.kt does not exist; no runtime-mcp main function named toStandardMcpMap exists, and the WorkflowContinueResult.Standard arm calls standardMcpContinueMap; McpToolArguments declares toolName as private.

## Non-Goals

- Changing any tool name, argument key value, result key value, key order or error message on the MCP wire.
- Removing the verify-workflow planningResult parsing in MCP or CLI; it is recorded as a follow-up because it changes the error result for a malformed plan.
- Moving readOnlyFullStateCommand, changing ScaffoldInvocationArgs or runScaffoldInvocation, or editing runtime-application or runtime-cli sources.
- Minting owners for the ok, error and skipped status values or for scaffold-only keys (platform, family, area, notes, skill_path, kind).
- Removing McpComponent accessors, renaming or moving McpScaffoldRuntime.kt (pinned by MCP_SCAFFOLD_RUNTIME_PATH), splitting files by size, adding handler interfaces or replacing the JSON-RPC framer.
- Editing other sessions' bundles or the runtime-application history.md record.

## Dependency Notes

Depends on: none
No dependency on open bundles. SKILL-391 (runtime-contracts) leaves McpToolPayloadKeys to this subtask. For decisions.md and WireVocabularyArchitectureTest, and for SKILL-396 edits to ReviewRowMappers.kt, whichever bundle lands second applies its edit to the text present then. SKILL-389 removes RuntimeComponent accessors that McpComponent does not read. SKILL-392 relies on the unchanged runtime-mcp parity tests.

## Validation Strategy

Read each edited file against its criterion. The validate phase compiles runtime-contracts, runtime-infra sqlite, runtime-mcp (including KSP InjectMcpComponent) and runtime-core repoTest. It then runs `:runtime-mcp:test`, `:runtime-mcp:repoTest`, `:runtime-core:repoTest` and the sqlite review suites; the unchanged stdio, dispatch, parity and mcp-tools-list.json golden suites are the byte-identity evidence. Test obligations: only the new WireVocabularyArchitectureTest method; the McpStdioServerTest import update is mechanical.

## Next Path

skill-bill goal SKILL-395

## Spec Path

.feature-specs/SKILL-395-runtime-mcp-architecture/spec_subtask_1_mcp-vocabulary-ownership-and-adapter-hygiene.md
