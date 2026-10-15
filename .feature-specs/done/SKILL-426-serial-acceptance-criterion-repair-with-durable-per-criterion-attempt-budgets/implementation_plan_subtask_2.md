# Subtask 2 execution checklist and test obligations

## Boundary ownership

The executable spec is spec_subtask_2_serial-audit-execution.md. This companion adds no delivered handoff source. Existing owners stay in charge.

| Work | Digest-backed owners |
| --- | --- |
| Strategy/modes/results | runtime-engine featuretask/slot/audit/AcceptanceAuditStrategy.kt, AcceptanceAuditRound.kt, AcceptanceAuditVerdictRule.kt, AcceptanceAuditLoopRules.kt, AcceptanceAuditHistory.kt and AcceptanceAuditResumeRules.kt |
| Prompts/completeness | AcceptanceAuditPromptSections.kt; planning/AuditPlanFixPromptSections.kt; AuditImplementFixPromptSections.kt and AuditImplementFixStep.kt |
| Declared handoff | runtime-domain phase/task/FeatureTaskRuntimePhaseWorkflowProjectionDeclarations.kt; model/handoff/assembly/FeatureTaskRuntimeHandoffProjectionInputs.kt; handoff/FeatureTaskRuntimeHandoffProjectionValueBuilder.kt; engine phase/briefing/FeatureTaskRuntimeBriefingProjectionInputsMapper.kt |
| Routing/gates | domain FeatureTaskRuntimePhaseWorkflowDefinition/Transitions; engine runloop/core/FeatureTaskRuntimeRunLoopDrive/Transitions; state/FeatureTaskRuntimeRunState/Reconstruction and RunTransitionOwner |
| Attempts/settlement | slot/attempt/PhaseAttemptLoop/Continuations; PhaseStepHookContexts; runloop/attempt/RunLoopAuditSettlement/HookViews; durable/RunLoopDurableState; transport PhaseSettlementService |
| Status/validation | engine FeatureTaskRuntimeStatusModels/RunModels/StatusService, existing validationGateExecutionEvidence acceptance owner and detached serial facts from subtask 1 |

Abbreviated engine paths are under runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask; domain paths are under runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime. Implement confirms exact integration seams without inventing new authority.

## Ordered checklist

1. Confirm subtask 1's actual typed operations/versions and required architecture documents. Preserve the branch policy already resolved by the runtime.
2. Add declared bounded initial/current/final projections with required catalog/execution/current-AC/reservation/tree identities; version phase handoff 0.3 with explicit 0.2 admission and preserve envelope/private storage versions unless shape changes.
3. Supply current original text/all composite gaps/prerequisites/prior outcomes/budget/position to plan and repair. Deliver full catalog-bound admission/final requirements through their declared projections. Bound references; do not substitute raw phase prose as authority.
4. Integrate coupled reserve-before-effects and exact runtime acceptance into existing attempt/settlement owners. Remove serial whole-set permission and nested semantic repair retries. Keep genuine process/output/build/review/monitor protections and no-change ceremony.
5. Route initial discovery to queue admission, focused plan to mutation, complete repair to targeted audit, failure to retained-budget planning/block and empty queue to final-full. Marker alone never closes an AC.
6. Bind output to immutable accepted outcome identity/digest and post-repair tree; update memory/reporting only after authoritative commit. Reuse the live reservation after crash when evidence proves its stage; refuse ambiguity.
7. Enforce catalog/current-tree final proof at review, checkpoint, explicit resume, hydrated completion, carry-forward and no-change bypasses. Reopen only demonstrated final-boundary regressions and immediately block exhausted budgets.
8. Activate semantic revision 4 and supported pristine adoption together, inspecting raw history before normalization. Integrate validation-owned deferred obligation resolution without fabricating receipts or source completion.
9. Add executor-side read-only budget-block facts and meaningful separate phase history. Author the sparse runner/handoff tests below; update shared fixtures to structured mode-specific results.

## Accounting decision table

| Accepted event | Reservation and unsuccessful budget | Next source action |
| --- | --- | --- |
| Initial discovery or read-only planning | No reservation or failure consumed | Admit queue or plan current AC |
| Mutating repair launch | One durable live cycle reserved before effects | Repair current AC |
| Genuine pre-output process failure | Retain live reservation; no source failure invented | Existing process protection/recovery only |
| Invalid output requiring correction | No accepted source outcome; retain binding | Existing bounded output correction or refusal |
| Validated incomplete/retryable blocked repair | Finalize same reservation with one unsuccessful outcome | Focused planning if used below three, otherwise block; no targeted verification |
| Complete repair | Same reservation awaits targeted result at admitted post-tree | Targeted audit |
| Accepted targeted failure | One unsuccessful outcome for same reservation, once | Focused planning or third-failure block |
| Accepted targeted success | Close only current AC once; retain previous failures | Next queued AC or final-audit-required |
| Empty queue | No new repair reservation | Final-full audit over production catalog |
| Accepted final regression | Reopen demonstrated ACs with retained identities/history/budgets | Current AC repair, or immediate exhausted block |
| Accepted final-full pass | Persist tree/catalog-bound final proof | Existing review and quality sequence; validation obligations remain separate |

