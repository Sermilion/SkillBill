# Runtime-application investigation

## Judgment

The module has a sound inward dependency graph after SKILL-347 and SKILL-370. Its remaining problems are specific. Durable acceptance decoders can truncate a numeric subtask identity, the typed-parse scanner silently misses selected functions, review orchestration still owns Git and GitHub command syntax, and a small amount of CLI presentation and unused code remains in application. Preserve genuine ports and the current module graph. Repair the existing boundaries rather than add another service layer.

## Method and baseline

Investigation used direct source reading, grep, and Python. No census or review work was delegated. Read CLAUDE.md, runtime-kotlin/ARCHITECTURE.md including Design Principles, Gradle Modules, Package Ownership, Boundary Rules, Guardrails, and Wire vocabulary, docs/code-principles.md, and runtime-application agent decisions and history. Located prior module work through the requested spec search and read the SKILL-370 investigation and spec in full. Reviewed SKILL-347 acceptance and retention decisions.

Investigation baseline HEAD is dfb489641f6d67a3e3f45e4a67330a3fff798029 on base/SKILL-380-phase-slot-strategies. The source tree remains at that SHA. The final preparation fetch found a concurrently committed SKILL-386 runtime-cli bundle on origin/base/SKILL-380-phase-slot-strategies. SKILL-387 remains reserved for the running prose-output preparation. A fresh active/done directory, branch, and all-ref commit census selected SKILL-388 immediately before writing. No checkout, branch creation, source edit, staging, or commit occurred. Only this bundle was written.

The installed command skill-bill config resolve-spec-type --arg local returned local. No Linear calls occurred. No compilation, tests, generator execution, full checks, or workflow execution occurred. Census scratch data was stored outside the repository.

## Evidence path notation

A means runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/. D means runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/. P means runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/. G means runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/. SQL means runtime-kotlin/runtime-infra/sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/. WF means runtime-kotlin/runtime-infra/workflow/src/main/kotlin/skillbill/infrastructure/workflow/. These prefixes identify actual source paths, not generated files.

## Measured census

| Application source set | Kotlin files | Lines | Packages |
| --- | ---: | ---: | ---: |
| main | 179 | 18315 | 52 |
| test | 65 | 16353 | 22 |
| testFixtures | 19 | 1950 | 6 |
| repoTest | 1 | 44 | 1 |

Lines include blank lines. Package counts use declared package names.

Main package file counts below omit the common skillbill.application prefix. The root package is shown as root.

| Package | Files | Package | Files |
| --- | ---: | --- | ---: |
| root | 1 | agentoutput | 1 |
| agentrun | 2 | agentrun.model | 1 |
| config | 1 | continuation.model | 1 |
| decomposition | 11 | decomposition.model | 3 |
| diagnostics | 2 | diagnostics.model | 3 |
| idestatus | 1 | install | 4 |
| learning | 3 | learning.model | 2 |
| review.learnings | 2 | review.model | 9 |
| review.packet | 6 | review.parallel.planning | 6 |
| review.parallel.runner | 7 | review.parallel.verification | 3 |
| review.preparation | 2 | review.preparation.model | 1 |
| review.service | 8 | review.snapshot | 1 |
| review.spec | 5 | review.stats | 4 |
| review.verification | 3 | reviewevidence | 8 |
| reviewevidence.model | 2 | runtime | 1 |
| runtimepersistence | 1 | scaffold | 7 |
| scaffold.model | 1 | system | 2 |
| telemetry.config | 2 | telemetry.lifecycle | 7 |
| telemetry.model | 3 | telemetry.service | 4 |
| telemetry.settings | 2 | telemetry.sync | 3 |
| telemetry.validation | 3 | uninstall | 3 |
| uninstall.model | 1 | updatecheck | 3 |
| updatecheck.model | 3 | work | 1 |
| work.model | 1 | workflow | 1 |
| workflow.decomposition | 7 | workflow.model | 7 |
| workflow.persist | 4 | workflow.service | 9 |

Test package counts under the same prefix are root 19; decomposition 1; diagnostics 1; idestatus 1; review.packet 1; review.parallel.planning 6; review.parallel.runner 10; review.preparation 3; review.service 2; review.snapshot 4; review.spec 2; review.stats 1; review.verification 1; reviewevidence 2; scaffold 1; telemetry.service 1; telemetry.settings 1; telemetry.sync 1; uninstall 2; updatecheck 2; workflow.decomposition 1; workflow.persist 2. Test fixtures are application root 12, review.governed 1, review.recording 1, review.snapshot 3, telemetry.lifecycle 1, and skillbill.workflow 1. RepoTest is application.review.snapshot 1. No application test package lacks a corresponding main package. Fixture namespaces intentionally include recording and governed substitutes.

The largest main non-model package has 11 files; the largest model package has nine. No file-size or sibling-count split is proposed.

### Gradle edges

runtime-application/build.gradle.kts:10-14 declares api dependencies on runtime-contracts, runtime-domain, and runtime-ports, plus implementation dependencies on Kotlin-inject runtime and serialization JSON. Its public request/result signatures and injectable constructors expose types from all three project dependencies. Narrowing those edges without changing the API would conceal required signature dependencies.

