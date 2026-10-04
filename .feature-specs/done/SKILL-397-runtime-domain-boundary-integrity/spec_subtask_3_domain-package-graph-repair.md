# SKILL-397 Subtask 3 - domain-package-graph-repair

Parent spec: [.feature-specs/SKILL-397-runtime-domain-boundary-integrity/spec.md](spec.md)
Issue key: SKILL-397

## Scope

Mechanical package repair. (F-001) Empty runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/baselines/runtime-domain-package-cycle-baseline.txt by breaking each cycle at its root. Move the 40 internal *_ARTIFACT_KEY constants into skillbill.workflow.engine.model, keeping them internal, so DurableWorkflowArtifactFamily imports no key from another package. Move selectedPlatformSlugs from install.policy into install.model. Move FeatureTaskRuntimeRequiredArtifactPresenceResolver from taskruntime.artifact and UpstreamPlanningProjectionSpec from taskruntime.phase.planning into taskruntime.phase.task. Keep one SHA-256 hex helper per input type (ByteArray, UTF-8 String) in skillbill.text, and delete the internal copy in taskruntime.model.repair and the private copy in ReviewLaneBundleAssembly. Resolve any remaining cycle edge the EXACT_PACKAGE_SCC scanner reports by moving declarations between domain packages within the sibling ceilings. (F-003) Flatten taskruntime.model.persistence.task.runtime.{checkpoint, goal, implementation, prior, run, store} into taskruntime.model.persistence. Fold handoff.envelope into handoff and repair.task into repair. (F-002) Delete the skillbill.goalrunner and skillbill.workflow.model.goalreview ceiling branches in ArchitectureScanSupport.kt (both the productionPackageSiblingCounts and packageSiblingCountViolationMessage copies), and split the two packages to at most 12 files each. Suggested: the AttemptLedger trio goes to goalrunner.ledger; the goalreview repair ledger and receipt files go to taskruntime.model.repair; the goalreview observability files go to workflow.model.goalobservability. (F-009) Refresh the ARCHITECTURE.md Package Ownership list for runtime-domain so it contains only packages that exist. Move the domain test package skillbill.workflow.goal beside its subject. Importers in other modules are rewritten to match; no alias is added.

## Acceptance Criteria

1. baselines/runtime-domain-package-cycle-baseline.txt records no SCC row.
2. All *_ARTIFACT_KEY constants of runtime-domain are declared internal in skillbill.workflow.engine.model, and DurableWorkflowArtifactFamily.kt imports no *_ARTIFACT_KEY from another package.
3. selectedPlatformSlugs is declared in skillbill.install.model. FeatureTaskRuntimeRequiredArtifactPresenceResolver and UpstreamPlanningProjectionSpec are declared in skillbill.workflow.taskruntime.phase.task. No package skillbill.workflow.taskruntime.phase.planning exists.
4. runtime-domain main declares SHA-256 hex hashing only in skillbill.text, and neither CorrectiveRepairRendering.kt nor ReviewLaneBundleAssembly.kt declares a sha256 function.
5. No runtime-domain package name contains persistence.task.runtime, handoff.envelope or repair.task.
6. ArchitectureScanSupport.kt contains no package-name-specific ceiling. skillbill.goalrunner and skillbill.workflow.model.goalreview each hold at most 12 main files, and packageSiblingCountRemainderInventory stays empty.
7. The runtime-domain rows of the ARCHITECTURE.md Package Ownership section name only packages that exist in runtime-domain main, and every runtime-domain test package matches a main package.
8. No typealias, baseline row or architecture exemption is added.

## Non-Goals

- Behaviour changes of any kind.
- Merging or splitting packages beyond what the cycle and sibling rules require.
- Resolving the skillbill.model split package shared with runtime-ports.
- Narrowing top-level visibility (SKILL-372 F-013).

## Dependency Notes

Depends on: 1, 2
Runs after subtasks 1 and 2, so the cycle and sibling scans judge the final file set, including the transitions added to skillbill.workflow.decomposition. Importer rewrites in engine, application, infra, mcp and cli follow the rule that whichever lands second rewrites the files present, with no alias.

## Validation Strategy

Goal gates: build, unit tests and the repoTest architecture suite, with the EXACT_PACKAGE_SCC scan passing against an empty runtime-domain baseline and the sibling scan passing without package-specific ceilings. No new test obligations; this is a move-only change.

