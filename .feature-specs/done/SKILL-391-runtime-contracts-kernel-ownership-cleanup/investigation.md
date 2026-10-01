# SKILL-391 runtime-contracts architecture investigation

## Judgment

runtime-contracts is a healthy shared kernel. It is a leaf module with one external edge (`api(libs.kotlinx.serialization.json)`). It has no DI, no `var`, one sanctioned clock value, and no typealiases. Its exact-package SCC is acyclic across `skillbill.contracts.*` and `skillbill.error.*`, and all four of its per-module guard baselines are empty. The SKILL-374 cleanup landed. Only one SKILL-374 instruction did not: moving the MCP-only output keys out of the kernel.

The remaining problems are small:
- one entry-point error class sits in the kernel;
- 28 runtime-operations errors that only runtime-engine reads were added to the kernel after the placement rule was written (F-007);
- two wire tokens are restated as string literals;
- one extension overload has no callers;
- six public declarations are read only inside their own file;
- six infra repoTests declare kernel packages.

The fixes remove or narrow code. They add no layer, module, guard class or baseline line. Wire output stays byte-identical.

## Method and baseline

- Baseline HEAD `ae23f4f28` ([SKILL-386] Runtime Cli Architecture), rechecked before writing.
- Every census was done personally with grep, cut, sort and uniq over the worktree. No code-review subagents were used, and nothing was compiled or tested.
- Context read:
  - AGENTS.md;
  - runtime-kotlin/ARCHITECTURE.md: placement rule L376-397, L596-616 and L940-946, and the EXACT_PACKAGE_SCC note L670-674;
  - runtime-contracts/agent/history.md (SKILL-349 entries only; the module has no decisions.md);
  - `../SKILL-374-runtime-contracts-shared-kernel/investigation.md`, read in full;
  - the untracked sibling bundles SKILL-389, SKILL-392, SKILL-393 and SKILL-395.

## Census

| Source set | Files | Lines |
| --- | ---: | ---: |
| main | 93 | 3,541 |
| test | 6 | 330 |
| testFixtures | 0 | 0 |
| repoTest | 0 | 0 |

Every test package exists in main. The six tests are:
- JsonCodecTest
- JvmSystemClockTest
- RuntimeProvenanceContractTest
- DecompositionPlanningContractsTest
- DatabaseAccessErrorTest
- ScaffoldPayloadParsingTest

Largest main files:

| File | Lines |
| --- | ---: |
| DecompositionPlanningContracts | 302 |
| FeatureTaskRuntimeShellContentErrors | 256 |
| JsonCodec | 209 |
| InstallShellContentErrors | 178 |
| OperationErrors | 161 |
| ReviewContracts | 140 |
| LearningContracts | 131 |

Files per package:
- error.core 12 (at the 12-file sibling limit)
- error.featuretask 10
- error.shellcontent 9
- review 7
- workflow/identity/task 7
- telemetry 5
- decomposition 5
- workflow/payload 4
- every other package 1-3

Build edges:

| Configuration | Modules |
| --- | --- |
| `api(runtime-contracts)` | application, ports, engine |
| `implementation(runtime-contracts)` | domain, core, cli, mcp and all seven infra modules |
| `testFixturesImplementation(runtime-contracts)` | sqlite |
| direct `implementation(kotlinx)` | sqlite, application, cli, mcp (each imports kotlinx itself, so justified) |

The redundant http and engine kotlinx edges are gone, so SKILL-374 F-006 landed.

Symbol consumers:
- 312 top-level symbols.
- Production modules per symbol: 1 = 175, 2 = 87, 3 = 25, 4 = 8, 5 = 5, 6 = 7, 7-9 = 1 each, 12 = 2.
- Most-imported symbols: JsonCodec (339 imports), SharedPayloadKeys (265), InvalidWorkflowStateSchemaError (98), ShellContentContractException (56).

Single-owner errors number 122, up from 84 at SKILL-374. By module:

| Module | Errors |
| --- | ---: |
| engine | 50 |
| infra/skills | 36 |
| domain | 12 |
| infra/contracts | 8 |
| infra/http | 4 |
| application, host, launcher, sqlite, mcp | 2 each |
| core, infra/workflow | 1 each |

Other measurements:
- Interfaces: two.
  - `JsonPayloadContract` is a typed carrier used by about 40 signatures and implemented by a testFixture.
  - `FailureWireCode` has 3 implementations in contracts and 1 in domain, and FailureCodeTotalityArchitectureTest enumerates them.
