# SKILL-401 Subtask 5 - application-and-infra-adapters

Parent spec: [.feature-specs/SKILL-401-defect-exception-control-flow/spec.md](./spec.md)
Issue key: SKILL-401

## Scope

Remove the IAE/ISE control flow at the remaining 5 runtime-application sites and at the runtime-infra host, http, contracts, sqlite and workflow sites (21).

**Application**

**`workflow/service/LegacyGoalRunnerControlMigration.kt:63`, `:121` and `:149`**: use the shared validators. All three messages are unchanged.

**`review/parallel/verification/ParallelCodeReviewRunnerFailureAdmission.kt:144` and `:146`.**

- Run the census over `ParallelReviewFindingParser.parse`.
- If no input can make it throw, drop both arms. A defect then propagates.
- A test that injects a throwing `parse` lambda to assert `ReviewRegisterParseSeamException` (or its code) pins the removed control flow. Change its expected type to the propagated defect (exception-type edit).
- If an input path still throws, give it a parse-result variant instead.

**host**

- **`FileSystemRepoLocalConfig.kt:110`**: apply `ReviewContextBudgetPolicy.violation(...)` before construction, and keep the `MalformedRepoLocalConfigError` text (or its code, if SKILL-399 converted it).
- **`JdkFeatureTaskRuntimeWorkerSupervisor.kt:196` and `:200`** (heartbeat liveness).
  - Find which types `heartbeat()` raises for expected renewal failures. Expected: `IOException`, plus the coded `SkillBillRuntimeException` that SKILL-398 subtask 5 gives database-busy and database-access failures.
  - Replace the IAE and ISE arms with an arm for those types, keeping `reportFailure` and the retry.
  - If the census shows renewal still signals an expected condition with ISE, convert that source to a `FeatureTaskRuntimeHeartbeatTick` value or the coded failure first.

**http**

- **`HttpRequestUri.kt:11`**: use `URI(url)` and catch `URISyntaxException`. The proxy-request failure (`TelemetryHttpFailureCode.PROXY_REQUEST_FAILED`, or `TelemetryProxyRequestFailureError` if still present) keeps its text and detail.
- **`GitHubReleaseCatalogAdapter.kt:33`**: the URL and headers are constants, so this is kind D. Drop the arm.

**contracts**

- **`ClasspathContractSchemaLoader.kt:103`, `:124`, `:167`**
  - Own identity checks throw the failure directly.
  - Library failures are narrowed to the networknt or Jackson types that `getSchema`, `readTree` and `writeValueAsString` document, such as `JsonProcessingException` and `com.networknt.schema.JsonSchemaException` if it applies. Then drop the IAE arms.
- **`workflow/decomposition/DecompositionManifestSchemaValidator.kt:134`**
  - The source is the code's own `require(parser.nextToken() == null)`. Replace it with an explicit throw with reason `"YAML is malformed: YAML contains trailing content or multiple documents."` and failure code `malformed`.
  - Keep SKILL-398 subtask 3's duplicate-key handling.
- **`DecompositionManifestSchemaValidator.kt:149`** and **`DecompositionManifestBundleJournalSchemaValidator.kt:115`**: replace `convertValue` with a reader or `treeToValue` and catch `JsonProcessingException`, keeping `error.message` in the same position.

**sqlite**

- **`SqliteRejectedOutputDiagnosticRepository.kt:264`**
  - Replace `RejectedOutputLifecycle.valueOf` with `entries.firstOrNull { it.name == raw.uppercase() }`.
  - Call `RejectedOutputDiagnostic.violation(...)` before construction. If `corruptRecord` takes a `Throwable`, give it a message variant that yields the same output.
  - Apply this to the read shape SKILL-398 subtask 2 left.
- **`workflow/goalrunner/runner/LegacyGoalRunnerControlLedgerMigration.kt:93`, `:153`, `:182`** and **`GoalRunnerControlStoreDecodePolicies.kt:29`, `:35`, `:76`**: use the shared validators. For example, `:29` keeps `"…invalid code_review_mode: <unknownWireValueMessage>"`.
- **`GoalRunnerControlStoreDecodePolicies.kt:101`**: drop the IAE arm, because `SerializationException` is already caught.
- **`worklist/SQLiteWorkListRepository.kt:154`**: use `parsePersistedInstantOrNull`, keeping `"invalid $column '$value'"`.

**workflow**

- **`featuretask/FileSystemFeatureTaskRuntimeSharedEvidenceStoreReads.kt:134`**: use model validators before construction, and keep the degraded `seam`, `used`, `expected` and the literal `cause` prefix (ground rule 3).