## Implementation Details

All paths below are relative to `../../../runtime-kotlin`. Domain main is `runtime-domain/src/main/kotlin/skillbill/`. The census was taken on `c038e02e5`, before subtasks 1 and 2. This subtask runs on the same branch after both of them, so recount file counts and import edges when you start.

**Scope.** This plan covers only this subtask's eight criteria. The preplan digest assumed one run would deliver all three subtasks. It does not: subtasks 1 and 2 are separate children, and this plan neither performs nor repeats their work. If a dependency's change is missing when this subtask starts, apply this subtask's rule to the files that exist and do not implement the missing work here.

**Rechecked on `c038e02e5` during planning:**
- the six baseline rows;
- 40 distinct `*_ARTIFACT_KEY` constants;
- `goalrunner` at 15 files and `goalreview` at 18;
- the ceiling branches at `ArchitectureScanSupport.kt:473-474,505-506`;
- the named model-package list at `:855-861`;
- no main file in a model package imports `skillbill.text` (only `goalrunner.subtaskreview`, a non-model package, does);
- no other goalreview file refers to the goalobservability family;
- no `review` or `workflow.model.persistence` file imports goalreview.

### How the scanners decide

- **Cycle scan.** `ArchitectureScanSupport.packageImportEdges` with `EXACT_PACKAGE_SCC` builds edges only from column-0 `import` lines. Each import resolves to the longest declared domain package. Same-package references create no edge until a move turns them into imports.
- **Model-package rule.** `modelPackageImportViolations` (`ArchitectureScanSupport.kt:832-868`, run by `ApplicationPackageAcyclicityArchitectureTest`) checks every package with a `model` segment. Such a package may import only:
  - model packages;
  - the named list at `:855-861`.
- **Data-only rule.** `PackageSiblingCountArchitectureTest` "task-runtime ... models stay data-only" stops `skillbill.workflow.taskruntime.model.*` from importing any non-model `skillbill.workflow.taskruntime.*` package.
- **Test placement.** "runtime test sources remain co-located" requires each test file's directory to equal its package.

### Where the spec's suggestions do not work on the current tree

1. **`skillbill.text` is not importable from model packages.** It is a non-model package, and the review-context and task-runtime model packages cannot import it under today's rule. Adding `skillbill.text` to the named list would be an exemption, which AC-008 forbids. Task 5 refines the rule instead.
2. **The goalreview repair receipt and ledger cannot move to `taskruntime.model.repair`.** They and the rest of goalreview depend on each other:
   - `FeatureTaskRuntimeRepairReceipt.kt` declares `GoalSubtaskReviewState.upsertRepairReceipt`. It also uses `GoalSubtaskReviewCompactFinding`, `normalizeIdentityPart`, `canonicalizeFindingRef`, `requireFindingRefAlias` and `withStableFindingRefs`.
   - `FeatureTaskRuntimeRepairLedger.kt` folds receipts with `GoalSubtaskReviewPassResult`.
   - `GoalSubtaskReviewState` holds the receipts.

   Moving the two files would create a new repair↔goalreview SCC. Task 8 moves other whole families instead.
3. **The AttemptLedger trio needs one extra change to move.** `AttemptLedgerAccumulator.parseInstantOrNull` calls `recordDurableDecodeSubstitution`, which lives in `goalrunner`. Meanwhile `goalrunner/DurableWorkflowArtifactsGoalProgressAccessors.kt` calls ledger functions, so moving the trio as-is would form a cycle. Task 9 deletes that one-line forwarder.
4. **Rows 3 and 6 of the baseline need more than the named fixes.** No fix named in the spec breaks them. Tasks 6 and 7 move individual declarations to get a strict layering.
5. **`handoff.envelope` no longer exists.** SKILL-387 removed it, so that part of AC-005 only needs a check.

### Ordered tasks

Break every edge (tasks 1-9) before flattening (task 10). If you flatten first, `persistence.task.runtime.goal` (row 1) and `.run` (row 6) merge into one larger SCC.

#### 1. Artifact keys move into `skillbill.workflow.engine.model` (AC-002, AC-001 row 1)

