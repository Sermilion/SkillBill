## [2026-10-04] SKILL-399 decomposition-manifest and bundle-journal failure codes
Areas: runtime-contracts/error/shellcontent, runtime-contracts/contracts/decomposition, runtime-domain/workflow/decomposition, runtime-application/decomposition/review/spec, runtime-engine/goalrunner/preflight, runtime-infra/contracts/workflow/decomposition, runtime-infra/workflow/decomposition/featuretask, runtime-core architecture baseline and decomposition tests
- Replaced InvalidDecompositionManifestSchemaError and InvalidDecompositionManifestBundleJournalError with coded SkillBillRuntimeException message functions.
- Reused DecompositionManifestValidationFailureCode for existing schema vocabulary. WorkflowFailureCode owns five additional manifest conditions and one entry per bundle-journal failure literal.
- Followed guarded code classification. isDecompositionManifestSchemaFailure accepts domain validation codes and the five manifest conditions, excludes journal codes, and rethrows unrelated failures.
- Reusable: the manifest classifier, message functions and Rejected.failure carrier preserve shared failure handling across domain, application, engine and infrastructure consumers.
- Schema validation carries the original failure through Rejected; requireAccepted rethrows it without rebuilding its message or cause. Rejections without a carried failure still construct a coded error.
- Converted existing preflight, schema, journal and writer assertions to codes and removed the two deleted-class baseline rows. Domain remains free of filesystem and ports dependencies.
- Breaking change: deleted exception types have no aliases. User-visible messages, causes, persisted payloads, contract versions, journal containment and recovery behavior retain their existing contracts.
- Limit: this entry covers subtask 8. Legacy exception bases and codeless transition support remain while other subclasses or callers exist.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-10-04] SKILL-399 workflow state and record failure codes
Areas: runtime-contracts/error/shellcontent, runtime-domain/workflow/goalrunner, runtime-ports/workflow, runtime-application/work/workflow, runtime-engine/featuretask/goalrunner/work, runtime-infra/contracts/sqlite/workflow/skills, runtime-core architecture baseline and persistence tests
- Replaced eight workflow state, work-list, issue-key, retired-prose, output-schema and verification-cap exception classes with WorkflowFailureCode and coded SkillBillRuntimeException message functions.
- Registered WorkflowFailureCode in shell-content classification. The workflow-state predicate handles the state code and preserves checkpoint-version membership from subtask 6.
- Followed exact code checks and guarded rethrows. Merged IDE status and rejected-output recorder catches retain their existing result branches and degradation handling.
- Reusable: WorkflowFailureCode, message functions and the workflow-state predicate provide shared failure handling for domain, ports, application, engine and infrastructure consumers.
- Converted existing exception assertions to code assertions and removed only the eight owned custom-throwable baseline rows.
- Breaking change: removed exception types have no aliases. User-visible messages, causes, stored payloads, contract versions, transaction rollback and fallback behavior remain unchanged.
- Limit: this entry covers subtask 7 only. The two decomposition-manifest exceptions belong to subtask 8; legacy bases and codeless support remain while other subclasses or callers exist.
Feature flag: N/A
Acceptance criteria: 3/3 implemented

## [2026-10-04] SKILL-399 feature-task runtime evidence and record failure codes
Areas: runtime-contracts/error/shellcontent, runtime-domain/workflow/taskruntime/goalreview, runtime-engine/featuretask/goalrunner/verify/work, runtime-application/workflow, runtime-ports/taskruntime/featuretask, runtime-infra/contracts/sqlite/workflow, runtime-core architecture baseline
- Replaced sixteen evidence, record, execution-identity, worker-ownership and operator-rejection exception classes with FeatureTaskRuntimeFailureCode and coded SkillBillRuntimeException factories.
- Moved receipt anchoring, review-state wrapping and gate-integrity failure context into decode callbacks. Handoff persistence and shared-evidence validators return violation reasons for contextual failure or recorded degradation.
- Followed exact code checks and guarded rethrows. isInvalidWorkflowStateFailure preserves checkpoint-version handling at former workflow-state catches, including checkpoint remediation refusal.
- Reusable: FeatureTaskRuntimeFailureCode, message factories, the workflow-state predicate and the wire-validator violation API provide shared failure handling across domain, engine and infrastructure consumers.
- Converted exception assertions and pinned handoff validator labels to codes, and removed only the sixteen owned custom-throwable baseline rows.
- Compatibility: removed exception types have no aliases. Message templates, receipt anchors, repair guidance and degradation reasons retain their existing meaning; persisted contract versions and payload shapes do not change.
- Breaking interface change: FeatureTaskRuntimeWireArtifactValidator implementations must supply the abstract violation operation. Build-receipt factories no longer carry unread failureCode or payloadFreeReason properties.
- Limit: this entry covers subtask 6 only. Workflow-class conversion belongs to subtask 7; legacy bases and codeless support remain while other subclasses or callers exist.
Feature flag: N/A
Acceptance criteria: 3/3 implemented