- `runCatching` wraps only pure JDK parsing (JsonCodec L133 and L141, DecompositionPlanningContracts L280-289), never suspending code.
- `Map<String, Any?>` appears in 11 files, 16 of them in DecompositionPlanningContracts. These are pinned by the raw-map guard and retained.
- Intra-module imports are acyclic: review→learning, review→telemetry, every package→root, contracts→error.core only from JsonCodec, and error.shellcontent→error.featuretask→error.core.

## Prior work (SKILL-374, baseline dbf9f4830): what landed

| Finding | Status | Evidence |
| --- | --- | --- |
| F-001 YAML loaders | Landed | Loaders absent; purity lock at RuntimeArchitectureTest:40-58 with ban lists at RuntimeArchitectureTestSupport.kt:827-844 |
| F-002 single-owner moves | Landed | Moved files present in launcher, sqlite, http, workflow, application, mcp, cli, engine and domain; `*SchemaPaths` objects live in infra/contracts (7), infra/skills (2) and mcp repoTest (1) |
| F-003 SQLite shadow keys | Landed | Files moved to sqlite; value trimming not re-verified |
| F-004 dead code | Landed | All listed names absent |
| F-005 error cycle | Landed | error packages acyclic |
| F-006 JsonCodec and kotlinx edges | Landed | |
| F-007 docs | Landed | |
| F-008 JsonPayloadContract | Retained at planning | |
| F-009 stutter and JsonSupportTest | Landed | |
| Subtask 2 instruction to move MCP-only output keys | **Not applied** | `contracts/mcp/McpToolPayloadKeys.kt` (69 lines) is still in the kernel; SKILL-395 subtask 1 now owns it |

## Principle assessment

| Checklist item | Verdict | Evidence |
| --- | --- | --- |
| 1. Dependency direction | Clean | Leaf module; only a kotlinx api edge. api consumers are the three modules whose public signatures expose kernel types. |
| 2. Inbound adapters | Not applicable | The kernel has no use cases. |
| 3. Outbound ports | Clean | No vendor protocol. The purity lock bans jackson, networknt, java.nio Files, org.yaml, java.io and getResourceAsStream. |
| 4. Domain richness | Clean | Pure wire DTOs and validation errors. DecompositionPlanningResult decoding is shared by 5 modules and retained. |
| 5. Composition | Clean | No @Inject, no bags, no locators. |
| 6. Entry-point leakage | Finding F-001 | `InvalidMcpToolArgumentError` and `McpToolPayloadKeys` name the MCP adapter. `LegacyProseWorkflowError` embeds CLI text but is user-facing wire text and stays. |
| 7. Ambient effects | Clean | Only `val JvmSystemClock = Clock.tickMillis(UTC)`, the sanctioned value; the ambient-clock baseline is empty. |
| 8. State | Clean | No var, no transactions. |
| 9. Error model | Minor | Typed taxonomy with no broad catch. F-003 covers a restated token default. JsonCodec catches SerializationException, a subclass of IllegalArgumentException, before IllegalArgumentException; that is harmless and stays. |
| 10. Cohesion | Findings F-001, F-005 and F-007 | Single-owner code is kept under the SKILL-374 retention, except entry-point vocabulary (F-001) and the engine-only runtime-operations errors added after the placement rule (F-007). |
| 11. YAGNI | Finding F-004 | Dead Array overload; the EnumEntries overload has 2 callers. |
| 12. Naming and packages | Finding F-006 | Six orphan repoTest packages. error.core is at its 12-file limit and drops to 11 after F-001. |
| 13. Guard validity | Valid | See the guard matrix below. |

## Guard matrix (opened and checked)

- **Purity lock**, RuntimeArchitectureTest:40-58.
  - It filters `relativePath` on `../../../runtime-kotlin/runtime-contracts/src/main/kotlin`.
  - It asserts the file set is non-empty (L45-48) and has a synthetic fixture (L60-98).
  - Its ban lists are at RuntimeArchitectureTestSupport.kt:827-844.
