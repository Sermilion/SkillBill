# SKILL-395 Subtask 1 - mcp-vocabulary-ownership-and-adapter-hygiene

Parent spec: [.feature-specs/SKILL-395-runtime-mcp-architecture/spec.md](spec.md)
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

## Implementation Details

Wire rule for every task: each replacement constant has the same string value as the one it replaces and keeps its position in its `mapOf`/`linkedMapOf`. Tool names, argument keys, result keys, key order and error message text stay as they are, including the dispatcher's literal `"tool"` argument key and `"unknown tool"` detail. Re-check `git status` and HEAD before editing, because another session can move the tree.

### Constant split (computed in preplan, rechecked in plan)

`McpToolPayloadKeys` has 65 constants. 46 restate a value that a runtime-contracts main key object declares and are deleted. These 19 stay, because no runtime-contracts key object declares them:
- TOOL, CONTENT, TEXT, TYPE, IS_ERROR
- ENVELOPE, PAYLOAD, ORCHESTRATED, REPO, DRY_RUN
- REPOSITORY_IDENTITY, GOVERNED_SPEC_PATH, ARTIFACTS_PATCH, STEP_UPDATES
- FEATURE_VERIFY_FINISHED, GENERATED_DESCRIPTION, FINAL_PR_BODY, QUALITY_CHECK_FINISHED, ADD_LEARNING

`"orchestrated"` also appears in runtime-contracts, but only as the file-private `ORCHESTRATED_MODE` in `LifecycleTelemetryContracts.kt`, not in a key object. So ORCHESTRATED stays.

### Owner table for deleted constants

Use the closest semantic owner. Do not mint a new constant in runtime-contracts.

| Value(s) | Call sites | Owner |
|---|---|---|
| routed_skill, detected_stack, fallback, fallback_reason, scope_type, initial/final_failure_count, iterations, duration_seconds, result, session_id, failing_check_names, unsupported_reason, acceptance_criteria_count, rollout_relevant, spec_summary, feature_flag_audit_performed, review_iterations, audit_result, completion_status, history_relevance, history_helpfulness, gaps_found, commit_count, files_changed_count, was_edited_by_user, pr_created, pr_title | `lifecycle/McpLifecycleToolHandlers.kt`; `normalizeQualityCheckFinished` in `core/McpToolDispatcher.kt`; `advertisedEnumSubset` keys in `core/McpToolRegistry.kt` (COMPLETION_STATUS, RESULT) | `LifecycleTelemetryPayloadKeys` |
| error | `mcpToolErrorResult`; `workflow/WorkflowMcpResultMappers.kt` (5 sites); `workflow/WorkflowContinueMcpBranchMapsCore.kt` (2 sites); `workflow/WorkflowContinueMcpStandardMap.kt` | `LifecycleTelemetryPayloadKeys.ERROR` |
| skill | `resolveLearnings` | `LifecycleTelemetryPayloadKeys.SKILL` |
| session_id, workflow_status, current_step_id | `workflow/McpWorkflowToolHandlers.kt` | `WorkflowWirePayloadKeys` |
| issue_key | `workflow/McpWorkflowToolHandlers.kt` | `SharedPayloadKeys.ISSUE_KEY` |
| attempt | `featuretask/McpFeatureTaskSettlementHandlers.kt` | `SharedPayloadKeys.ATTEMPT` |
| review_run_id, review_text, decisions | `review/McpReviewToolHandlers.kt` | `ReviewVerificationSignalKeys` |
| review_session_id | `resolveLearnings` | `LearningPayloadKeys.REVIEW_SESSION_ID` |
| review_session_id | sqlite `ReviewRowMappers.kt:34` | `ReviewFinishedTelemetryPayloadKeys.REVIEW_SESSION_ID` |
| finding_count | `McpReviewImportSkippedContract` | `ReviewFinishedTelemetryPayloadKeys.FINDING_COUNT` |
| workflow, since, date_from, date_to, group_by | `telemetry/McpTelemetryToolHandlers.kt` | `TelemetryProxyPayloadKeys` |
| reason | the three skipped DTOs; `addLearning`; featuretask settlement block; `McpStdioServerTest:124` | `UpdateCheckPayloadKeys.REASON` |
| kind | featuretask settlement acknowledgment map | `ReviewAccountingPayloadKeys.KIND` |