- Add `workflow/engine/model/DurableWorkflowArtifactKeys.kt` holding all 40 `internal const val *_ARTIFACT_KEY` declarations. Copy each value verbatim and group them by family. `engine.model` goes from 5 to 6 files; its ceiling is 20.
- Delete each key from its current file:

  | File | Keys removed |
  |---|---|
  | `goalrunner/GoalRunnerWorkflowStoreConstants.kt` | 3 (the file keeps `STALENESS_EVIDENCE_WINDOW`) |
  | `goalrunner/GoalWorkerSubtaskRequestArtifactCodec.kt` | 1 |
  | `goalrunner/model/GoalRunnerAccountingModels.kt` | 1 |
  | `workflow/model/goalreview/GoalSubtaskReviewConstantsAndDispositions.kt` | 4 |
  | `workflow/model/goalreview/GoalObservabilityModels.kt` | 4 |
  | `taskruntime/model/core/FeatureTaskRuntimeDecomposeTerminal.kt` | 1 |
  | `taskruntime/model/audit/FeatureTaskRuntimeDiagnosticSignalModels.kt` | 1 |
  | `taskruntime/model/audit/FeatureTaskRuntimeQuarantineModels.kt` | 1 |
  | `persistence/task/runtime/checkpoint/FeatureTaskRuntimeCheckpointIdentityModels.kt` | 1 |
  | `persistence/task/runtime/run/FeatureTaskRuntimeRunInvariantsPersistence.kt` | 1 |
  | `persistence/task/runtime/store/FeatureTaskRuntimeExecutionPlanArtifactKey.kt` | 1 (delete the file) |
  | `persistence/task/runtime/store/FeatureTaskRuntimePersistenceArtifactKeys.kt` | 15 (the non-key constants stay) |
  | `taskruntime/model/validation/FeatureTaskRuntimeValidationGateProgressModels.kt` | 2 |
  | `taskruntime/model/validation/FeatureTaskRuntimeReadinessEvidence.kt` | 1 |
  | `workflow/engine/WorkflowInputProjectionSelector.kt` | 1 (`RUNTIME_REPOSITORY_EVIDENCE_ARTIFACT_KEY`) |
  | `workflow/decomposition/runtime/DecompositionRuntimeArtifactKeys.kt` | 2 (delete the file) |

- Remove all 40 key imports from `DurableWorkflowArtifactFamily.kt`.
- Rewrite every domain main and test import of a key to `skillbill.workflow.engine.model.<KEY>`. The keys are internal, so only runtime-domain refers to them.
- This removes the edges `engine.model` → {`goalrunner`, `goalrunner.model`, `decomposition.runtime`, `workflow.engine`, `goalreview`, `audit`, `core`, `persistence.*`, `validation`}. It also removes `decomposition.runtime` → `goalrunner`.

#### 2. `selectedPlatformSlugs` moves into `skillbill.install.model` (AC-003, row 2)

- Add `install/model/PlatformPackSelectionResolution.kt`. Move into it, verbatim, both `selectedPlatformSlugs` overloads and `PACK_SIDECAR_PARENT_SKILL` from `install/policy/InstallPlanPolicyResolution.kt:45-105`. The first overload reads that constant, so leaving it behind would keep the `install.model` → `install.policy` edge.
- Delete the two unused imports of `skillbill.install.policy.selectedPlatformSlugs`:
  - `install/model/InstallPlanWireMap.kt:6`;
  - `install/model/InstallPolicyModels.kt:3`.

  Both files refer only to the `selectedPlatformSlugs` property.
- `install/policy/InstallPlanPolicy.kt` imports the function from `install.model`.
- Rewrite `skillbill.install.policy.PACK_SIDECAR_PARENT_SKILL` and `skillbill.install.policy.selectedPlatformSlugs` to `skillbill.install.model.*` in every module. That is about 30 files:
  - `runtime-application/.../install/InstallService.kt:17-18`;
  - infra-skills main, test, testFixtures and repoTest;
  - the infra-workflow test;
  - the application and cli tests;
  - the domain test `install/policy/InstallPlanPolicyTest.kt`.

#### 3. The resolver and `UpstreamPlanningProjectionSpec` move into `skillbill.workflow.taskruntime.phase.task` (AC-003, row 5)

- Move both files, changing the package line and the directory, then delete the `phase/planning/` directory:
  - `workflow/taskruntime/artifact/FeatureTaskRuntimeRequiredArtifactPresenceResolver.kt`;
  - `workflow/taskruntime/phase/planning/UpstreamPlanningProjectionSpec.kt`.
