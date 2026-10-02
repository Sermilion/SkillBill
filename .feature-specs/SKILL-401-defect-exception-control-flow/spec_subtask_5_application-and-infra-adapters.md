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