At lines 16-22, testFixturesImplementation includes runtime-infra:host, runtime-infra:contracts, runtime-infra:workflow, ports fixtures, domain fixtures, kotlin.test, and Jackson YAML. At lines 23-28, testImplementation includes application fixtures, ports fixtures, domain fixtures, JUnit, kotlin.test, and Jackson YAML. These are test support edges. There is no engine dependency in any application configuration.

The surrounding main graph remains inward. CLI and MCP consume application and core alongside contracts, domain, ports, and engine. Engine publishes contracts/domain/ports and uses application through implementation. Core composes application, ports, engine, and infrastructure adapters. Infrastructure main code has no application import in the explicit-import census. Contracts, domain, and ports do not import application. Infrastructure sibling dependencies provide adapter facilities and do not create an application-to-infrastructure edge.

### Symbol consumers

| Other module/source set | Distinct explicit application imports |
| --- | ---: |
| CLI main | 96 |
| CLI test | 17 |
| engine main | 72 |
| engine test | 81 |
| engine testFixtures | 12 |
| core main | 25 |
| core test | 54 |
| core repoTest | 2 |
| MCP main | 42 |
| MCP test | 5 |
| MCP repoTest | 6 |
| infrastructure contracts test | 4 |
| infrastructure main, domain, ports | 0 |

These counts are distinct import paths, including nested declarations and fixture imports. They are not compiler-resolved exported-symbol counts.

The declaration matcher recorded 763 candidate declarations: 243 default-public, 329 internal, and 191 private. It recorded 572 non-private candidates. Of these, 342 have no external lexical name occurrence in other modules' main/test/fixture sources. Forty-six are default-public candidates: 24 classes or types, seven functions, one object, and fourteen values. The matcher is conservative and does not enumerate every extension, nested member, or inferred type. Do not convert these candidate counts into a blanket visibility change.

Eighty-three candidate declarations have exactly one explicit-importing main module: CLI 38, engine 37, core four, MCP four. They comprise 47 classes, 29 functions, four values, two interfaces, and one object. Their ownership requires same-module callers, signature closure, and generated access checks.

CLI-only main-import candidates are toLearningListContract, toLearningDeleteContract, decodeScaffoldPayloadObject, RuntimeProvenanceService, resolveFeatureTaskGovernedSpecPath, RejectedOutputDiagnosticInspection, UninstallRequest, UninstallPlan, UninstallResult, RejectedOutputDiagnosticInspectionResult, RejectedOutputDiagnosticMetadata, RejectedOutputDiagnosticAmbiguousSelectorError, WorkListItem, WorkListResult, UpdateRunStatus, UpdateRunRequest, UpdateRunPlan, UpdateRunResult, FeatureTaskGovernedSpecPathResult, WorkflowServiceOpenFeatureTaskArgs, RepairFeatureTaskRuntimeIdentityArgs, openFeatureTask, TelemetryStatusResult, TelemetrySyncStatusResult, TelemetryMutationResult, ReviewPreviewResult, ReviewFeedbackResult, FeatureTaskRuntimeStatsResult, ReviewSnapshotPruneResult, RequestedReviewMode, toReviewPreviewContract, toReviewFeedbackPayload, toFeatureTaskRuntimeStatsPayload, EditLearningInput, LearningListResult, LearningRecordResult, LearningResolveResult, and LearningDeleteResult. Most are application-owned use-case inputs/results or have application callers. Only uninstall text bodies have a demonstrated CLI-only ownership move.

Engine-only main-import candidates are rethrowIfCooperativeCancellationOrInterruption, getOrElseUnlessCooperative, defaultFeatureBranch, loadValidatedDecompositionManifestPersistingRepair, encodeValidatedDecompositionManifestYaml, DECOMPOSITION_MANIFEST_FILENAME, loadManifestOrNull, resolvedParentSpecPath, repoRelativePath, specSource, executionModel, baseBranch, decompositionPlanningSubtask, decompositionPlanningResult, AgentActivityStampWriter, RejectedOutputDiagnosticService, agentFailureExcerpt, headAndTailExcerpt, ReviewDiffEvidence, RejectedOutputDiagnosticRequest, requireRuntimeModeForEngineWrite, goalContinuationFor, updateGoalParentForBlockedPhaseRetry, generateWorkflowId, FeatureTaskRuntimeStartedRequest, FeatureTaskRuntimeCorrelation, FeatureTaskRuntimeFinishedRequest, FeatureTaskRuntimeAgentContext, GoalStartedRequest, GoalSubtaskFinishedRequest, GoalFinishedRequest, GoalIssueFinishedRequest, toProjectionPayload, SpecIntentProjectionResolver, ParallelCodeReviewRunnerResultAssembly, DecompositionPlanningSubtaskOptions, and DecompositionPlanningResultOptions. Current application callers and reusable use-case responsibilities prevent an ownership move based solely on this list.

Core-only candidates are AgentRunGoalRunnerSubtaskLauncher, AgentRunService, DefaultTelemetrySettingsProvider, and TelemetryLevelMutationService. Core is their composition consumer, which justifies their current ownership. MCP-only candidates are qualityCheckResults, historySignalValues, featureVerifyCompletionStatuses, and toReviewFinishedTelemetryPayload. These are existing shared validation/payload vocabulary rather than a reason to create another interface.