Two owners are deliberate cross-feature choices. `UpdateCheckPayloadKeys.REASON` and `ReviewAccountingPayloadKeys.KIND` are the only public key-object owners of `reason` and `kind` in runtime-contracts. The non-goals forbid minting owners, and editing runtime-contracts beyond the deletion would collide with SKILL-391. Name both choices in the decisions entry (task 7) so review sees them as intended.

### Ordered tasks

1. **Create the runtime-mcp key object** (AC-001, AC-003).
   - Add `../../../runtime-kotlin/runtime-mcp/src/main/kotlin/skillbill/mcp/shared/McpToolPayloadKeys.kt`: `package skillbill.mcp.shared`, `internal object McpToolPayloadKeys`, holding only the 19 survivors with their current values and `: String` style.
   - Delete `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/contracts/mcp/McpToolPayloadKeys.kt`. It is the only file in the package, so `skillbill.contracts.mcp` disappears.
   - `shared` imports no other `skillbill.mcp.*` package, so the new edges cannot form a package cycle. The runtime-mcp cycle baseline stays empty.

2. **Repoint runtime-mcp main call sites** (AC-002, AC-003).
   - Change these 13 files: `core/McpToolRegistry.kt`, `core/McpToolDispatcher.kt`, `lifecycle/McpLifecycleToolHandlers.kt`, `review/McpReviewToolHandlers.kt`, `review/McpAdapterContracts.kt`, `workflow/McpWorkflowToolHandlers.kt`, `workflow/WorkflowMcpResultMappers.kt`, `workflow/WorkflowContinueMcpBranchMapsCore.kt`, `workflow/WorkflowContinueMcpStandardMap.kt`, `telemetry/McpTelemetryToolHandlers.kt`, `telemetry/TelemetryEventSchemaValidator.kt`, `featuretask/McpFeatureTaskSettlementHandlers.kt`, `scaffold/McpScaffoldRuntime.kt`.
   - In each file, replace `import skillbill.contracts.mcp.McpToolPayloadKeys` with `import skillbill.mcp.shared.McpToolPayloadKeys` if survivors are still used; otherwise drop the import.
   - Change every deleted-constant reference to its owner from the table, and add owner imports in lexicographic order.
   - Files inside `skillbill.mcp.shared` need no import.
   - Do not rename or move `McpScaffoldRuntime.kt`: `MCP_SCAFFOLD_RUNTIME_PATH` pins it.

3. **Repoint sqlite and the stdio test** (AC-002).
   - In `../../../runtime-kotlin/runtime-infra/sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/review/stage/ReviewRowMappers.kt`, line 34 becomes `getString(ReviewFinishedTelemetryPayloadKeys.REVIEW_SESSION_ID)`. Drop the `contracts.mcp` import; `ReviewFinishedTelemetryPayloadKeys` is already imported.
   - SKILL-396 may edit the same file. Apply this edit to whatever text is present.
   - In `runtime-mcp/src/test/kotlin/skillbill/mcp/core/McpStdioServerTest.kt`, line 124 becomes `UpdateCheckPayloadKeys.REASON`. Swap the import to `skillbill.contracts.system.UpdateCheckPayloadKeys`, and keep `skillbill.mcp.shared.McpToolPayloadKeys` only if other survivors are referenced there.
   - Finally, a repo-wide grep for `McpToolPayloadKeys` and `skillbill.contracts.mcp` outside `../../../runtime-kotlin/runtime-mcp/src` must return nothing.
   - Leave the prose mentions in `../../../docs/architecture-guidelines.md` and runtime-application `history.md` alone.