## Acceptance Criteria

1. No main source in `LegacyGoalRunnerControlMigration`, `ParallelCodeReviewRunnerFailureAdmission` or runtime-infra host, http, contracts, sqlite and workflow catches or `is`-checks `IllegalArgumentException` or `IllegalStateException`, except catches narrowed to a documented library subtype.
2. The worker heartbeat keeps reporting and rescheduling expected renewal failures.
3. Schema-validator, URI, rejected-output, goal-runner control and work-list messages, codes and degraded records are byte-identical, including the literal `"IllegalArgumentException: "` prefix in the shared-evidence degraded cause.

## Non-Goals

Telemetry config reads (subtask 4); runtime-infra/skills (subtasks 4 and 6).

## Test obligations

- A heartbeat recovery regression test, only if the heartbeat arm type changed. A heartbeat that throws the coded persistence failure is reported and rescheduled, and does not stop renewing. This guards worker liveness.

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

.feature-specs/SKILL-401-defect-exception-control-flow/spec_subtask_5_application-and-infra-adapters.md

## Implementation Details

This plan uses only the supplied preplan digest for checkout HEAD `5a17798c15c908c1d5a9c40ca025f0cb1bcb50d6`. Historical line numbers above are anchors, not current locations. No repository discovery, branch preparation, implementation, or command validation occurred in plan. The runtime owns branch preparation. This subtask has no dependencies and must remain independently implementable.

Paths below are relative to `runtime-kotlin/`. Production paths use `<module>/src/main/kotlin/skillbill/`; test paths use `<module>/src/test/kotlin/skillbill/`. Infrastructure owners are nested modules such as `runtime-infra/host` and `runtime-infra/sqlite`.

### Ordered tasks

1. Convert legacy and persisted goal-runner control decoding. Serves AC-001 and AC-003.

   Touch application `workflow/service/LegacyGoalRunnerControlMigration.kt` and infra SQLite `workflow/goalrunner/runner/LegacyGoalRunnerControlLedgerMigration.kt` and `GoalRunnerControlStoreDecodePolicies.kt`. Use `CodeReviewExecutionMode.fromWireOrNull` and `unknownWireValueMessage` for execution mode. Validate add-on entries and selection before construction through domain `agentaddon/model/AgentAddonModels.kt`: `PersistedAgentAddonSelectionEntry.violation(slug, sourceIdentity, contentSha256)` checks slug, content digest, then source identity; `AgentAddonSelection.violation(entries)` checks unique slugs. Add missing helpers in that owner and have constructor invariants use the same helpers. Retain helpers already introduced by another subtask. Do not wait for that subtask or recreate the already present mode parser.

   Keep each distinct legacy/control prefix, original reason placement, and `AgentAddonFailureCode.INVALID_SELECTION`. Remove the redundant IAE arm beside `SerializationException` in `GoalRunnerControlStoreDecodePolicies`. Preserve map keys, ordering, numeric compatibility, optional omissions, and code-checked propagation of unrelated runtime failures. Tests to run in validate are existing migration and control-decoder boundary coverage. The digest does not name those tests or establish their invalid-branch coverage. Implement must identify existing evidence before adding any test; the only eligible uncovered behavior is malformed mode, invalid entry, or duplicate selection being mapped to its unchanged owned code and text.

2. Remove defect interception from lane register admission. Serves AC-001 and the common defect-propagation criteria.

   Touch application `review/parallel/verification/ParallelCodeReviewRunnerFailureAdmission.kt`, specifically `parseLaneRegisterSeam`. Remove IAE and ISE arms; malformed register input must arrive as existing parser rejection values. Remove failure helpers or variants only if they lose their final production reader. Update the existing application `ParallelCodeReviewRegisterSeamTest.kt` case that injects `error(laneBody)` to assert the propagated ISE with the same message. Remove bounded failed-result assertions for that case because they assert the retired interception behavior. Do not add a new test for defect-only arm removal.

   Independent-execution assumption for implement to confirm: the domain structured-path conversion might not yet be present. If malformed input still throws from the parser, apply the digest's bounded nullable conversion in domain `review/parallel/ParallelReviewTrailingStructuredFields.kt` and `ParallelReviewFindingParser.kt`. Use `decodeParallelReviewStructuredStringOrNull`, nullable hexadecimal parsing, and `repositoryRelativePathViolation`; preserve `UNPARSEABLE_STRUCTURED_PATH` versus `NO_ADMISSIBLE_LOCATION`. Retain existing `ParallelReviewFindingParserTest.kt` coverage of short Unicode escapes and continued parsing. This is the required caller-chain repair, not execution of another subtask or a repo-wide parser sweep.