Test imports principally consume application service boundaries, request/result types, decomposition helpers, snapshot harnesses, and explicit fixture substitutes. Application tests import no engine, CLI, MCP, or DI namespace. Visibility decisions must account for friend-path test access and Kotlin-inject generated construction. A public type returned through inference may have no direct external name reference.

### Interfaces and substitutes

The census found 72 consumed non-sealed interfaces across application and ports. Counts below are direct named production implementations and direct test/fixture substitutes. SAM bindings and inherited implementation need separate inspection.

| Interface | Production | Test substitutes |
| --- | ---: | ---: |
| GoalLifecycleTelemetryEmitter | 2 | 3 |
| ReviewContextEnvelopeValidator | 1 | 12 |
| ScaffoldGateway | 1 | 1 |
| InstallPlanWireValidator | 1 | 1 |
| AgentRunLauncher | 1 | 5 |
| FeatureTaskRuntimeWireArtifactValidator | 1 | 5 |
| FeatureTaskRuntimeSharedEvidenceLocatorReadPort | 1 | 2 |
| FeatureTaskRuntimeSharedEvidenceResolverPort | 1 | 2 |
| DiffResolverPort | 2 | 8 |
| RepoLocalConfigPort | 1 | 7 |
| RejectedOutputDiagnosticRepository | 1 | 5 |
| RejectedOutputDiagnosticMetadataValidator | 1 | 1 |
| ProducerOutputEvidenceValidator | 1 | 0 |
| RejectedOutputDiagnosticPermissions | 1 | 1 |
| RuntimeDiagnostics | 2 | 43 |
| HostPlatformPort | 1 | 4 |
| UninstallPathsPort | 1 | 3 |
| DatabaseSessionFactory | 1 | 36 |
| GoalRunnerPersistenceSession | 0 | 0 |
| WorkflowSnapshotValidator | 1 | 22 |
| WorkflowStateRepository | 1 | 8 |
| AgentActivityStampRepository | 1 | 2 |
| DecompositionManifestProjectionWriter | 1 | 0 |
| InstallerProcessPort | 1 | 1 |
| InstallerScriptFetchPort | 1 | 1 |
| ReleaseCatalogPort | 1 | 5 |
| InterruptSignalPort | 1 | 4 |
| UnitOfWork | 1 | 3 |
| RepositoryEnclosingRootPort | 1 | 2 |
| RepositoryOriginScopeKeyPort | 1 | 0 |
| SkillRemoveFileSystem | 1 | 1 |
| DecompositionManifestStore | 1 | 15 |
| DecompositionManifestValidator | 1 | 8 |
| WorkflowGitOperations | 1 | 21 |
| GoalRunnerSubtaskLauncher | 1 | 7 |
| AgentRunActivityStampSink | 0 | 0 |
| AgentRunProgressProbe | 1 | 2 |
| InstallAgentTargetPort | 1 | 2 |
| InstallApplyExecutionPort | 1 | 2 |
| InstallSkillLinkPort | 1 | 1 |
| InstallMcpRegistrationPort | 1 | 8 |
| InstallNativeAgentLinkPort | 1 | 2 |
| InstallPlanningFactsPort | 1 | 2 |
| InstallPlatformSkillMaterializationPort | 1 | 2 |
| InstallStagingIntentPort | 1 | 2 |
| InstallSelectionPersistencePort | 1 | 2 |
| BaselineManifestPersistencePort | 1 | 1 |
| InstallReconcileApplyPort | 1 | 1 |
| InstallReconcilePort | 1 | 1 |
| ExternalAddonOverlayPort | 1 | 1 |
| ExternalAddonSourceConfigPort | 1 | 1 |
| ExternalPlatformPackSourceConfigPort | 1 | 0 |
| PlatformPackCatalogPort | 1 | 0 |
| InstalledPlatformPackCatalogPort | 1 | 0 |
| ReviewEvidenceBroker | 1 | 8 |
| ReviewEvidenceBrokerFactory | 0 | 0 |
| ReviewSnapshotGateway | 1 | 1 |
| GovernedReviewEvidenceEndpointHandle | 1 | 6 |
| GovernedReviewEvidenceEndpointBinder | 1 | 6 |
| ReviewStoredHunkBodyExtractor | 1 | 0 |
| ReviewLaunchAgentStagingPort | 1 | 0 |
| ReviewNativeAgentPreflightPort | 1 | 0 |
| ReviewAttributionPort | 1 | 4 |
| ReviewRubricResolver | 1 | 2 |
| ReviewInputSource | 1 | 2 |
| ReviewSpecialistContractProvider | 1 | 0 |
| ReviewRepository | 1 | 2 |
| TelemetryConfigStore | 1 | 3 |
| TelemetryLevelMutator | 1 | 1 |
| TelemetrySettingsProvider | 1 | 15 |
| TelemetryClient | 1 | 9 |
| TelemetryOutboxRepository | 2 | 10 |

GoalRunnerPersistenceSession is implemented through UnitOfWork and its SQLite implementation and substitutes. AgentRunActivityStampSink has production SAM construction and a production default callback. ReviewEvidenceBrokerFactory has the production SAM binding at runtime-core/src/main/kotlin/skillbill/di/review/RuntimeReviewEvidenceProvides.kt:31 and test/fixture SAM substitutes. Therefore zero direct named implementations do not establish dead interfaces. No consumed interface was confirmed to have neither a production implementation nor a production binding. Adapter ports remain legitimate even when a direct substitute was not found.