## [2026-10-03] SKILL-399 install, schema and configuration failure codes
Areas: runtime-contracts/error/shellcontent, runtime-infra/skills/contracts/host, runtime-application/config, runtime-domain/goalrunner/workflow, runtime-engine/featuretask, runtime-cli, runtime-mcp, runtime-core architecture baseline and install tests
- Replaced sixteen Install exception classes with InstallFailureCode and SkillBillRuntimeException construction. Producers retain message text and causes across schema validation, configuration, install persistence and reconciliation.
- Unreadable repo-local config and malformed machine config share REPO_LOCAL_CONFIG_FAILURE. Malformed repo-local config retains its own code.
- Followed exact code checks and rethrowUnless at converted handling boundaries. InstallFailureCode joins shell-content classification; record and baseline wrapping reuse failures only when their codes match.
- Reusable: InstallFailureCode and message factories provide shared failure vocabulary for infrastructure producers, domain decoders and application consumers.
- Converted existing exception assertions to exact code assertions and removed only the sixteen owned custom-throwable baseline rows.
- Compatibility: deleted exception types have no aliases; converted diagnostic class labels use failureCodeLabel. Persisted schemas, payloads, recovery and quarantine behavior retain their current contracts.
- Limit: this entry covers subtask 3 only. Goal-planning preparation declarations remain for subtask 4, receipt re-wrap cleanup belongs to subtask 6, and legacy transition support remains while other subclasses or codeless callers exist.
Feature flag: N/A
Acceptance criteria: 3/3 implemented

## [2026-10-03] SKILL-399 scaffold and review-context failure codes
Areas: runtime-contracts/error/shellcontent/scaffold, runtime-domain/scaffold/review, runtime-application/scaffold/review, runtime-engine/featuretask, runtime-ports/review, runtime-infra/skills/contracts/workflow, runtime-cli, runtime-core architecture baseline, runtime-mcp and runtime-infra/sqlite tests
- Replaced ten Scaffold and ten ReviewContext exception classes with owner failure-code enums and message functions. Producers across the affected modules now construct coded SkillBillRuntimeException failures.
- Review schema extraction, launch handling and aggregation branch on exact codes. Install staging still rethrows skill-content identity mismatches before converting other staging failures into issues.
- Followed narrow code-based handling with rethrowUnless. ReviewContextFailureCode joins shell-content classification; ScaffoldFailureCode remains outside it.
- Reusable: ScaffoldFailureCode, ReviewContextFailureCode and message functions preserve producer context, message text and causes across modules.
- Converted existing exception assertions to exact code assertions and removed the twenty owned custom-throwable baseline rows.
- Compatibility: scaffold stdout, stderr and exit code remain unchanged. Deleted exception types have no aliases; converted diagnostic class labels use failureCodeLabel.
- Limit: this entry covers subtask 2 only. Legacy transition support remains while other subclasses or codeless callers exist; persisted contracts and payloads do not change.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-10-03] SKILL-399 manifest and skill-staging failure codes
Areas: runtime-contracts/error/shellcontent, runtime-domain/install/review/scaffold, runtime-engine/featuretask, runtime-infra/skills/contracts/launcher/workflow, runtime-cli, runtime-application tests, runtime-core architecture baseline
- Replaced eight Manifest and twelve SkillStaging exception classes with owner failure-code enums and message functions. Producers now construct coded SkillBillRuntimeException failures.
- Added both enums to shell-content classification. Manifest-schema wrapping and skill-shape validation handle their exact codes and rethrow other failures; telemetry preserves its existing category mapping.
- Followed owner-code discrimination without reading removed exception properties. Shared schema-loader callbacks accept SkillBillRuntimeException while retaining shell-content guards.
- Reusable: ManifestFailureCode, SkillStagingFailureCode and message functions preserve producer context, causes and operator repair guidance across modules.
- Converted existing exception assertions to exact codes and removed the twenty owned custom-throwable baseline rows.
- Compatibility: user-visible messages remain unchanged; manifest ERROR_TYPE and sidecar causeClass labels now identify their owner codes.
- Limit: this entry covers subtask 1 only. Other shell-content families and legacy exception transition support remain until all remaining subclasses and codeless callers are gone.
Feature flag: N/A
Acceptance criteria: 3/3 implemented