3. Preserve repo-local budget rejection and heartbeat recovery. Serves AC-001, AC-002, and AC-003.

   In host `FileSystemRepoLocalConfig.kt`, validate before constructing `ReviewContextBudgetPolicy`. Add or retain the ordered companion violation helper in domain `review/context/model/accounting/ReviewContextBudgetModels.kt`; positive limits, nonnegative assignment expansion, and cross-limit relationships keep their original order and messages. Constructor invariants delegate to that helper. Keep `malformedRepoLocalConfigError` arguments, including the original input's `value.toString()`.

   In host `JdkFeatureTaskRuntimeWorkerSupervisor.kt`, retain IOException recovery and replace IAE/ISE recovery with `SkillBillRuntimeException` handling guarded to the existing database-access and database-busy codes. Rethrow every unrelated code and defect. Both feature-task and goal heartbeat callbacks perform database transactions; SQLite `workflow/featuretask/FeatureTaskRuntimeWorkerStore.kt` already returns a boolean from lease renewal, and fencing loss already has `FeatureTaskRuntimeHeartbeatTick.FencingLost`. Do not invent another renewal outcome or move transaction ownership. Preserve reporting, rescheduling, consecutive-failure accounting, expiry escalation, cancellation, and interruption behavior.

   Test obligation for AC-002: a transient coded database renewal failure must report and retry rather than silently stop heartbeat scheduling; sustained coded failures must retain expiry escalation. Reuse `JdkFeatureTaskRuntimeWorkerSupervisorTest.kt` and its countdown-latch evidence. Replace the injected ISE in existing transient and sustained cases with the real coded persistence failure carrying the same message. No duplicate heartbeat tests are needed. The digest does not name the database-code constants or budget test file; implement must confirm the existing owner symbols and boundary coverage. Add a budget boundary test only if a newly exposed invalid rule has no existing decoder evidence, naming the concrete wrong acceptance or changed rejection it catches.

4. Narrow HTTP URI handling and remove constant-request defect handling. Serves AC-001 and AC-003.

   In HTTP `HttpRequestUri.kt`, replace `URI.create(url)` with `URI(url)` and catch only `URISyntaxException`. Keep the bounded cause message and all `telemetryProxyRequestFailure` fields. In `GitHubReleaseCatalogAdapter.kt`, remove its IAE arm because URL and headers are constants; retain IOException handling and interruption propagation. Validate through existing URI/proxy-request and release-catalog tests identified by implement. No new tests are planned for library narrowing or defect-only arm removal.

5. Keep contract-schema failure sets and conversion messages exact. Serves AC-001 and AC-003.

   In infra contracts `ClasspathContractSchemaLoader.kt`, remove broad IAE arms. Identity mismatch already throws owner failures directly; YAML reads and Jackson writes retain their existing IOException or JsonProcessingException handling. Do not add a `JsonSchemaException` catch: pinned networknt 1.5.6 declares it as a RuntimeException, and it already propagates. Fixed factory configuration failures remain defects.

   In `workflow/decomposition/DecompositionManifestSchemaValidator.kt`, replace the trailing-token `require` with the existing MALFORMED coded failure and exact reason `YAML is malformed: YAML contains trailing content or multiple documents.` Preserve duplicate-key handling and the MismatchedInputException arm. Replace `convertValue` here and in `DecompositionManifestBundleJournalSchemaValidator.kt` with a typed reader/tree conversion and JsonProcessingException handling. Preserve INVALID_SHAPE, the journal YAML-object code, and original error-message placement. Do not wrap already framed owner failures again or broaden handled codes.

   Validate with existing schema-loader, manifest, and bundle-journal boundary tests identified by implement. The digest does not supply their names or prove branch coverage. Reuse their malformed, duplicate-key, trailing-document, and non-object cases. Only an uncovered durable-schema rejection with a changed code or message warrants a new boundary test; no tests are planned for removing fixed-configuration defect arms.