- `phase.task` goes from 6 to 8 files.
- The resolver may refer to declarations in the artifact package. The resulting `phase.task` → `artifact` import is allowed. Check that `artifact` no longer imports `phase.task`.
- Rewrite importers in engine and every other module.

#### 4. SHA-256 hashing lives only in `skillbill.text` (AC-004)

- Keep `sha256HexUtf8` in `text/Utf8Text.kt` as the only helper. Add no `ByteArray` overload: once `CorrectiveRepairRendering.sha256Hex` (which has no callers) is deleted, nothing needs one.
- Delete these and point their callers at `skillbill.text.sha256HexUtf8`:
  - `sha256Hex` and the `MessageDigest` import in `workflow/taskruntime/model/repair/CorrectiveRepairRendering.kt:47-48`;
  - the private `sha256Hex` in `review/context/model/packet/ReviewLaneBundleAssembly.kt:323`;
  - `sha256` in `review/context/model/execution/ReviewContextCanonical.kt:42`, which callers in bundle, commit, hunk and packet use.
- Route the three inline digests through `sha256HexUtf8`. Each produces the same bytes as before:
  - `taskruntime/model/skeleton/PhaseStepPolicy.kt:35-38`;
  - `featureTaskRuntimeOwnedPathDigest` in the persistence checkpoint file (`framed.toByteArray()` is already UTF-8);
  - `FeatureTaskRuntimePlanningProjectionModels.fileHunkIndexDigest`: hash the entries joined with each one followed by `'\u0000'`, which gives the same byte stream as the current `update(entry) + update(0)` loop.
- Afterwards, `MessageDigest` appears in runtime-domain main only under `skillbill/text`.

#### 5. The model-package rule admits leaf kernel packages (enables AC-004 and keeps AC-008)

- In `modelPackageImportViolations`, a non-model domain package becomes importable when it is a leaf: none of its files imports another declared domain package under the prefix.
- Delete the named entry `skillbill.workflow.time`, which is a leaf.
- Once tasks 1-10 are done, also delete any other named entry that no model package imports any more. Check each with grep. Likely candidates, because their only model-package importers were the keys and `selectedPlatformSlugs`:
  - `skillbill.goalrunner`;
  - `skillbill.workflow.engine`;
  - `skillbill.workflow.decomposition.runtime`;
  - `skillbill.install.policy`.
- Add no names.
- Add one synthetic test to `ApplicationPackageAcyclicityArchitectureTest`. It builds a temporary source tree and passes its absolute path as `scanRoot`, then checks two cases:
  - a model package importing a leaf non-model package produces no violation;
  - a model package importing a non-model package that itself imports another domain package produces one violation.

  It catches leaf detection that treats every package as a leaf, which would silently switch the rule off.
- Describe the rule in the ARCHITECTURE.md Package Ownership paragraph on package structure (about line 583).
- Rejected alternatives:
  - a named `skillbill.text` entry, because it is an exemption;
  - calls by fully qualified name without an import, or forwarders through an already-exempt package, because both dodge the import scanners.

#### 6. Layering for the review-context SCC (row 3)

Target DAG, lowest first: `text` ← `hunk` ← `bundle` ← `commit` ← `packet` ← `accounting` ← `execution` ← `launch`.

- Move into `review.context.model.hunk`:
  - `execution/ReviewContextCanonical.kt`, without `sha256`. It carries `canonicalFields`, `canonicalFieldList`, `structuredString`, `SHA256_HEX` and `requireRepositoryRelativePath`.
  - `packet/ReviewExpansionRecord.kt`. It depends only on the canonical helpers and `ReviewEvidenceLimits`.
- Move `execution/ReviewLaneDecision.kt` into `commit`. It is a leaf.
- Move into `accounting`:
  - `hunk/ReviewContextBudgetModels.kt`;
  - the `ReviewIntegrationTerminalOutcome` enum, together with its companion, from `launch/ReviewIntegrationPass.kt`, into `accounting/ReviewAccountingTypes.kt`.
- Move `launch/CodeReviewExecutionMode.kt` into `execution`.
- Resulting file counts: hunk 6, bundle 1, commit 4, packet 5, accounting 3 (5 after task 8), execution 6, launch 5.
- Rewrite importers symbol by symbol in every module (`CodeReviewExecutionMode` has many importers). A package-prefix rewrite is wrong here because the packages split.

