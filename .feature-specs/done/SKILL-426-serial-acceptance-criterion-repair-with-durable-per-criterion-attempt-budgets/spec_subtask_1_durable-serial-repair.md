# SKILL-426 subtask 1: Durable serial repair admission and transactions

## Scope

Provide runtime-owned durable criterion accounting and callable transaction operations for the existing prose workflow. Agent findings and reports stay prose; the runtime supplies identity, order, cycle and counter facts. No new audit schema, handoff contract, semantic admission validator or dependency graph belongs to this foundation.

This file supersedes the structured-contract design on 2026-10-14. Subtask 1's earlier recorded completion and evidence remain history. Subtask 2 reconciles the actual code with this corrected boundary, including removal of incompatible schema machinery. Do not rewrite the completion record or claim that an operation test proves executor integration.

## Acceptance Criteria

1. S1-001. Durable accounting preserves original authored criterion identity, full text, spec order and workflow association. Composite criteria have one budget. These are runtime-owned facts, not fields an auditor must return. Covers AC-001 and AC-006.
2. S1-002. The runtime can select one current criterion in authored order and retain prose prerequisite explanations. Shared edits do not advance other criteria. Selection needs no dependency graph, graph validator or exact disposition list. Covers AC-001.
3. S1-003. Serial transitions retain one live repair cycle, count at most one failure for its outcome, block at three failures and preserve failures through success, restart and reopening. An empty focused queue requires final full source audit. Source acceptance does not discharge later test obligations. Covers AC-003, AC-005 and AC-006.
4. S1-004. Callable engine persistence uses the existing acquired-owner boundary to settle accounting, phase records, ledger, attempts and workflow changes together. It returns committed facts only after commit. A stale owner cannot reserve or settle, and rollback cannot return a successful closure. Production callers belong to subtask 2. Covers AC-004.
5. S1-005. Restart and replay recover the same criterion, live cycle, outcome and retained failures. Duplicate delivery does not count twice. Projection regeneration copies committed facts without replaying domain mutation. Authoritative accounting does not depend on pruned display history. Covers AC-004.
6. S1-006. Accounting uses the existing owning persistence path without a new schema family, agent-output validator, strict repair projection or required prose format. Reports remain prose in existing envelopes. Private implementation models do not become a new agent protocol. Covers AC-008.
7. S1-007. Existing outputs, aggregate attempts, ledger and checkpoints remain inspectable. Accounting never invents historical per-criterion attribution or clears known failures. Unknown attribution stays unknown and any prospective accounting start requires an explicit operator decision. No exact historical tuple allowlist or adoption receipt is required. Covers AC-008.
8. S1-008. A few observable boundary regressions cover retained budgets, duplicate outcomes, stale ownership, coupled rollback and projection-only recovery. Required writes propagate cancellation and report attributable failures. No schema/parity or exact-prose test is added for the discarded feature contracts. Covers AC-009 and AC-010.

## Non-goals

No executor activation, reader mutation authority, new command or second authoritative filesystem store. No historical aggregate-to-criterion migration or incident resume. Existing project architecture and unrelated transport contracts remain intact.

## Audit boundary

Inspect callable domain and persistence behavior against S1-001 through S1-008. Running workflow callers, focused prose delivery and final-audit routing belong to subtask 2. Earlier schema artifacts or historical producer captures are not acceptance evidence for the revised design.

## Dependency notes

Subtask 2 integrates useful operations and removes incompatible work already introduced by the superseded design. Subtask 3 reads committed context. Keep current manifest identities and runtime history rather than reopening or completing a subtask by editing documents.

## Implementation Details

1. Use the authored acceptance-criteria/catalog owner for identity and order. Keep only private state needed for current selection, retained failures, live cycle/outcome, final-audit requirement and references to existing prose reports.
2. Use existing engine persistence and the acquired worker capability to reserve and settle under the workflow's owner fence. Couple relevant phase, ledger, attempt and workflow writes in the same transaction. Return only committed facts.
3. Recover live cycles and retained outcomes from committed workflow state. Keep duplicate delivery once-only and filesystem recovery projection-only. Do not infer history from telemetry or aggregate prose counts.
4. Preserve existing compatibility and unavailable-history explanations. Remove requirements for strict evidence admission, graph coherence, adoption provenance or descriptor capture. The concrete cleanup of code already landed under those requirements belongs to subtask 2.
5. Reuse existing domain and isolated production-storage regressions for budget retention, replay, ownership and rollback. Add coverage only for a concrete behavioral gap.

Existing starting owners include FeatureTaskRuntimeAcceptanceCriteria, AcceptanceAuditCatalog, AcceptanceCriterionRepairState, AcceptanceCriterionRepairPersistence, RuntimeOwnedPersistenceBoundary, FeatureTaskRuntimePhaseStateRecorder and FeatureTaskRuntimeWorkerCoordinator. Fit the corrected behavior to their actual ownership instead of retaining an unnecessary owner because the previous spec named it.

The companion implementation_plan_subtask_1.md records checklist and historical evidence. Apply runtime architecture/code/observability guidance and preserve unrelated work in the workflow checkout.

## Validation strategy

No checks execute during respecification. Later owning phases run existing project checks and focused persistence regressions with disposable storage. Source audit does not require future build/test receipts. Remove tests whose only purpose is the discarded contracts while retaining unrelated guards and actual persistence regressions.

## Next path

The existing subtask completion remains recorded. Subtask 2 performs reconciliation and executor integration after refreshed planning. This spec change neither resumes nor resets any workflow.