6. Replace SQLite diagnostic and work-list exception parsing. Serves AC-001 and AC-003.

   In `SqliteRejectedOutputDiagnosticRepository.kt`, replace lifecycle `valueOf` with nullable lookup retaining current case normalization. Validate `RejectedOutputDiagnostic` before construction through a companion violation helper in ports `diagnostics/model/RejectedOutputDiagnosticModels.kt`; its only invariant is nonnegative `repairTurn`, and its constructor uses the same helper. Keep the CORRUPT code and `rejectedOutputDiagnosticCorruptMessage(identity)` without appending a reason. Model declarations and companion members fit the existing ports guard; add no exemption.

   In SQLite `worklist/SQLiteWorkListRepository.kt`, make `parseInstant(value, workflowId, column)` branch on `parsePersistedInstantOrNull`. Keep `invalid $column '$value'` and the existing owner failure. Preserve successful timestamp compatibility and durable record bytes. Validate through existing rejected-output repository and work-list tests identified by implement. Eligible uncovered test obligations are an unknown stored lifecycle or negative repair turn producing the unchanged corruption boundary, and an invalid stored work-list timestamp producing the unchanged rejection. Add none if existing tests already drive those branches.

7. Preserve shared-evidence degradation and prepare acceptance evidence. Serves AC-001 and AC-003, with AC-002 evidence retained from task 3.

   In workflow `featuretask/FileSystemFeatureTaskRuntimeSharedEvidenceStoreReads.kt`, validate all reached shared-evidence index models before construction. Helpers belong in domain `workflow/taskruntime/model/review/FeatureTaskRuntimeSharedEvidenceModels.kt`; constructor invariants share each ordered reason. Retain the projection-schema violation path and code-checked shared-decoder catches. Keep `stored_envelope_index`, `re-derive`, expected-value text, and the literal degraded cause prefix `IllegalArgumentException: `. Passing the reason explicitly must reproduce the emitted record without catching a defect.

   The digest does not enumerate the index model constraints or name their tests. Implement must preserve their current order and reuse existing shared-evidence store rejection/degradation coverage. A new test is justified only if no existing boundary test pins malformed stored index input to the exact degraded record, especially its literal cause prefix. Avoid one test per helper when one caller test covers the rule.

   Audit must inspect each criterion's end state, including the scope's remaining catches and type checks, validator caller chains, unchanged emitted text, exact handled code sets, heartbeat liveness evidence, and removal of dead failure helpers. Keep every named `ParseBoundarySite` in `runtime-core/src/repoTest/kotlin/skillbill/architecture/PrincipleEnforcementInventory.kt`; retain function names or update locations without dropping entries. A custom-throwable baseline row may shrink only after its class loses its last production reader. No baseline widening or suppressions are allowed.

### Constraints and phase handoff

Expected input rejection uses nullable parsing or an ordered violation; malformed durable input that ends execution keeps `SkillBillRuntimeException` and its existing owner code. True defects retain invariants and propagate. Preserve architecture rules A1, A2, A4, A7, A10, and A11 and G7. Domain helpers stay pure and have no ports or `java.nio` imports. Keep wire keys with their existing owners, schema versions, telemetry classification, and the protected CLI/MCP edge files unchanged. Do not add a Result/Either library, new throwable, typealias, runtime-exception property, phase-specific runner branch, or new port contract for these conversions.

Use small helpers within existing owners, obey detekt limits, keep files below 1,200 lines, and add no authored Kotlin line or non-KDoc block comments. In touched functions, replace relevant `runCatching` with direct or narrow handling; retained broad wrappers must rethrow cooperative cancellation and interruption. Do not move forbidden catches behind wrappers. Review and validate may repair production wiring, tests, formatting, or lint needed to satisfy these same end states.

Implement writes the repository changes and test updates without executing builds or tests. Audit and review inspect the changes. Build owns the pack build command. Validate owns all test execution and full gates, including the manifest-declared collect-all gate and its cache-bypassing counterpart, unit tests, detekt, runtime-core repoTest, and `scripts/validate_agent_configs`. Preserve `TypedParseBoundaryArchitectureTest`, `FailureCodeTotalityArchitectureTest`, `PortsDeclarationArchitectureTest`, `WireVocabularyArchitectureTest`, comment/KDoc, file-size, and module/package ownership guards. Spotless must run in a plain clone. Exact command argv comes from the owning phase's installed runtime and repository guidance, not an invented command here.

No command evidence is claimed by this plan; tests_executed remains empty. History, commit/push, PR, and runtime settlement stay with their owning phases. No migration, feature flag, public workflow command, install refresh, or skill-source change is needed. All missing checkout facts above are bounded implementation confirmations, not product questions or planning blockers. Preserve the title and all existing scope, criteria, dependency context, validation strategy, and next-path text.
