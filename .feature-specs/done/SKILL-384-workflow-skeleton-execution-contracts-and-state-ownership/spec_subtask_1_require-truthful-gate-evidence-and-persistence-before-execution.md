# SKILL-384 Subtask 1 - Require truthful gate evidence and persistence before execution

Parent spec: [.feature-specs/SKILL-384-workflow-skeleton-execution-contracts-and-state-ownership/spec.md](spec.md)
Issue key: SKILL-384

## Scope

Implement findings 1, 2, and 3 as one executable-safety change. Own runtime-engine featuretask slot/skeleton/SkeletonStrategyBindings.kt, slot/qualitygate, validation command policies and coordinators, slot/attempt/PhaseAttemptOnce.kt and PhaseLaunchPreparation.kt, runloop/output/FeatureTaskRuntimeRunLoopOutputPersistence.kt, phase/record, and the existing runtime-owned build and commit entry paths. Own affected runtime-domain validation evidence, runtime-ports recorder and validator boundaries, runtime-infra/contracts receipt validation, canonical receipt YAML under orchestration/contracts, composition wiring, and the affected observability documentation.

Add an explicit pack-validation strategy for SkeletonDefinition.VALIDATION. It owns PHASE_VALIDATE, reports the validation gate, and settles through the existing validation-result and validation-evidence family. Keep goal-child PackBuildStrategy on PHASE_BUILD and build_receipt. Share command-cycle mechanics only where their semantics agree. Select the full validation command pair from the dominant manifest and retain existing wrapper override resolution. Do not switch on definition in PhaseRunEntry or add a run loop. Keep existing agent-validation alternatives and repair ownership.

Use a typed required-write failure at the owning persistence seam, handled by the shared attempt boundary. A false required write cannot return as ordinary success. Inventory ordinary launches, review launches, repair and triage launches, planning fan-out, gate commands, and runtime-owned side effects. Apply the prerequisite at each route without moving transaction or lease ownership. In-memory writes acknowledge their intentional ephemeral success. Audit briefings remain in memory.

Migration and recovery: tighten the canonical build receipt and shared gate-evidence coherence. Unsupported schema versions loud-fail into existing quarantine. Preserve real successful cached-command evidence, including zero executed work units. A bounded legacy conversion may preserve a structurally old nonempty receipt only when its original command, outcome, exit status, checkpoint, and applicable policy evidence prove current semantics. It may not invent missing fields. Empty successful receipts are invalid evidence. Quarantine them without clearing downstream completion, commit, push, checkpoint, or terminal history records. Regenerate evidence only at an existing safe gate boundary with compatible identity and proven absence of downstream effects. Until subtask 2 establishes that identity check, uncertain legacy recovery blocks with a typed operator-recovery reason. Completed runs remain terminal.

Follow runtime-kotlin/ARCHITECTURE.md and docs/code-principles.md. Contract changes start in canonical YAML and include owning versions and keys, typed failures, parity coverage, and all producer and reader seams. Use existing diagnostics with bounded identifiers and reasons, without payload bodies. Preserve unrelated working-tree edits.

## Acceptance Criteria

