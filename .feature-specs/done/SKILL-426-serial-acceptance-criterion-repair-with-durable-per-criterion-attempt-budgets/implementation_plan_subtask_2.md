# Subtask 2 execution checklist and test obligations

## Boundary ownership

The executable spec is spec_subtask_2_serial-audit-execution.md. This checklist supersedes the structured-evidence plan on 2026-10-14. It includes cleanup because subtask 1 already introduced incompatible contract machinery. Preserve partial edits and reuse useful persistence work; do not treat the old design as an immutable requirement.

| Work | Starting owners |
| --- | --- |
| Prose strategy/verdict | AcceptanceAuditStrategy, AcceptanceAuditRound, AcceptanceAuditVerdictRule, AcceptanceAuditHistory and AcceptanceAuditResumeRules |
| Focused briefings | AcceptanceAuditPromptSections, AuditPlanFixPromptSections, AuditImplementFixPromptSections and AuditImplementFixStep |
| Routing/acceptance | Existing RunLoopDrive/Transitions, RunState/Reconstruction and FeatureTaskRuntimeRunTransitionOwner |
| Attempt/settlement | PhaseAttemptContinuations, existing audit hook/settlement owners and phase records |
| Durable writes | AcceptanceCriterionRepairPersistence, required workflow persistence and acquired worker capability |
| Readable status | Existing FeatureTaskRuntimeStatusService and block/report text |

Engine paths are under runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask. Domain catalog/phase owners stay in runtime-domain. No new protocol or authority is introduced.

## Ordered checklist

1. Delete superseded acceptance-criterion audit-result, repair-handoff, repair-state and adoption schema files, including the adoption 0.1 snapshot. Remove dedicated validator/version/resource dispatch, strict admission and dependency graph requirements, and historical tuple/adoption gates. Delete code/tests used only by that machinery rather than moving it. Keep useful accounting and actual transaction/recovery tests, unrelated schema guards and recorded workflow/incident evidence.
2. Use existing phase result/verdict and prose audit interpretation. Let the runtime choose and bind current criterion and cycle. Pass original criterion text, prose findings, relevant prior reports and allowance through existing briefing channels. Do not require a new marker, structured payload or exact field format.
3. Carry acquired ownership from the existing partial startup changes into reserve/settlement. Couple accounting with phase records, ledger, attempts and workflow state in the existing required transaction. Do not accept success in memory or emit it before commit.
4. Route focused planning/repair/audit in authored order. Do not consume allowance for discovery, planning or prose clarification. Incomplete/retryable blocked repair consumes once. Completed repair proceeds to focused verification and any failure charges that same reservation once.
5. Replace generic semantic repair multiplication on the serial path with retained per-criterion failures. Keep unrelated process/output correction and project quality protections. Preserve live cycles through process interruption, restart and duplicate delivery.
6. Gate review and all resumed/hydrated/no-change source-completion paths on final full prose audit. Reopen demonstrated regressions with retained failures. Keep source acceptance separate from later project quality obligations.
7. Update canonical and delegating strategy prompts and readable block/status reports together. Preserve existing command routing and outer contract versions. Remove whole-set prose gap comparisons where they formerly granted serial repair permission.
8. Author only missing behavior regressions below. Refresh runtime briefings from the revised specs before retry. Do not mutate production workflow rows or reset the exhausted phase budget from implementation code.

## Accounting decision table

| Event | Accounting | Next source action |
| --- | --- | --- |
| Discovery, read-only planning or prose clarification | No reservation or failure | Continue audit/planning |
| Mutating repair launch | Reserve current cycle once | Repair current criterion |
| Process interruption before an accepted outcome | Retain live cycle; do not invent source failure | Existing process recovery |
| Incomplete or retryable blocked repair | Settle one failure for the live cycle | Replan below three; block at three |
| Completed repair | Keep cycle live | Focused source audit |
| Failed focused audit | Settle one failure for that cycle | Replan below three; block at three |
| Satisfied focused audit | Close current source gap, retaining failures | Next authored criterion or final full audit |
| Final full audit demonstrates regression | Reopen same identity with retained failures | Focused repair or immediate exhausted block |
| Final full audit accepts current source | Retain accounting and prose conclusion | Existing review/quality sequence |

The existing phase verdict carries routing. Prose explains findings and conclusions; no new audit schema or required text grammar participates in this table.

## test_obligations

| Realistic bug | Observable assertion | Criteria |
| --- | --- | --- |
| Broad repair advances several criteria or requires structured audit fields | Ordinary prose follows current authored focus; unrelated criteria still need audit | S2-001/S2-003 |
| Incomplete repair or failed verification charges twice | One cycle increases failures by one; discovery/planning do not charge | S2-002/S2-003 |
| Nested phase retries permit more than three criterion failures | Third unsuccessful cycle blocks with current criterion and readable gap | S2-003/S2-007 |
| Empty queue or resume skips final full source audit | Review is withheld until normal final verdict accepts current source | S2-004 |
| Regression or restart resets failures | Reopened criterion retains prior count; duplicate delivery is once-only | S2-004/S2-006 |
| Failed settlement publishes acceptance | Rollback leaves phase/ledger/accounting/workflow unchanged and no success escapes | S2-002/S2-006 |

Reuse existing runner and isolated production-storage fixtures. Test observable behavior without exact prompt strings, output field schemas or historical adoption matrices. Keep existing unrelated ownership, cancellation and compatibility coverage.

## Coverage and handoff

S2-001 removes the rejected protocol and preserves prose delivery. S2-002/S2-006 connect transactions and recovery. S2-003 enforces focus/budget. S2-004 gates final source acceptance. S2-005 separates source and later quality work. S2-007 connects strategies and readable diagnostics. S2-008 covers the execution boundaries. Subtask 3 uses existing report text, not a new payload contract.

## Validation ownership

No runtime checks execute during respecification. Later owning phases run existing required checks and affected behavioral tests. Obsolete feature-schema tests are removed with their code; unrelated schemas and guards retain their checks. Refresh planning/briefings before runtime-owned retry, preserving history.