#### 7. Layering for the task-runtime handoff SCC (row 6) and the repair back edges (row 4)

- **Prompt fields.** Move `persistence/task/runtime/run/FeatureTaskRuntimeRunInvariantPromptFields.kt` into `taskruntime/model/handoff/task/`.
- **Split `handoff/task/FeatureTaskRuntimeHandoffModels.kt`.**
  - These stay: `FeatureTaskRuntimeRunInvariants`, `FeatureSize`, `PreplanCeremony`, `ReviewScope`, `AuditCeremony`, `CeremonyScaling` and `NormalizedFeatureTaskRuntimePhaseOutput`.
  - These move verbatim to a new `taskruntime/model/handoff/FeatureTaskRuntimePhaseHandoffModels.kt`: `FeatureTaskRuntimePhaseOutput`, `FeatureTaskRuntimeResolvedUpstreamOutputs`, `FeatureTaskRuntimePhaseHandoff` and `FeatureTaskRuntimePhaseDeclaration`.
- **Lift two files.** Move `FeatureTaskRuntimeHandoffProjectionInputs.kt` and `FeatureTaskRuntimeHandoffAssemblyRequest.kt` into `taskruntime.model.handoff`.
- **Budget constants.** Move `FEATURE_TASK_RUNTIME_PROJECTION_LIST_MAX_COUNT` and `FEATURE_TASK_RUNTIME_CHANGED_PATH_MAX_COUNT` from `phase/FeatureTaskRuntimePlanningProjectionModels.kt:16-18` into `handoff/task/FeatureTaskRuntimeHandoffProjectionBudget.kt`, their only user.
- **Result.** File counts: `handoff.task` 8, `handoff` 6, `phase` 6. `handoff.task` imports nothing from `handoff`, `phase`, persistence, repair or goalreview.
- **Operator-block-retry constants.** Move all five `FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_*` constants out of `store/FeatureTaskRuntimePersistenceArtifactKeys.kt:9-15` into `repair/task/FeatureTaskRuntimeOperatorBlockRetry.kt`: the public `REASON_MAX_LENGTH` and the four internal payload keys.
  - Without this, flattening leaves repair → persistence → goalreview → repair.
  - Importers to rewrite:
    - `runtime-application/.../WorkflowServiceBlockedPhaseRetry.kt:36`;
    - `phaseartifacts/FeatureTaskRuntimePhaseArtifactDecoders.kt:10-11`;
    - `artifact/FeatureTaskRuntimeWorkflowArtifactWire.kt:24-27`.
- **Receipt limits.** Move `REPAIR_RECEIPT_MAX_CONSTRUCT_SYMBOL_UTF8_BYTES` and `REPAIR_RECEIPT_MAX_CONSTRUCT_FILE_UTF8_BYTES` from `goalreview/FeatureTaskRuntimeRepairReceipt.kt:17-18` into `repair/task/FeatureTaskRuntimeRepairReceiptSanitizer.kt`. Goalreview then imports them from repair, which breaks the back edge in row 4.

#### 8. `skillbill.workflow.model.goalreview` drops to 12 files (AC-006)

Move whole families that depend only downward. The repair receipt and ledger stay (see the second point above).

- Move `GoalObservabilityModels.kt`, `GoalObservabilityParsing.kt` and `GoalObservabilitySchemaErrors.kt` into a new `skillbill.workflow.model.goalobservability`. The family is self-contained: no other goalreview file refers to it.
- Move `GoalHistoryArtifactRetention.kt` (`appendBoundedHistoryBySequence`) into `skillbill.workflow.model.persistence.artifact`.
- Move into `skillbill.review.context.model.accounting`:
  - `ReviewAccountingBoundedJson.kt`, an extension on `ReviewAccountingSummary`;
  - `GoalSubtaskCommitFocusedAccounting.kt`, which uses `SHA256_HEX` and `ReviewIntegrationTerminalOutcome`.

  If the second file turns out to refer to a goalreview declaration, swap it for another file in goalreview that depends only downward, and do not split a family.
- Result: goalreview goes from 18 to 12 files.
- Rewrite importers in all modules: `GoalProgressEvent*`, `GoalObservability*`, `asGoalWorkflowArtifactMap`, `goalObservabilityLatestEventFromArtifacts`, `appendBoundedHistoryBySequence`, `toReviewAccountingBoundedJson` and `GoalSubtaskCommitFocusedAccounting`.