## [2026-10-01] SKILL-391 runtime-contracts kernel ownership cleanup
Areas: runtime-contracts/{error,learning,validation}, runtime-engine/operation/core, runtime-mcp/shared, runtime-infra contracts repoTests
- Moved InvalidMcpToolArgumentError out of the kernel into an internal class in runtime-mcp shared; the kernel error/core package keeps only shared failure types.
- Moved the 28 operation error classes into an internal OperationErrors file in runtime-engine operation/core; the kernel keeps only OperationUsageError (file renamed to match for detekt MatchingDeclarationName).
- Narrowed visibility of LearningSummaryWire, ValidationReportPayloadKeys and the REVIEW_* constants to private; removed the Array failureWireByValue overload (EnumEntries remains).
- Phase-output schema error now defaults its failure code to the contract-owned SCHEMA_INVALID wire value; MCP adapter reuses the public NO_APPLIED_LEARNINGS constant.
- Pattern: kernel holds shared wire vocabulary only; adapter- and engine-specific errors live with their owning module. reusable
- Four repoTests were repackaged out of the skillbill.contracts package so only runtime-contracts declares it; two others were already deleted by SKILL-387.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-09-16] SKILL-349 subtask 2 — Simplify contract implementation
Areas: runtime-contracts/{time,error,install,scaffold}, runtime-application/workflow, runtime-domain/taskruntime, runtime-infra-fs/phaseoutput, runtime-core/architecture, runtime-kotlin docs
- `JvmSystemClock` delegates to a live UTC millisecond JDK clock while domain consumers retain injected `java.time.Clock`.
- `WorkflowContracts` was folded into `WorkflowWireProjections`, preserving payload order, null omission, continuation mode, and extra-field precedence without another map pass.
- Phase-output failure tokens now have one contract-owned enum; validators and conformance consumers derive classification and unknown-token rejection from it.
- Unused diagnostics, helpers, install convenience declarations, and failure lookup residue were removed after caller checks; documentation records the resulting ownership and diagnostic-deletion decision.
- Pattern: keep wire vocabulary and failure classifications at their contract owner, with application mapping as the single projection seam. reusable
- Known limitation: no compatibility aliases or ambient-time domain access were added; existing external error behavior remains unchanged.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-09-16] SKILL-349 preserve contract input meaning
Areas: runtime-contracts, runtime-application/updatecheck, runtime-infra-sqlite telemetry/review/workflow, orchestration contract schemas, runtime-kotlin architecture
- Preserved exact numeric and JSON values at canonical contract boundaries, with typed failures for wrong roots, types, and strict arrays.
- Migrated decomposition, scaffold, update-check, telemetry, review, and workflow consumers so malformed content is explicit while omitted data retains its meaning.
- Followed lightweight packaged-resource validation and owning wire-key/error vocabulary; documented numeric and failure-policy boundaries.
- Reusable: shared packaged YAML number helpers and a strict JSON array entry point.
- Known limitation: issue-key schema retains scalar minLength/maxLength while its loader enforces Int-backed bounds.
Feature flag: N/A
Acceptance criteria: 8/8 implemented