### Other lexical inventories

Application main has zero typealiases, zero engine or infrastructure namespace imports, zero SQLITE_BUSY/database-is-locked strings, and zero listed ambient System clock/environment, Instant.now, or Thread calls. No alias-plus-model duplicate remains. Thirty-six @Inject constructor declarations were inspected; none exposes an injected constructor parameter as a non-private property. Prior review and install dependency bags are gone.

Raw Map<String, Any?> appears on 80 lines in 21 application files. These include private/internal wire assembly, domain artifact translation, and payload projection. This textual count does not establish 80 public API violations. No additional public raw-map boundary was confirmed. Existing typed contract carriers and shared presenter mappings remain.

JSON/JsonCodec call patterns appear on 22 lines in nine files, including encoding and map conversion. The five lines matching direct parse-oriented patterns are ScaffoldCommandRequestDecoder.kt:8, WorkflowServiceInputMapping.kt:101, WorkflowServiceFeatureTaskAbandon.kt:89, and ReviewClaimVerificationRunner.kt:378,384. Legacy migration map conversion is separately recorded at LegacyGoalRunnerControlMigration.kt:40,71,107. Shared decoding and serialization are not automatically domain re-decoding defects.

Enum-wire spelling matches produced 135 occurrences in 46 files. This includes enum declarations, prose, and unrelated strings. Confirmed restatements include pending in DecompositionManifestWriterPlan.kt:35 and WorkflowServiceBlockedPhaseRetry.kt:256; completed/running in DecompositionWorkflowContinuation.kt:147,152,396; completed/in_progress in DecompositionWorkflowResumeAlignment.kt:142,223,229; running in WorkflowServiceInputMapping.kt:170; and inline in DecompositionWorkflowContinuationAdvancement.kt:161. Use the existing owning enums' wireValue properties. Phase identifiers and open telemetry level collections must not become closed enums merely because their spellings match.

A mutable-declaration pattern found 43 lines in 22 files, mostly locals and invocation-scoped parser/recorder state. AgentActivityStampWriter's bounded instance throttle state and LatestStamp fields at lines 151-152 have an explicit lifetime. ReviewEvidenceReadCount.kt:4 is invocation evidence accounting. Private workflow/domain helpers are stable service collaborators rather than public dependency getters. No new process-global mutable application state was confirmed.

There are 43 explicit .transaction calls across 14 application files. Owners are decomposition projection failure persistence, LearningService, RejectedOutputDiagnosticInspection, RuntimeOwnedPersistenceBoundary, WorkflowServiceInputMapping, WorkflowServiceBlockedPhaseRetry, WorkflowService, LifecycleTelemetryService, LifecycleTelemetryGoalEmission, TelemetryLevelMutationService, TelemetryService, ReviewService, ReviewServiceTriage, and ReviewLearningsResolver. P/persistence/UnitOfWork.kt:22 extends GoalRunnerPersistenceSession and exposes named non-null repository capabilities. SQLite owns connection, locking, retry, commit, and rollback mechanics. Application owns use-case transaction extent. Do not split live migration reads and writes across sessions.

## Principle assessment

| Checklist item | Assessment and evidence |
| --- | --- |
| 1. Dependency direction | Clean main direction. Application Gradle lines 10-14 publish inner signature dependencies. Engine has an implementation edge into application. No application main import of infrastructure, engine, CLI, or MCP was found. Test-fixture adapter edges are separate. |
| 2. Inbound use cases and decoding | CLI/MCP consume concrete application services and typed requests/results. WorkflowService no longer exposes validator getters and receives decoded goal summaries. Shared scaffold decoding is an intentional common boundary. No justification exists for speculative inbound use-case interfaces. |
| 3. Outbound ports | Most ports are purpose-built and have substitutes. DiffResolverPort.runProcess at P/diff/DiffResolverPort.kt:7 is a confirmed generic transport leak. Application owns git/gh argv and protocol parsing at A/reviewevidence/SharedReviewEvidenceAssembly.kt:99,110,117 and review planning/coordinates sites. No application SQL busy strings remain. Installer ports are retained with their current plan/execution contract. |
| 4. Domain richness and duplicate rules | Aggregate and artifact access rules landed in domain. Exact integer decoding exists at D/workflow/model/persistence/artifact/DurableArtifactMapReader.kt:146. Three acceptance paths still use Number.toInt. The application live migration and SQLite one-time migration intentionally have different transaction contexts; their equivalent policy/acceptance grammar must agree without a new parser framework. |
| 5. Composition | Runtime-core remains the binding root. Thirty-six injected constructors have no exposed collaborator properties. Prior bags are absent. Retain WorkflowService's previously accepted private helper/domain construction. RuntimeReviewEvidenceProvides.kt:55 binds DiffResolverPort and line 61 binds the existing parser function type. |
| 6. Entry-point leakage | UninstallModels.kt:32,42,61 contains CLI-only prompts and text. Move these bodies to CLI. UpdateCheckText.kt:6 has both CLI and engine consumers, including engine/operation/updatecheck/UpdateCheckOperation.kt:23, so its shared formatter stays. Shared wire DTO mapping stays. |
| 7. Ambient effects | Listed clock/environment/thread scans are clean in application main. AgentActivityStampWriter uses TimeSource. Host environment is passed through requests. UUID/random identity construction is an existing use-case responsibility, not evidence for a new clock or identity abstraction. |
| 8. State and transactions | Forty-three explicit transaction calls have named use-case owners. WorkflowService.kt:433 keeps continuation migration in the transaction; projection follows the transaction. SQLite owns database mechanics. Preserve durable-control authority, rollback, and migration idempotence. Bounded service state and invocation-local parsing state remain. |
| 9. Error model | No Exception/Throwable catch was found in application main. CooperativeFailurePropagation.kt:5,13 preserves cancellation/interruption. Legacy migration uses error/require/IllegalStateException for malformed input, and acceptance construction can leak IllegalArgumentException. FileSystemDiffResolver.kt:68 restores interruption then returns null, erasing the failure into ordinary unavailable evidence. |
| 10. Cohesion and ownership | Eighty-three single-main-consumer candidates require signature and local-caller analysis. Concrete CLI-only uninstall rendering has a feasible move. Engine-consumed diagnostics, telemetry, decomposition, and activity services still have application responsibilities. Free pure helpers do not need service wrappers. |
| 11. YAGNI | No application aliases or test-only production telemetry NONE member remain. Seven unused decoding helpers survive in DecompositionManifestWriterPaths.kt:25-84, and specInputTypes has only its declaration. Remove them. Keep interfaces justified by adapters, inherited implementation, SAM bindings, or substitutes. |
| 12. Naming and packages | No orphan application test package or repeated package segment was confirmed. Maximum package counts are 11 non-model and nine model files. No size-only or naming-only decomposition is justified. |
| 13. Guard validity | Module roots resolve to real runtime-kotlin source trees and read files. However, TypedParseBoundaryArchitectureTest silently skips absent function names and its function pattern misses extension declarations. Root correctness alone does not prove coverage. Repair the existing scanner and inventory. |