#### 9. `skillbill.goalrunner` drops to at most 12 files (AC-006)

- Move `AttemptLedgerDecoding.kt`, `AttemptLedgerProgressEvents.kt` and `AttemptLedgerAccumulator.kt` into a new `skillbill.goalrunner.ledger`.
- Delete `goalrunner/DurableDecodeSubstitutionRecorder.kt`, an internal one-line forwarder. Its two callers call `DurableDecodeSubstitutionObservations.record(...)` from `goalrunner.model` directly:
  - `AttemptLedgerAccumulator.parseInstantOrNull`;
  - `GoalRunnerWorkerSubtaskRequestParser`.

  The behaviour is the same, and the ledger → goalrunner edge disappears.
- Result: 15 files become 11. Recount after subtasks 1 and 2.
- Rewrite importers:
  - `runtime-engine/.../goalrunner/persist/WorkflowGoalRunnerProgressRecording.kt`;
  - `runtime-engine/.../goalrunner/persist/WorkflowGoalRunnerOutcomeReconcile.kt`;
  - `runtime-ports/src/test/.../WorkflowRecordMappingTest.kt`;
  - the domain tests.

#### 10. Flatten and fold (AC-005)

- Move every file under `taskruntime/model/persistence/task/runtime/{checkpoint,goal,implementation,prior,run,store}/` into `taskruntime/model/persistence/`, package `skillbill.workflow.taskruntime.model.persistence`.
  - After tasks 1 and 7, 9 files remain. The ceiling is 12.
  - Rename `FeatureTaskRuntimePersistenceArtifactKeys.kt` to `FeatureTaskRuntimePersistenceConstants.kt`, since it no longer holds keys.
- Move `taskruntime/model/repair/task/*` into `taskruntime/model/repair/`, giving 4 files.
- Confirm that no `handoff/envelope` directory exists.
- A package-prefix import rewrite is safe here:
  - `skillbill.workflow.taskruntime.model.persistence.task.runtime.<sub>.` becomes `skillbill.workflow.taskruntime.model.persistence.`;
  - `skillbill.workflow.taskruntime.model.repair.task.` becomes `skillbill.workflow.taskruntime.model.repair.`.
- Check the merged packages for duplicate top-level names.

#### 11. Ceilings and baseline (AC-001, AC-006)

- In `ArchitectureScanSupport.kt`, delete the `skillbill.goalrunner` and `skillbill.workflow.model.goalreview` branches at `:473-474` and `:505-506`. Fold the two `when` copies into one private `siblingCeiling(packageName)`: a package whose last segment is `model` gets 20, everything else gets 12.
- Keep `packageSiblingCountRemainderInventory` empty.
- Empty `baselines/runtime-domain-package-cycle-baseline.txt` but keep the file.
- Before handing off, grep the import edges to confirm the graph matches the target DAGs above.

#### 12. Path-keyed guards (AC-001 and AC-005 support; update rows in place, never add one)

- In `PrincipleEnforcementInventory.parseBoundarySites`, update these `relativePath` values. A missing file fails the guard through `notLocatedViolation`, so stale paths show up.
  - `FeatureTaskRuntimeRunInvariantPromptFields.kt` → `model/handoff/task/`.
  - `FeatureTaskRuntimeGoalContinuationArtifact.kt`, `FeatureTaskRuntimeGoalContinuationPersistenceModels.kt`, `GoalSubtaskReviewArtifactDecoder.kt` and `FeatureTaskRuntimeImplementationAttemptModels.kt` → `model/persistence/`.
  - `GoalObservabilityModels.kt` and both `GoalObservabilityParsing.kt` entries → `model/goalobservability/`.
  - `goalrunner/AttemptLedgerDecoding.kt` → `goalrunner/ledger/`.
- Change the marker at `WireVocabularyGovernedSeamInventory.kt:84` to `taskruntime/model/persistence/FeatureTaskRuntimeGoalContinuationArtifact`. A stale marker silently matches nothing. Update the synthetic paths at `WireVocabularyArchitectureTest.kt:210,219` to match.
- If any entry in `RuntimeArchitectureTestSupport.rawMapBoundaryAccessors` still names `persistence.task.runtime.goal` after subtask 1, rewrite its FQN.

#### 13. Domain test packages (AC-007)

