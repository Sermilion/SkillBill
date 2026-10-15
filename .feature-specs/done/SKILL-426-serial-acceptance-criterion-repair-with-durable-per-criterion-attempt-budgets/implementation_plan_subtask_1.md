# Subtask 1 execution checklist and test obligations

## Boundary ownership

The executable spec is spec_subtask_1_durable-serial-repair.md. This checklist follows the operator's prose correction of 2026-10-14. Earlier completion remains recorded; subtask 2 reconciles incompatible implementation already produced. This file does not authorize reopening, resetting or resuming a workflow.

| Work | Starting owners |
| --- | --- |
| Criterion identity/order | runtime-domain workflow/taskruntime/model/audit/FeatureTaskRuntimeAcceptanceCriteria; runtime-engine featuretask/slot/audit/AcceptanceAuditCatalog |
| Private accounting | runtime-domain workflow/taskruntime/model/audit/AcceptanceCriterionRepairState, simplified to runtime-owned facts |
| Required settlement | runtime-engine featuretask/persist/acceptance/AcceptanceCriterionRepairPersistence; persist/RuntimeOwnedPersistenceBoundary; phase/record/FeatureTaskRuntimePhaseStateRecorder |
| Acquired ownership | featuretask/lifecycle/core/FeatureTaskRuntimeWorkerCoordinator and existing run admission |
| Recovery | Existing durable workflow artifacts and projection recovery, without a separate agent contract |

Paths abbreviate runtime-kotlin module source roots. Named owners are starting points, not a reason to preserve an unnecessary abstraction.

## Ordered checklist

1. Retain authored criterion identity, full text and spec order. Keep composite criteria intact. Agents receive this context in prose and do not reproduce an exact catalog response.
2. Retain only runtime accounting needed for current focus, live cycle/outcome, failures, final-audit requirement and references to existing prose. Drop graph/disposition/evidence-receipt requirements.
3. Reserve and settle under acquired ownership in the existing transaction, coupling phase, ledger, attempts and workflow changes. Return accepted facts after commit only.
4. Preserve counts and live cycles on restart. Duplicate outcomes count once. Projection regeneration does not repeat domain mutations.
5. Preserve historical aggregate attempts and unknown attribution. No descriptor capture, fixed historical allowlist or adoption receipt is required. Do not reconstruct per-criterion failures or silently clear known counts.
6. Reuse meaningful domain/production-storage regressions. Subtask 2 removes discarded contract code and its dedicated tests while preserving actual accounting/ownership regressions.

## test_obligations

Name the realistic bug before adding a test and reuse existing coverage where it proves the outcome.

| Realistic bug | Observable assertion | Criteria |
| --- | --- | --- |
| Success or reopening grants another full allowance | Previous failures survive closure and regression; third failure blocks | S1-003 |
| Restart or duplicate delivery charges twice or resets failures | Reopened storage recovers the same cycle and count; replay leaves count unchanged | S1-005 |
| A stale worker settles after takeover | No accounting, phase, ledger or workflow advancement by stale ownership | S1-004 |
| A late write failure leaves a visible successful closure | Coupled state rolls back and no successful result escapes | S1-004 |
| Projection regeneration repeats committed outcome | Display recovers without another failure or workflow transition | S1-005 |

Use existing isolated production SQLite/public-port fixtures with disposable homes and injected time. Retain cancellation and real past-bug coverage. No audit-schema, graph-validation, historical-tuple or exact-prose test is added.

## Coverage and handoff

S1-001/S1-002 cover identity/order and selection. S1-003 covers accounting/final-audit foundations. S1-004/S1-005 cover transaction/recovery. S1-006/S1-007 preserve prose and historical evidence without new contracts. S1-008 carries behavioral coverage and diagnostics. Subtask 2 connects the running executor and removes incompatible code from the prior design.

## Validation ownership

Respecification executes no runtime checks. Existing build, review and project validation phases retain their normal ownership. Tests use disposable data. Earlier test results are historical evidence, not checks of the corrected design.

## Direct repair evidence, 2026-10-14

The following record is preserved verbatim as historical evidence of work under the superseded structured-contract design. It supplies no current requirement for adoption allowlists, schemas or strategy revision 4 and does not prove the revised criteria. No checks described below were rerun during respecification.

> The earlier claim that S1-001 through S1-008 were all satisfied is withdrawn. The former allowlist reconstruction did not prove the exact historical standalone shape. Subtask acceptance requires a fresh runtime audit against the corrected ownership boundary. Strategy revision 4 and executor activation remain in subtask 2; reporting remains in subtask 3.
>
> The nine descriptors under `runtime-engine/src/test/resources/featuretask/serial-adoption/` now come from the unnormalized production producers at the immutable commits in `provenance.json`. The checked-in `capture.py` rebuilt all three commits in disposable checkouts and matched every descriptor's SHA-256 identity. It uses neither normalized archives nor current-policy reconstruction. Original workflow history is separate evidence and remains mandatory at adoption.
>
> Direct repair also addresses prior-owner stop proof, explicit 0.6 output admission with original-version preservation, projection locking, stale/conflicting projection refusal and corrupt-cache recovery. Focused adoption, persistence and projection tests passed. Full repository checks belong to the direct repair's validation evidence; their success does not mark any criterion accepted or reset runtime budgets. The runtime owns the next audit, durable subtask completion and commits.