## Ranked findings

### F-001, P1. Acceptance identities are decoded with lossy numeric coercion

Evidence: A/workflow/service/LegacyGoalRunnerControlMigration.kt:76; SQL/workflow/goalrunner/runner/LegacyGoalRunnerControlLedgerMigration.kt:112; SQL/workflow/goalrunner/runner/GoalRunnerControlStoreDecodePolicies.kt:92-97. A fractional Number can become another subtask's integer identity. Overflow and non-finite values are also not rejected by the intended exact-positive-Int boundary. Policy and acceptance parsing in the two legacy paths reports malformed external data through generic reporters, and blank/invalid constructor values can escape as untyped model exceptions.

Fix: gate input as Number, reuse the existing domain exact-integer conversion, and require a positive Int before constructing an acceptance. Do not broaden accepted input to numeric strings. Use the existing InvalidWorkflowStateSchemaError family for malformed goal-control records, while retaining the separately typed add-on selection failure. Preserve accepted valid bytes, authoritative existing controls, snapshot reads, duplicate-entry behavior, and migration idempotence. No new migration framework or schema version is needed.

The live application migration is called by decomposition continuation, resume alignment, and blocked-phase retry. The SQLite migration is registered by DatabaseMigrationEntries.kt:578-579. These are distinct transaction contexts. A comparison found shared review-policy and acceptance grammar, different source containers, and different current error/key handling. Keep their ownership separate and test agreement at observable boundaries.

### F-002, P1. Typed-parse enforcement can scan real files while enforcing nothing for selected functions

Evidence: G/ArchitectureScanSupport.kt:177-189 reads a registered file and iterates only extracted bodies, without checking selected-name coverage. Its function pattern at line 533 recognizes ordinary functions but not extension receivers. Static inventory inspection found 35 registrations over 33 files with 95 selected names. Nineteen selected names are absent from their registered files. Six existing extension functions fail the current pattern.

Absent names comprise eleven in GoalRunnerControlStore.kt, three in GoalSubtaskReviewFindingArtifacts.kt, three in AttemptLedgerWorkflowDecoding.kt, and two in WorkflowEngineSnapshotCodec.kt. The SQLite decoders now live in GoalRunnerControlStoreDecodeState.kt, DecodePolicies.kt, and DecodeValues.kt. The review-artifact decoders now live in D/workflow/taskruntime/model/persistence/task/runtime/goal/GoalSubtaskReviewArtifactDecoder.kt:29,82,108. Some old workflow decoder names describe removed code and must be replaced by current authority or retired with evidence, not resurrected.

The six extension blind spots are toDecompositionManifest and toDecompositionSubtask in DecompositionManifestWireCodec.kt, asGoalWorkflowArtifactMap in GoalObservabilityParsing.kt, toReviewStateMap and requireOnlyReviewStateKeys in GoalSubtaskReviewStateDecoding.kt, and decodeDeclaredGoalProgressEvent in AttemptLedgerDecoding.kt.

Fix: repair the existing scanner to recognize the actual registered declaration/body forms and fail loudly if any selected function cannot be located or inspected. Correct stale registrations, preserving coverage of their current boundary replacements. Add the legacy migration and acceptance helper closure to the inventory. Extend TypedParseBoundaryArchitectureTest with meaningful synthetic missing-selection and extension-reporter rejection cases. No new architecture-test class and no baseline expansion.