- **No SchemaPaths in the kernel**, RuntimeArchitectureTest:206-210. It resolves the module directory through RuntimeModuleCatalog.
- **Per-module scan case**, PrincipleEnforcementInventory:26-50.
  - Scan root: `../../../runtime-kotlin/runtime-contracts/src/main/kotlin`.
  - Package prefix: `skillbill.`, the L60 fallback, because there is no moduleMainPackageRoots entry. The prefix therefore covers both kernel roots.
  - EXACT_PACKAGE_SCC applies at L41-42.
  - The four baselines `runtime-contracts-{package-cycle,ambient-clock,ambient-environment,inject-constructor-defaults}-baseline.txt` are empty.
  - ApplicationPackageAcyclicityArchitectureTest:43 has the synthetic kernel cycle fixture.
- **Package ownership**, RuntimeArchitectureTest:426-440 over `declaredSubsystemPackages` (RuntimeModuleCatalog:199). It scans main sources only, which is why it never saw the F-006 repoTest orphans. No scanner extension is proposed: the criterion can be read off the tree, and YAGNI applies.
- **Wire vocabulary**, WireVocabularyArchitectureSupport. Its object regex ignores visibility, so F-005 is compatible with it.
- **Failure-code totality**, FailureCodeTotalityArchitectureTest:52-55. It enumerates FailureWireCode entries, including the domain enum, and is unaffected.
- **testFixtures map**, RuntimeModuleCatalog:175. runtime-contracts maps to an empty set.

## Findings

### F-001 (P2). MCP entry-point error lives in the shared kernel

- **Evidence:**
  - `runtime-contracts/src/main/kotlin/skillbill/error/core/InvalidMcpToolArgumentError.kt` declares a class whose name and message template name the MCP tool surface.
  - Its only references are runtime-mcp main: `mcp/core/McpToolDispatcher.kt` L7, L49 and L55, and `mcp/shared/McpToolArguments.kt` L4 and L83. No test names it.
  - The dispatcher catches it through its base, `ShellContentContractException` (McpToolDispatcher:25).
  - After SKILL-395 moves `McpToolPayloadKeys`, this is the only MCP-named declaration left in a kernel that engine and application compile against.
- **Supersedes:** the part of the SKILL-374 retention that kept all single-module errors in the kernel, for this one class. The new evidence is that its vocabulary is entry-point (checklist item 6) rather than domain failure vocabulary. F-007 narrows the same retention for the runtime-operations leaves; the other 93 single-owner errors stay.
- **Fix:** declare the class in runtime-mcp main, package `skillbill.mcp.shared`, as `internal`. Keep the same name, constructor parameters, message template and base class. Delete the kernel file; error.core drops from 12 to 11 files.
- **Precedent:** adapter-local errors already extend kernel bases: infra/host GateJvmResolutionErrors, launcher CursorReviewStreamErrors and sqlite InvalidGoalTelemetryRowError.
- **Feasibility:** the class imports only `ShellContentContractException`, and runtime-mcp already has `implementation(runtime-contracts)`. No guard or doc names the class.

### F-002 (P2, handover). MCP-only output keys still in the kernel

`McpToolPayloadKeys` (contracts/mcp) has one read outside runtime-mcp: sqlite ReviewRowMappers.kt:34 reads `REVIEW_SESSION_ID`, whose value ReviewFinishedTelemetryPayloadKeys also declares. SKILL-395 subtask 1 owns the move and the sqlite switch. This bundle does not touch it.

### F-003 (P3). Two wire tokens restated as literals

- (a) runtime-mcp `mcp/review/McpAdapterContracts.kt:42` writes `LearningPayloadKeys.APPLIED_LEARNINGS to \"none\"`. That restates `NO_APPLIED_LEARNINGS`, which `summarizeAppliedLearnings` uses for the same field (LearningContracts.kt:125-128).
  - Fix: reference `NO_APPLIED_LEARNINGS`. It therefore stays public: it is a shared wire value.
- (b) `FeatureTaskRuntimeShellContentErrors.kt:29` defaults `failureCode` to the literal `schema_invalid`. The same class then decodes that value through `coarseFailureKindForPhaseOutputWireCode` (L36).
  - Fix: default to `FeatureTaskRuntimePhaseOutputFailureCode.SCHEMA_INVALID.wireValue`. error.shellcontent already imports error.featuretask in this file.
  - L197 (`InvalidFeatureTaskRuntimeBuildReceiptSchemaError`) stays: no enum owns build-receipt codes.

### F-004 (P3). Dead overload