Repeated identical accepted events are observations of committed facts. Conflicting/stale events refuse. A reservation is not a failed attempt; unsuccessful outcomes are. No phase semantic retry multiplier applies to source repair.

## test_obligations

| Named realistic bug | Observable boundary assertion | Criteria |
| --- | --- | --- |
| Shared/prerequisite repair skips another queued AC | Runner records focused launch/current-AC order for dependent/shared work; only current accepted targeted result closes, second AC remains queued | AC-002; S2-001/S2-002 |
| Incomplete repair relaunches free or waits for verification it cannot supply | Runner observes one unsuccessful outcome per incomplete/blocked repair, no targeted launch, and third-failure block with no fourth mutating launch | AC-003; S2-002/S2-003 |
| Phase semantic retries multiply a three-failure AC budget | Runner drives unsuccessful repairs through existing attempt paths and observes exactly three source failures and blocked current AC, while unrelated process/output protection remains distinguishable | AC-003; S2-003 |
| Duplicate targeted delivery counts twice or success erases earlier failures | Runner accepts failure replay once, then success and next-AC advancement once with earlier count retained; conflicting reservation output refuses | AC-003, AC-004; S2-002/S2-003 |
| Restart/reset creates another reservation or resets exhausted budget | Recreate runner with persisted repository after reservation/awaiting-targeted/failure stages; same reservation/count/current AC remains, absent source outcome never fabricates success | AC-004; S2-003 |
| Final audit cannot reopen a real regression or gives an exhausted AC another mutation | Runner final-full result reopens only demonstrated AC, preserves consumed budget and refuses exhausted repair; unaffected criteria retain closure | AC-005; S2-004 |
| Review or hydrated completion trusts stale prose/final tree | Seed/resume supported evidence paths and observe no review/completion until exact catalog/current-tree final-full proof, including no-change and carry-forward gates | AC-005; S2-004 |
| Source-satisfied mixed/test-only AC appears complete without validation | Runner/status show source/validation axes and pending deferred counts; only matching validation-owned accepted evidence resolves obligation, goal-child-build alone does not | AC-006; S2-005 |
| Wrong-current-AC/tree or oversized handoff reaches an agent | Actual declared projection validator/briefing boundary rejects max-plus-one bytes/items, mismatched binding/unknown fields and allows valid bounded projection | AC-002, AC-008, AC-010; S2-001 |
| Legacy normalization hides a broad repair and adopts it | Runner admission refuses preserved prior repair/in-flight evidence before normalization; supported stopped-pristine run obtains provenance then fresh explicit discovery | AC-008; S2-007 |

Use FeatureTaskRuntimeAuditProgressRegressionTest, AuditFixPlanningTest, AuditTestSupport and RunnerTestSupport; reuse runtime harness launch order/events and persisted repository. Use HandoffProjectionValidatorTest/HandoffContractTest/PhaseBriefingBudgetTest for governed boundary rejection. Production transaction behavior is already covered by subtask 1; do not reimplement SQLite semantics in fakes. Every new test states the concrete bug it catches before its body is authored. Update tests that encoded permanently closed/whole-set shrinking policy to assert the new real safety boundary, preserving unrelated past bug regressions.

## Coverage and validation ownership

S2-001/S2-007 cover focused handoffs/prompts for AC-002 and AC-010. S2-002/S2-003 deliver AC-003 and connect authoritative AC-004. S2-004 supplies every AC-005 gate. S2-005 supplies execution AC-006. S2-006 provides executor budget blocks for AC-007. S2-007 activates AC-008 adoption semantics; S2-008 supplies corresponding AC-009 regressions and retained guard authority.

No command execution belongs to this checklist's source work. tests_executed is empty. Only build runs pack buildability proof and only validate runs all required full checks. Review/validate may repair production wiring/test setup/bodies/lint/format without weakening budgets or authority. Source audit cannot run builds/tests/generators or mint validation receipts. Runtime owns later review/history/one commit/checkpoint/PR/monitor.