### F-003, P2. Review orchestration owns process protocols and unavailable output can look like empty evidence

Evidence: P/diff/DiffResolverPort.kt:7 accepts arbitrary argv. Application constructs git rev-list/show/diff at SharedReviewEvidenceAssembly.kt:99,110,117; Git revision and gh baseRefOid queries at review/parallel/planning/ParallelCodeReviewRunnerPlanningRevisions.kt:58,65,70,78; tracked/untracked/no-index commands in PlanningLaneMap.kt:57-120; and index protocol decoding in review/parallel/runner/ParallelCodeReviewEvidenceCoordinates.kt:20. Engine has one additional live process caller in featuretask/review/core/FeatureTaskRuntimeSharedReviewEvidenceResolver.kt:63. The other production implementation is FeatureTaskRuntimeUnreadableDiffResolver.

WF/filesystem/FileSystemDiffResolver.kt:35-75 owns bounded process execution but accepts exit 0 and 1 globally, returns null for failure, and converts interruption to null at line 68. Callers using orEmpty can turn unavailable output into an apparently valid empty evidence payload. Its cleanup IOException is swallowed at line 75.

Fix: replace this port's arbitrary process operation with only the review fact queries required by current callers. Move Git/GitHub argv, response-field syntax, commit metadata decoding, and index-protocol decoding into the existing adapter. Keep review attribution, scope selection, coverage decisions, and pure diff parsing in their present policy owners. Adapt the one engine caller, the unreadable implementation, and substitutes in the same commit, then remove the generic operation and its forwarding helpers. Preserve intentional no-index exit-1 success only for the query that permits it. Represent unavailable evidence distinctly from a successful empty diff, propagate cooperative interruption, and record degradations including cleanup failure. Keep the existing port and DI binding; do not add an inbound interface, process framework, or new module.

### F-004, P2. Legacy/control seams bypass shared wire vocabulary

Evidence: A/workflow/service/LegacyGoalRunnerControlMigration.kt:43-85 restates review-policy and acceptance keys. SQL GoalRunnerControlStoreDecodePolicies.kt:84-87 and the legacy ledger migration repeat acceptance keys. The wire seam inventory does not cover these control decoder paths. Confirmed enum-token restatements are listed in the census.

Fix: use existing authoritative SharedPayloadKeys, DecompositionManifestPayloadKeys, and FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys for fields they own. Establish one shared owner for acceptance-specific fields read by application and SQLite, without copying existing shared declarations or borrowing an unrelated MCP field owner. If a generic field is consolidated, update its existing owners to reference the single authority. Keep JSON field names, ordering, accepted records, and schema versions unchanged. Extend WireVocabularyGovernedSeamInventory and the existing scanner's rejection evidence for these bounded encode/decode seams. Use existing enum wireValue tokens at the confirmed sites. Do not close open artifact or telemetry vocabularies.

The assessment access at DecompositionManifestRuntimeState.kt:48 is not established as a closed governed field. It remains unchanged rather than receiving a speculative artifact contract.

### F-005, P3. CLI-only uninstall presentation lives on application models

Evidence: A/uninstall/model/UninstallModels.kt:32,42,61 contains the confirmation prompt and text renderers. CLI system/UninstallCommand.kt:67,72,76 consumes those bodies. There is no engine uninstall text consumer.

Fix: move only these rendering bodies to the existing CLI system area. Leave UninstallRequest, UninstallPlan, UninstallResult, their Path values, status, exit code, and application orchestration in application. Preserve exact prompt/text bytes and JSON payloads. Retain UpdateCheckText because it has a real engine consumer.

### F-006, P3. Unused decoding helpers and module-local exports enlarge the API

Evidence: A/decomposition/DecompositionManifestWriterPaths.kt:25-84 contains optionalIntValue, intValue, booleanValueOrDefault, asInt, asIntOrNull, asStringAnyMap, and asStringAnyMapOrNull. Their only references are within this unused cluster. Retain resolvedParentSpecPath at line 8 and repoRelativePath at line 13, which engine consumes. The unrelated intValue helper in review verification is live and is outside this finding. A/telemetry/validation/CommonTelemetryValidators.kt:3 declares specInputTypes with no Kotlin consumer.

Fix: delete those seven helpers and specInputTypes, then remove their unused imports. Narrow demonstrated module-only functions, values, and the DecompositionManifestProjectionOperations object, which has real application callers. Candidates include validateDecompositionManifestYaml, learningEntryDto overloads, learningAppliedSessionWire, scaffold session helpers/constants, IMPLEMENT_TERMINAL_STATUSES, WorkflowServiceConstants values, REDACTED_ERROR_MESSAGE, INSTALL_SCRIPT_URL, and repoScopeKeyOrNull. Choose private only when file-local; otherwise use internal. Preserve public inferred request/result closure and generated Kotlin-inject access. Do not mechanically narrow the 24 name-local type candidates.

## Feasibility

No relocation to domain is proposed. Domain bans java.nio and skillbill.ports; uninstall models import java.nio.file.Path and therefore cannot move there. Moving only uninstall text bodies to CLI requires the existing application model imports and the already allowed CLI-to-application dependency. It introduces no dependency cycle or DI change.