`error/core/FailureWireCodeContract.kt:16-21` declares `Array<E>.failureWireByValue`, which has zero callers. The only callers use `entries`: error.featuretask FeatureTaskRuntimePhaseOutputFailureCode.kt:46 and domain DecompositionManifestValidationModels.kt:43.

Fix: delete it and keep the EnumEntries overload.

### F-005 (P3). Public declarations with no consumer outside the module

These are referenced only in their own file:
- `LearningSummaryWire` (LearningContracts.kt:30)
- `ValidationReportPayloadKeys` (ValidationReportContracts.kt:6)
- `REVIEW_HUNK_EVIDENCE_LOCATOR_MISSING`, `REVIEW_HUNK_EVIDENCE_LOCATOR_UNREADABLE`, `REVIEW_LEARNING_RULE_TEXT_TOO_LONG` and `REVIEW_LEARNING_TITLE_TOO_LONG` (ReviewContextShellContentErrors.kt:34, 36, 40, 51)

Fix: make them `private`. Values stay unchanged. Tests in other modules assert the literal messages, not the constants.

Not narrowed: `UpdateCheckPayloadKeys` (read in SystemContracts.kt) and `WorkflowContinueSessionSummaryPayloadKeys` (read in WorkflowContinueSessionSummary.kt). Today only other runtime-contracts files read them, but SKILL-395 subtask 1 AC 4 adds a `WireVocabularyArchitectureTest` method in runtime-core that reflects over both objects. As `internal` objects they would not compile there, so they stay public.

These stay public:
- `REVIEW_HUNK_EVIDENCE_INTEGRITY`, because a runtime-application test reads it;
- `NO_APPLIED_LEARNINGS`, per F-003;
- `UnrecognizedFailureWireCodeError` and `coarseFailureKindForPhaseOutputWireCode`, because domain tests read them.

### F-006 (P3). Orphan kernel packages in infra repoTests

Six files under `runtime-infra/contracts/src/repoTest/kotlin/skillbill/contracts/` declare `skillbill.contracts.review`:
- ReviewWirePayloadKeysYamlParityTest
- ReviewContextSchemaContractVersionTest

and `skillbill.contracts.workflow.featuretask`:
- FeatureTaskRuntimeCheckpointIdentitySchemaContractVersionTest
- FeatureTaskRuntimeExecutionPlanSchemaContractVersionTest
- FeatureTaskRuntimeVerifyFindingsDispositionSchemaRepoTest
- FeatureTaskRuntimeProjectionCanonicalizationSchemaRepoTest

ARCHITECTURE.md:599 says `skillbill.contracts.*` compiles only in runtime-contracts. The tests import jackson, snakeyaml, java.nio and infra locators, so they cannot move into runtime-contracts.

Fix: repackage them in place to the existing main packages `skillbill.infrastructure.contracts.review` and `skillbill.infrastructure.contracts.workflow.featuretask`, with matching directories. Add explicit imports for the kernel constants they reached through same-package access. runtime-kotlin/agent/decisions.md:1102 names one test by class name only; names stay.

### F-007 (P2). Runtime-operations errors with one owner sit in the kernel

- **Evidence:**
  - `runtime-contracts/src/main/kotlin/skillbill/error/operation/OperationErrors.kt` (161 lines) declares 29 classes. It appeared with the runtime operations work (SKILL-382), after SKILL-374 wrote the placement rule into AGENTS.md.
  - `OperationUsageError` is the only one read outside runtime-engine: runtime-cli `cli/operation/OperationCommand.kt:25` catches it as a base type.
  - The other 28, including the `OperationRefusalError` base and `DuplicateOperationIdError`, are read only by runtime-engine. That is 12 main files under `skillbill.engine.operation.{core,featureguard,featureguardcleanup,prreviewfix,release,unittestvalue,verify}` and 6 test files under the same packages. No other module's main or test source, no doc and no architecture guard names them.
  - Every class imports only `ShellContentContractException` or the two operation bases.
- **Why the SKILL-374 retention does not cover them:** that retention keeps errors in the kernel because CLI and MCP classify failures by kernel base types. Here only the base the CLI catches needs to be shared. The leaves are engine vocabulary, so every message edit recompiles the 14 modules that depend on the kernel. Single-owner errors grew from 84 to 122 between SKILL-374 and this baseline, and this package is the largest cohesive block of that growth.
- **Fix:**
  - Keep `OperationUsageError` in `skillbill.error.operation`, unchanged.
  - Move the other 28 classes into runtime-engine main as one file in `skillbill.engine.operation.core`, declared `internal`. Names, constructor parameters, base classes and message templates stay the same.
  - Engine main and test files change only their imports.