- Move each domain test file into the package and directory of its subject. The subject is the declaration the test class is named after (`FooTest` → `Foo`); failing that, use the package most of its imports come from. Change only the package line, the imports and the directory; edit no assertion.
- Orphan test packages today:
  - `skillbill.config` (1 file), `skillbill.install` (1);
  - `review.context.model.review` (4), `review.review` (2);
  - `skillbill.workflow` (2), `workflow.failureidentity` (1), `workflow.goal` (1);
  - `taskruntime.feature` (8), `taskruntime.review`, `taskruntime.semantic`, `taskruntime.unbounded`;
  - `scaffold.policy.scaffold`.
- These moves create more:
  - `persistence.task.runtime.{prior,implementation,persistence}` → `taskruntime.model.persistence`;
  - `repair.task` → `taskruntime.model.repair`.
- Final check: every `^package` in `runtime-domain/src/test` also appears in `src/main`.

#### 14. ARCHITECTURE.md (AC-007)

- **Package Ownership, runtime-domain rows.** Every package named must exist in domain main; check against `grep -rh '^package' runtime-domain/src/main`.
  - Replace the `skillbill.workflow.goal` / `.goal.model` row (`:648`) with `skillbill.workflow.model.goalobservability`.
  - Delete the `skillbill.workflow.idestatus` row (`:661`).
  - Change `skillbill.idestatus` (`:670`) to `skillbill.idestatus.model`.
  - Change `skillbill.workflow.taskruntime` / `.model` (`:651`) to `skillbill.workflow.taskruntime.*`.
  - In the `skillbill.review` row (`:732`), name packages that exist.
  - Change the `persistence.task.runtime.store` row (`:735`) to `skillbill.workflow.taskruntime.model.persistence`.
  - Add `skillbill.goalrunner.ledger`.
  - Extend the `skillbill.text` row with SHA-256 hex hashing and the `install.model` row with platform-pack selection.
  - Describe what remains in goalreview.
- **Stale FQNs.** Fix `:1587` (`GoalProgressEventValidator`), `:1600` (`IdeStatusValidator`) and `:2560` (`GoalSubtaskCommitFocusedAccounting`). Grep for each real declaration to get its FQN.
- **Every moved FQN.** Grep the document for each one and rewrite it.

### Constraints

- Add no typealias, baseline row or exemption name. Change no behaviour.
- Move declarations verbatim. The only edits besides moves are:
  - the package and import lines;
  - the moved constants;
  - the deleted forwarder;
  - the three digest call sites in task 4.
- Persisted bytes and CLI/MCP output stay identical. Edit no wire fixture and no expected-payload assertion.
- Keep `package` and `import` lines at column 0.
- Moved `internal` declarations stay `internal`; the module is unchanged.
- Each move turns some same-package references into imports. Add those imports and re-check the edge direction.
- Keep `targetReached`, `pauseAtOperatorBoundary` and the stop-reason rules that subtask 2 adds in `goalrunner.model`. They import only `workflow.decomposition.model`.
- Do not add the domain test imports of `withWorkflowId` or `withCompletedSubtask` to `runtime-engine/.../GoalRunnerTest.kt`, which declares local functions with those names.
- Only the build and validate phases run the build, the tests or `check`.

### Tests

- New: one synthetic leaf-rule test (task 5).
- Existing guards prove everything else once the paths are updated:
  - `ApplicationPackageAcyclicityArchitectureTest`: the empty domain baseline and the model-package rule;
  - `PackageSiblingCountArchitectureTest`;
  - `TypedParseBoundaryArchitectureTest`;
  - `WireVocabularyArchitectureTest`;
  - `RuntimeRawMapArchitectureTest`, including its domain artifact-key rule.
- Existing unit tests that pin digests and step-policy fingerprints prove task 4 is byte-identical.

### Acceptance criteria by task

| AC | Tasks |
|---|---|
| AC-001 | 1-11 |
| AC-002 | 1 |
| AC-003 | 2, 3 |
| AC-004 | 4, 5 |
| AC-005 | 10 |
| AC-006 | 8, 9, 11 |
| AC-007 | 13, 14 |
| AC-008 | all (no aliases or rows; task 5 adds no name) |

## Next Path

skill-bill goal SKILL-397

## Spec Path

.feature-specs/SKILL-397-runtime-domain-boundary-integrity/spec_subtask_3_domain-package-graph-repair.md