1. S1-AC1. SkeletonStrategyBindings selects an explicit pack-validation strategy for SkeletonDefinition.VALIDATION. Its source owns PHASE_VALIDATE and validation evidence, while goal-child BUILD retains PHASE_BUILD and build_receipt. PhaseRunEntry contains no definition-specific dispatch branch and both use the existing shared run loop.
2. S1-AC2. Validation command policy selects collect_all_full_gate_command for discovery and cache_bypassing_collect_all_full_gate_command for verification. Build policy selects only build_command and cache_bypassing_build_command. Both resolve the dominant manifest dynamically and preserve the existing wrapper override behavior.
3. S1-AC3. PhaseValidationRunTest and focused gate-dispatch coverage contain distinct fixture argv for all four commands, assert exact effective argv through discovery and repair verification, assert truthful validation telemetry, and assert that standalone validation creates neither a workflow row nor a commit. Goal-child build coverage asserts that no full-validation command is dispatched.
4. S1-AC4. Missing required gate declarations or required command members produce typed blocked outcomes and bounded diagnostics in durable and in-memory routes. No required-gate absence branch constructs successful empty evidence or silently switches to an optional skip or substitute agent-run gate.
5. S1-AC5. Successful gate evidence requires at least one actual successful applicable command record with command identity, zero exit status, and the relevant repository checkpoint. Settlement uses the terminal required command result, so an earlier success cannot mask failed verification. Discovery failure and later successful repair verification remain distinct records.
6. S1-AC6. Receipt schemas and coherence validators distinguish command execution from executed work. A real successful cached invocation may have zero executed work units and no newly executed checks. Zero gate runs cannot prove success. Counts match record collections, aggregate checks match recorded checks, and unknown or missing execution evidence cannot normalize to passed.
7. S1-AC7. Receipt version changes have an owning Kotlin version and wire keys, typed parse failures at every changed seam, parity coverage, and documented legacy handling. Recovery code preserves original evidence and finalization records, refuses unsafe regeneration, and cannot reopen a completed run or invoke commit/push to repair evidence.
8. S1-AC8. Required start persistence succeeds before a child, gate command, or phase side effect executes. Required briefing persistence succeeds before child launch. The owning persistence seam raises a typed rejection that the shared attempt boundary handles, with no discarded Boolean result or forwarding-only abstraction.
9. S1-AC9. Persistence rejection retains its write kind, phase, and attempt attribution. When storage remains available, terminal persistence records that rejection rather than a child failure. Secondary terminal-write or diagnostic failure preserves the original rejection. Existing exceptions and cancellation retain their propagation semantics.
10. S1-AC10. Failure-injection coverage rejects start and briefing writes independently and asserts zero launches and prohibited side effects. It also asserts the terminal reason when storage remains available, primary-error preservation, cancellation propagation, intentional in-memory success, and audit launch without durable briefing storage.

## Non-Goals

- Do not narrow all strategy capabilities in this subtask.
- Do not introduce semantic execution identity or a second traversal authority.
- Do not replace agent-validation behavior outside the explicit standalone pack-validation binding.
- Do not add an optional required-gate skip policy.
- Do not rename compatible durable step IDs, redesign unrelated operations, or change source skills.

## Dependency Notes

Depends on: none
No earlier subtask is required. This commit fixes command dispatch, evidence, and execution prerequisites together and is independently reviewable. Subtask 2 supplies the durable compatibility prerequisite for safe historical receipt recovery. Until then, absence of sufficient historical identity blocks recovery rather than guessing. Include a concise recovery decision table in the owning documentation, distinguishing valid cached success, unsupported receipt versions, empty success, downstream finalization, and uncertain side effects.

## Validation Strategy

Author tests during implementation; execute them only in validate. Extend PhaseValidationRunTest for the concrete regression where standalone validation invokes compile argv while reporting validation. Use distinct synthetic argv and assert the wrapper override's final dispatched command, including repair verification. Extend existing build coverage for accidental full-suite dispatch from BUILD. Add one evidence/coherence matrix for fabricated empty success, failed terminal verification masked by earlier success, valid zero-work cached success, and count/check inconsistency. Retain contract parity tests. Add a receipt-recovery boundary test whose wrong behavior is erasing completed finalization or issuing a duplicate commit/push while replacing a receipt. Add shared-attempt failure-injection tests whose wrong behavior is any launch or side effect after rejected start or briefing persistence; assert terminal attribution as well as execution counts. Use separate cases only for materially distinct branches such as audit exemption, cancellation, or terminal-storage failure. Validate runs the relevant engine, domain, contract, and persistence suites through the pack full gate. Only a runtime-selected build phase uses the manifest compile commands. No test or build execution is required by these acceptance criteria.

## Next Path

skill-bill goal SKILL-384

## Spec Path

.feature-specs/SKILL-384-workflow-skeleton-execution-contracts-and-state-ownership/spec_subtask_1_require-truthful-gate-evidence-and-persistence-before-execution.md
