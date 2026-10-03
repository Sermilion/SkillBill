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