4. **Telemetry map keys through LifecycleTelemetryPayloadKeys** (AC-006).
   - In `scaffold/McpScaffoldRuntimeMaps.kt`, write the keys `"mode"`, `"telemetry_payload"`, `"skill"`, `"result"`, `"error"` and `"session_id"` as `LifecycleTelemetryPayloadKeys.MODE`, `TELEMETRY_PAYLOAD`, `SKILL`, `RESULT`, `ERROR` and `SESSION_ID`, in both the success and failure maps.
   - Leave the values (`"orchestrated"`, `"skill-bill-scaffold"`, `"failed"`, the outcome strings) and the other keys (`kind`, `skill_name`, `platform`, `family`, `area`, `skill_path`, `notes`) as literals, per the non-goals.
   - In `review/McpAdapterContracts.kt`, `McpOrchestratedPayloadContract.toPayload` writes `put(LifecycleTelemetryPayloadKeys.MODE, "orchestrated")`, `put(LifecycleTelemetryPayloadKeys.TELEMETRY_PAYLOAD, …)` and `put(LifecycleTelemetryPayloadKeys.SKILL, "bill-code-review")`.
   - Keep the `putAll` and `put` order exactly as it is.

5. **Review DTO hygiene** (AC-008).
   - In `review/McpAdapterContracts.kt`, make `McpReviewImportSkippedContract`, `McpTriageSkippedContract`, `McpLearningsSkippedContract` and `McpOrchestratedPayloadContract` `internal data class`, kept in place. This is the smallest diff and the easiest SKILL-391 merge.
   - Change `McpReviewImportSkippedContract.findingCount` from `Any?` to `Int`. `preview.findingCount` is already `Int`, so the JSON is unchanged.
   - Delete the `telemetrySkill` parameter; task 4 writes `"bill-code-review"` directly.
   - The "type-surface stays public" rule (runtime-application decisions df21f3a090f5) does not apply: these types are built and read only inside internal runtime-mcp functions.
   - Confirm with a grep that includes annotated declarations: no other public top-level declaration exists in runtime-mcp main besides `fun main` in `Main.kt`.

6. **Forwarders, toolName and cancellation** (AC-007, AC-009).
   - Delete `review/McpResultMappers.kt`. In `McpReviewToolHandlers.kt`, inline its six call sites:
     - line 33: `importResult.toImportedReviewContract().toPayload()`
     - lines 77 and 81: `result.toTriagePayload().toPayload()`
     - line 127: `.toReviewStatsPayload().toPayload()`
     - line 130: `.toFeatureVerifyStatsPayload().toPayload()`
     - line 132: `.toGoalStatsPayload().toPayload()`
   - Add the imports `skillbill.application.review.service.toImportedReviewContract`, `…service.toTriagePayload`, `skillbill.application.review.stats.toReviewStatsPayload`, `…stats.toFeatureVerifyStatsPayload` and `…stats.toGoalStatsPayload`. These are the same module edge McpResultMappers already had.
   - Workflow and telemetry `toMcpMap` functions stay, because tests use them.
   - Delete `WorkflowContinueResult.Standard.toStandardMcpMap()` from `workflow/WorkflowContinueMcpBranchMapsCore.kt`. The `Standard` arm in `WorkflowMcpResultMappers.kt:17` becomes `is WorkflowContinueResult.Standard -> standardMcpContinueMap(view, dbPath)`, with the same receiver properties the forwarder read.
   - In `shared/McpToolArguments.kt`, change the constructor property to `private val toolName: String`. `invalid` still reads it. `GovernedReviewEvidenceBridge.toolName()` is an unrelated private extension.
   - In `McpToolDispatcher.dispatch`, add `is CancellationException -> throw error` as the first `when` arm, before the `ShellContentContractException`/…/`IllegalStateException -> mcpToolErrorResult` arm. Import `kotlin.coroutines.cancellation.CancellationException`, as `McpScaffoldRuntime.kt:9` and `:42` already do.
   - Why this is a real fix: on the JVM, that type aliases `java.util.concurrent.CancellationException`, which extends `IllegalStateException`. Today cancellation becomes a client error result.