- **Feasibility:**
  - `engine/operation/core` holds 7 files and goes to 8, under the 12-file sibling limit.
  - `operation.core` imports none of its sibling subpackages, so the move adds no cycle.
  - Engine depends on runtime-contracts through `api`, so the leaves can keep extending `OperationUsageError` and `ShellContentContractException`.
  - Engine tests are in the same module, so `internal` is visible to them.
  - CLI output is unchanged: the CLI catches the base type and prints the message, and the messages do not change.
- **Not moved:** the other 93 single-owner errors. They are older, spread across eight modules, and are mostly schema-validation vocabulary that SKILL-374 deliberately kept. Moving them is churn with no boundary gain beyond F-001 and F-007.

## Observations handed to owners (no work here)

- **SKILL-396 (infra):** runtime-infra/contracts DecompositionManifestSchemaValidator.kt L72, 103, 131, 138 and 153, and DecompositionManifestCoherenceValidator.kt:172, restate `DecompositionManifestValidationFailureCode` tokens as literals. infra/contracts can already import the domain enum.
- **SKILL-397 (domain):**
  - domain DecompositionManifestWireCodec.kt:240 restates `invalid_shape`.
  - DecompositionManifestValidationModels.kt:7 imports `SHA256_HEX`, which the private companion regex at :90 shadows, so the import is unused and the rule is duplicated.
- **SKILL-388 (application):** ReviewContractMappers.kt:146 forwards to the ports mapper; SKILL-388 already records this.
- **SKILL-392 (cli):** ARCHITECTURE.md:670-674 names only runtime-domain for EXACT_PACKAGE_SCC, while PrincipleEnforcementInventory:41 also applies it to runtime-contracts. SKILL-392 already updates that sentence when it adds runtime-cli. Whichever lands second keeps both modules named.

## Over-engineering register

- Removed: the dead `Array<E>.failureWireByValue` overload (F-004).
- Narrowed: six declarations read only inside their own file (F-005).
- Relocated to their single owner: 28 runtime-operations errors (F-007) and one MCP error (F-001). This shrinks the kernel surface that 14 modules recompile against.
- Rejected additions:
  - a new guard for test-package ownership (F-006 can be read off the tree);
  - a consumer-count guard;
  - an enum type for `failureCode: String?` fields;
  - splitting the kernel by area.

## What stays unchanged

| Item | Reason |
| --- | --- |
| Module graph and api edges | api consumers expose kernel types in their signatures. |
| `*_CONTRACT_VERSION` and `*_SCHEMA_ID` constants | Exempt from the placement rule. |
| Error kernel structure, class names and the error.shellcontent name | Acyclic; SKILL-374 retention. |
| 93 remaining single-owner errors | SKILL-374 retention. F-001 and F-007 take 29 of the 122 out. The rest are older failure vocabulary spread across eight modules; moving them is churn with no boundary gain. |
| `OperationUsageError` in `skillbill.error.operation` | runtime-cli catches it as a base type. |
| DatabaseAccessError, telemetry HTTP errors, JsonCodec | SKILL-374 retention. |
| `Map<String, Any?>` plus `*Keys` | Raw-map guard pins toPayload DTOs in contracts. |
| `JsonPayloadContract` | Carrier type in about 40 signatures plus a testFixture implementation. |
| `FailureWireCode` interface and EnumEntries overload | 4 implementations and 2 callers. |
| DecompositionPlanningResult and its decoder | 5 modules. |
| ScaffoldPayloadParsing, issue-key functions, issue-key-schema.yaml | SKILL-374 retention. |
| JvmSystemClock | Sanctioned clock, bound once. |
| `GOAL_PLANNING_WAVE_CAP` | Read by ports, engine and sqlite; an IDE-status wire bound. |
| `LegacyProseWorkflowError` CLI text | User-facing wire text; byte identity. |
| `isDecomposeMode()` string compare | SKILL-374 rejected enum-typing mode. |
| `DecompositionPlanningContracts:301` literal `invalid_shape` | The kernel cannot import the domain enum; moving the enum ripples. |
| `DecompositionManifestValidationFailureCode` stays in domain | Domain is a valid shared owner. The kernel error carries a String; moving the enum has no payoff. |
| JsonCodec redundant SerializationException catch | Harmless and documents the intended exception. |
| `workflow/identity/*` one-file packages | Renames are churn with no boundary gain. |
| Rejected earlier: split per area, consumer-count guard, DI caps, explicitApi | SKILL-374 retention. |