The review relocation moves transport syntax and protocol decoding into WF/filesystem or its existing workflow adapter area. The current adapter imports Kotlin-inject, bounded host process request/output/runner types, checkpointFileIdentity, DiffResolverPort, ReviewCheckpointFileIdentity, IOException, Files, Path, and logging. All are allowed in that adapter. Retained application policy files continue importing domain review models, purpose-built port facts, and Path values. Any new facts type belongs to the existing ports/domain dependency closure and must not import application or adapter libraries. The engine caller already depends on ports. Both production implementations and eight direct substitutes must be adapted together.

RuntimeReviewEvidenceProvides.kt:55 binds FileSystemDiffResolver to DiffResolverPort. Its parser function binding at line 61 remains. No new constructor binding, function-type binding, scope, or injectable helper is proposed. Kotlin-inject generated compilation is still required to confirm compatibility after visibility and port changes.

For F-001, application and SQLite already depend on domain. Importing D/workflow/model/persistence/artifact/asExactIntOrNull is inward and permitted. The helper also accepts strings, so the Number gate is essential to preserve grammar. Keep each migration in its existing transactional context and preserve validation before irreversible projection or migration completion.

Dead-helper deletion removes invalidManifest, BigDecimal, and BigInteger imports from WriterPaths while retaining Path. It does not change the live domain exact-number helper. Visibility changes require signature checks and generated-access compilation, not name-count assertions.

## Guard inspection

Opened the cited guards and traced their readers. RuntimeRawMapArchitectureTest uses moduleMainKotlinRoot through RuntimeArchitectureTestSupport.kt:106 and RuntimeModuleCatalog.kt:248. The root resolves to runtime-kotlin/<module>/src/main/kotlin, with missing-root and nonempty assertions at support lines 114-123 and actual file reads. Application contributes 179 main files. Its domain and ports roots are also real.

InjectConstructorDefaultsArchitectureTest uses the full application source root and has existing synthetic rejection cases. RuntimeContractModuleImportRulesTest.kt:50-52 obtains the same real root and source files. RuntimeCoreCompositionOnlyTest reads the actual module Gradle files and compares declared main edges. WireVocabularyArchitectureTest and its support read real declared module sources and contain existing synthetic key/owner/token rejection cases. Their roots are not the defect here; their control-seam inventory is incomplete.

TypedParseBoundaryArchitectureTest.kt:9 invokes the inventory scanner. ArchitectureScanSupport.kt:180-182 reads actual named files. F-002 is function-level omission despite real file reads. The scanner must reject missing coverage and recognize extension/body forms rather than claim validity merely because its roots exist. No architecture tests were executed.

## Over-engineering register and what stays unchanged

Retain purpose-built outbound ports and their test substitutes, GoalLifecycleTelemetryEmitter and its fixture substitute, AgentRunGoalRunnerSubtaskLauncher, InstallAgentService, RuntimeOwnedPersistenceBoundary, reviewevidence pure diff parsing, shared presenter-to-contract mapping, inert application Path values, the engine-to-application implementation edge, and previously retained private WorkflowService helper/domain construction. These have current responsibilities or consumers.

Retain the two legacy migration ownership contexts. Sharing exact-number semantics and keys does not require a shared migration service. Retain installer plan/execution orchestration and existing installer ports. Its URL, bash, environment, and argv raise a host-ownership question, but moving them without preserving the rehearsal/execution contract would create a larger abstraction change than this evidence warrants.

Retain UpdateCheckText because both CLI and engine consume it. Retain public types required by inferred signatures or Kotlin-inject generation. Retain enum model field types and codecs; replace existing wire-token literals without an enum-typing migration. Retain explicit API policy as it stands.

Reject inbound use-case interfaces without substitutes, extra service wrappers around free functions, file-size-only splits, a new process framework, a new migration parser framework, new modules, dependency bags, architecture-test classes, and baseline expansion. No tests are owed for deletion of unreachable helpers or visibility narrowing alone. Existing regression, parity, and validator-backed coverage remains mandatory.

## Coordination with concurrent bundles

The final persistence check found two current siblings that were absent at the investigation baseline:

| Sibling | Overlap and owner | Sequencing |
| --- | --- | --- |
| SKILL-386 runtime-cli-architecture, committed remotely | Its CLI investigator owns command bags, registration, alias reuse, command-area mappers, CLI wire vocabulary, and the UninstallCommand format comparison. This bundle owns moving application uninstall rendering into that CLI area, exact acceptance decoding, review transport queries, and typed-parse coverage. | Land CLI-386 first, then recheck the uninstall caller before this cleanup. Preserve its output and registration decisions. Do not edit its bundle or repeat its findings. |
| Prose-output spec preparation, provisionally SKILL-387 | Its owner is this session's separate phase-plan run. It owns common PhaseOutput use, removal of agent-response schema gates, and spec-path handoff. Both bundles can touch typed-parse inventory, wire-vocabulary coverage, and review handoff call sites. | Land this application's scanner/acceptance cleanup before the prose migration, then let the prose migration remove retired phase-output entries while preserving coverage of surviving durable/control decoders. Recheck the final prose-spec key when it is persisted. No agent-response schema redesign belongs in this cleanup. |

No sibling untracked bundle existed locally at persistence. The remote sibling was read through git show without checkout. Archived bundles below are landed context, not pending execution dependencies.