7. **Decisions entry** (AC-005).
   - In `../../../runtime-kotlin/agent/decisions.md`, insert a new `## [2026-10-01] SKILL-395: McpToolPayloadKeys is owned by runtime-mcp; supersedes the 2026-09-24 runtime-contracts clause` entry directly above the existing SKILL-393 2026-10-01 entry (newest first). Use the Context / Decision / Reason / Scope / Revisit when lines of its neighbours.
   - It must say that it supersedes the clause in "[2026-09-24] runtime-contracts is a shared kernel…" (around line 172) that kept `McpToolPayloadKeys` in runtime-contracts because runtime-infra/sqlite reads `REVIEW_SESSION_ID`.
   - It must say that the read was a SQL column label already owned by `ReviewFinishedTelemetryPayloadKeys`.
   - It must record the new owner: runtime-mcp `skillbill.mcp.shared`, `internal`, with only the 19 MCP-only values.
   - It must mention the zero-overlap guard method and the `reason`/`kind` owner choices.
   - Do not edit the 2026-09-24 entry itself.

8. **Zero-overlap guard method** (AC-004).
   - In `../../../runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/WireVocabularyArchitectureTest.kt`, add one `@Test` method next to `sqlite adapter key objects restate no shared payload key value`, for example `` `mcp tool payload keys restate no shared payload key value` ``.
   - The method takes `WireVocabularyArchitectureSupport.scanRuntimeMainSources().declarations`, filters to `owner == "skillbill.mcp.shared.McpToolPayloadKeys"`, and asserts the selection is non-empty with a message naming the owner. An empty selection would mean the scanner lost the object.
   - It maps the selection to `value` and intersects with `payloadKeyValues(...)` over the ten classes: `SharedPayloadKeys`, `LifecycleTelemetryPayloadKeys`, `ReviewFinishedTelemetryPayloadKeys`, `ReviewVerificationSignalKeys`, `ReviewAccountingPayloadKeys`, `UpdateCheckPayloadKeys`, `TelemetryProxyPayloadKeys`, `WorkflowWirePayloadKeys`, `LearningPayloadKeys`, `WorkflowContinueSessionSummaryPayloadKeys`.
   - It asserts `emptyList()` equals the sorted intersection, with a message saying the MCP key object must reference the shared owner.
   - Add the imports for the six classes not yet imported. Text-scanning is required because runtime-core cannot reflect an internal runtime-mcp object.
   - Add no new class, no baseline file and no `PrincipleEnforcementInventory` change.

### Tests

- **Add:** only the task-8 method. The realistic bug it catches: someone re-adds `ERROR = "error"` (or any shared value) to the MCP key object. Every other suite would pass, because the wire value is identical.
- **Change:** only the `McpStdioServerTest` reference and imports from task 3.
- **No new tests** for the cancellation arm, DTO visibility or forwarder inlining. They are type-level or one-line control-flow changes, and the AC text is checked by reading the files.
- **Behaviour baseline**, all run in validate: the unchanged stdio, dispatch, argument-shape, review, scaffold, workflow mapper and parity suites, plus the `mcp-tools-list.json` golden.

### Constraints

- This phase and implement do not compile, build, run tests or run check. Validate owns `:runtime-mcp:test`, `:runtime-mcp:repoTest`, `:runtime-core:repoTest` (WireVocabulary, RuntimeEngineInboundApi, RuntimeLayerBoundary, ApplicationPackageAcyclicity, PackageSiblingCount), the sqlite review suites, spotless and detekt.
- Do not touch runtime-application or runtime-cli sources, `McpComponent` accessors, the registry shape, schema projection or the framer.
- Do not edit other sessions' bundles.
- Leftover or misordered imports across the 13 main files are the main lint risk. Keep imports sorted and remove unused ones.
- `shared` grows from 3 to 4 files, well under the sibling ceiling.

## Next Path

skill-bill goal SKILL-395

## Spec Path

.feature-specs/SKILL-395-runtime-mcp-architecture/spec_subtask_1_mcp-vocabulary-ownership-and-adapter-hygiene.md