## Coordination with concurrent bundles

| Bundle | Overlap | Owner | Sequencing |
| --- | --- | --- | --- |
| SKILL-395 runtime-mcp | Owns `McpToolPayloadKeys` (F-002). Also edits McpToolArguments.kt (`toolName` private), McpToolDispatcher.kt and McpAdapterContracts.kt (DTOs internal, `findingCount` typed), the same files F-001 and F-003(a) touch. Both add files to `skillbill.mcp.shared` (3 files today, 5 after both). | 395 owns the keys; 391 owns the error and the `none` reference. 395's new WireVocabularyArchitectureTest method reflects over `UpdateCheckPayloadKeys` and `WorkflowContinueSessionSummaryPayloadKeys`, so F-005 keeps both public. | Either order. Whichever lands second applies the other's change to the files present: import the error from `skillbill.mcp.shared` and keep the `NO_APPLIED_LEARNINGS` reference in whatever DTO shape is present. |
| SKILL-389 runtime-core | Edits the architecture suite. This bundle edits no guard. | 389 | Independent. |
| SKILL-392 runtime-cli | ARCHITECTURE.md EXACT_PACKAGE_SCC sentence. | 392 | Whichever lands second names runtime-domain, runtime-contracts and runtime-cli. |
| SKILL-393 runtime-ports | No shared file. Its peer note had no item for contracts. | 393 | Independent. |
| SKILL-388 runtime-application | Wire-vocabulary inventory seams. F-005 visibility is compatible with the visibility-agnostic regex. | 388 | Independent. |
| SKILL-390 runtime-engine | F-007 adds one file to `engine/operation/core` and edits imports in 12 engine main and 6 engine test files under `skillbill.engine.operation.*`. 390 subtask 3 repackages three engine test files that declare the bare package `skillbill.engine.operation`. None of those three imports an operation error, and 390 edits no file that F-007 edits. 390 also leaves `normalizeIssueKey` naming to the contracts owner; this bundle keeps it (SKILL-374 retention). | 391 owns the operation-error relocation; 390 owns engine test packaging. | Either order. If 390 lands first and moves an engine test that imports an operation error, the import points at `skillbill.engine.operation.core`. |
| SKILL-396 runtime-infra | Depends on `GOAL_PLANNING_WAVE_CAP` staying in the kernel, which this bundle keeps. 396 does not edit the six infra/contracts repoTests that F-006 repackages. | 391 owns F-006. | Either order. If 396 later moves those tests, whichever lands second applies the package rule of F-006 to the files present. |
| SKILL-397 runtime-domain | No shared file. It receives the domain handover observations above. | 397 | Independent. |
| SKILL-387, SKILL-388 | Launched goals, both blocked at audit on 2026-10-01. 387 deletes `InvalidFeatureTaskRuntimePlanningProjectionSchemaError` from `FeatureTaskRuntimeShellContentErrors.kt`; F-003(b) edits a different class in that file, so the two merge cleanly. | Their owners. | Preferred first, but this bundle does not wait for them: if either is still unlanded, implement on the current tree and the second lander rebases. |

Global order (preferred, not required by this bundle): SKILL-387 and SKILL-388 first; then SKILL-389, SKILL-390, SKILL-392, SKILL-393, SKILL-395, SKILL-396, SKILL-397 and SKILL-391 in any order, under the second-lander rules above. Checked against every sibling bundle on disk on 2026-10-01: 387, 388, 389, 390, 392, 393, 395, 396 and 397.

## Limits (what only compiling can confirm)

- The repackaged repoTests compile once explicit imports replace same-package access to kernel constants.
- `private LearningSummaryWire` is not exposed by any public signature in LearningContracts.kt; it appears only in function bodies and its own companion.
- The runtime-mcp dispatcher compiles against the relocated error.
- Every engine main and test reference to the 28 relocated operation errors resolves after the import change, and no module outside runtime-engine references them through reflection or a qualified name the census missed.
- Not re-verified: SKILL-374 F-003 SQLite key value trimming.
- There is no DI-generated code in this module (no @Inject), so no generated readers are unaccounted for.