There are no active or untracked sibling bundles and no live sibling owner was identified. Archived issue ownership is historical, not a reservation. Do not edit another session's bundle.

| Archived sibling | Overlap and current state | Owner and sequencing |
| --- | --- | --- |
| SKILL-347 | Earlier application ownership work. Removed dependency bags are absent; do not move the old bag under another name. | Archived owner. No pending dependency. |
| SKILL-370 | Most recent application investigation. Its bags, aliases, busy retry, ambient activity clock, orphan tests, and upward engine import repairs landed. Visibility retention still applies. | Archived owner. This bundle covers demonstrated residuals only. |
| SKILL-371 | CLI/shared scaffold input ownership. Current shared decoder and invocation surface exist; no wrapper reconstruction is proposed. | Archived owner. CLI uninstall rendering is the bounded remaining overlap. |
| SKILL-372 | Domain snapshots, accessors, aggregate rules, and exact numeric helper. Current domain source confirms relevant ownership. | Archived owner. Reuse its exact helper; acceptance decoders are residual application/adapter seams. |
| SKILL-373 | Core composition and guard ownership. Current core bindings and repoTest layout exist. F-002 identifies stale function registration despite real roots. | Archived owner. Extend existing guard support only. |
| SKILL-374 | Shared contract/key authority and inner API ownership. Current shared contracts exist. Legacy acceptance/control seams still lack complete ownership and coverage. | Archived owner. Reuse authority rather than introduce parallel strings. |
| SKILL-375 | MCP input and dispatch ownership. Current direct/shared surfaces remain. No MCP dispatch redesign is proposed. | Archived owner. Preserve MCP output bytes. |
| SKILL-377 | Ports, persistence sessions, and explicit adapter capabilities. Current non-null UnitOfWork and tested ports exist. Generic DiffResolverPort process transport remains. | Archived owner. Keep the port boundary and replace only its generic operation. |
| SKILL-378 | Engine/application dependency and journal ownership. Engine uses implementation(application); journal/agent scan ownership moved. | Archived owner. Adapt the one review diff consumer without moving runtime loops. |
| SKILL-384 | Execution/state authority, including commit 43fa555ac. Current loops and state ownership remain. | Archived owner. No loop or authority redesign. |
| SKILL-385 | Authoring discipline at the current HEAD. Future implementation must follow current comment, formatting, key, and test-value rules. | Archived owner. No source authoring occurred here. |

This inspection checks the relevant landed seams, not every unrelated archived acceptance criterion. No sibling is assumed to remain queued merely because its historical spec says so. Before future implementation, recheck changed paths and coordinate any newly active owner.

## Test obligations

1. F-001 data integrity. A malformed fractional or out-of-range numeric acceptance must not authorize another subtask. Extend existing goal-control and migration tests with exact-positive-ID boundary cases, valid integral records, and typed malformed failures. Include numeric strings as rejected legacy input and blank required acceptance fields. Preserve the existing valid once-only migration regression.
2. F-001 atomicity. A legacy record with a valid policy followed by malformed acceptance must leave no partial controls, consumed legacy state, completed migration marker, or published projection. Exercise real persistence through the existing SQLite migration suite and a core runtime-component or engine integration boundary. Do not add upward adapter dependencies to application tests.
3. F-002 enforcement. A selected function that moved or vanished must fail the scanner, and an extension function using a forbidden malformed-input reporter must be detected. Extend TypedParseBoundaryArchitectureTest. Assert coverage outcomes rather than parser implementation structure.
4. F-003 evidence integrity and cancellation. A failed or interrupted query must not publish a successful empty evidence artifact. A successful empty diff and intentional no-index exit-1 diff remain distinguishable valid outcomes. Cover this at adapter/review evidence boundaries, with observable results and interruption propagation rather than mock call ordering.
5. F-004 governed vocabulary. Extend existing synthetic seam/owner/token rejection coverage for the control paths. Keep schema parity and existing validator-backed regressions. Assert persisted field spellings/order through existing durable-record coverage, without recapturing baselines during implement or audit.
6. F-005 output compatibility. Extend existing CliUninstallRuntimeTest only where its current observable coverage does not already preserve confirmation, dry-run, success, and warning output bytes. Do not mirror string-building internals.

No new tests are planned for the unused-helper deletion or access-modifier changes. Existing compiler and architecture enforcement provide their relevant proof.

## Limits

Lexical references cannot prove inferred Kotlin signature closure, generated DI visibility, reflection consumers, or successful compilation. The final bounded feasibility pass reopened the relocation sources and all six cited architecture tests, traced their roots and file readers, checked adapter imports and the existing Kotlin-inject bindings, and confirmed the typed-parse selected-function omission. No domain relocation or new DI scope is proposed. Build owns compiler and generated-access proof; validate owns the named behavior regressions and routed checks.

The phase plan returned a complete draft with blocked status because its installed writer cannot emit investigation.md. The parent session persisted the returned preparation content as this four-file bundle under the user's authorization. The bundle preflight runs read-only after persistence. This recovery changes no runtime source and does not launch implementation.

Read-only preparation preflight completed with exit code 0 and verdict new_work. It found the manifest, one pending runnable subtask, and no running goal. No implementation was launched.
