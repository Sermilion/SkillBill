# SKILL-426 Serial acceptance-criterion repair with durable per-criterion attempt budgets

## Mode

decomposed

## Intended outcome

Repair one authored acceptance criterion at a time through the existing prose audit, focused planning and repair loop. The third unsuccessful repair cycle for that criterion blocks with a readable account of the remaining gap. Attempts survive restart and replay. Once focused repairs finish, a final full source audit precedes the existing review and project quality phases.

SKILL-422 exposed the problem. A broad repair plan covered several unresolved criteria, but aggregate phase retries could exhaust before another audit established which gaps remained. Those retries do not prove that each criterion used three attempts. Subtracting reported gaps from the authored criterion total also does not prove completion, especially when criteria include later test or build obligations.

## Respecification, 2026-10-14

The operator corrected the design to preserve the project's prose-based workflow. This specification and its subtask specs and plans supersede requirements for structured audit evidence, schema-driven audit admission, dependency graphs, strict repair handoffs and historical adoption allowlists. No new schema or validator is part of this feature.

Subtask 1 has already produced code under the superseded design. Its recorded completion and evidence remain history, not proof that the revised feature is complete. Subtask 2 owns removal of the incompatible machinery and integration of the useful persistence work. Existing workflow IDs, status, attempts and ledger entries are preserved. Changing these documents does not reset or resume the blocked workflow.

## Scope

Use the existing acceptance-audit, audit_plan_fix and audit_implement_fix owners. Findings, plans, repair reports and audit conclusions remain prose in the existing phase result. Runtime-owned facts identify the current authored criterion and repair cycle and retain attempt counts. Agents do not supply a structured evidence record or a new machine-readable declaration of criterion dispositions.

Subtask 1 defines durable accounting and transaction behavior. Subtask 2 reconciles that foundation with this correction and connects the running prose loop. Subtask 3 presents the committed facts as readable status and updates guidance through existing reporting channels.

## Acceptance Criteria

1. AC-001. The runtime selects one authored criterion at a time in spec order, preserving its original identity and full text. Composite criteria retain one identity and budget. Existing prose audits identify source gaps and explain prerequisites without a dependency graph, disposition schema or graph validator.
2. AC-002. Existing audit, audit_plan_fix and audit_implement_fix steps receive readable context for the current criterion, its remaining gap, relevant prior reports and remaining allowance. The runtime associates each invocation with its own current criterion and cycle. A focused audit's normal phase verdict and prose conclusion decide whether the current source gap is closed. Shared edits cannot silently close or skip another criterion.
3. AC-003. Each mutating repair cycle reserves once before effects and consumes at most one unsuccessful attempt. Discovery and read-only planning consume none. An incomplete or retryable blocked repair consumes one failure; otherwise its failed targeted audit consumes that same cycle's failure. The third unsuccessful cycle blocks with the latest prose gap. Success retains previous failures. Generic semantic retries do not multiply the criterion's repair allowance; unrelated process and project quality protections keep their scope.
4. AC-004. The existing acquired-owner transaction persists current criterion, cycle and outcome, retained counters and relevant prose references together with phase records, ledger, attempt evidence and workflow advancement. Restart and duplicate delivery preserve the same allowance. Stale owners cannot settle another owner's cycle. Failed commit publishes no successful closure or advancement. Filesystem projection recovery does not repeat a committed outcome.
5. AC-005. Finishing the focused queue requires a final full source audit before review or resumed source completion. Its normal verdict and prose conclusion can reopen a demonstrated regression with the same identity and retained failures. An exhausted reopened criterion blocks immediately. Source acceptance leaves the existing review, build and project quality phases in place.
6. AC-006. Reports distinguish source gaps, source acceptance and obligations belonging to later project quality phases. Mixed criteria can have their source gap closed while tests remain pending. The runtime reports only facts it knows. It does not infer completed criteria by subtracting prose gap counts, fabricate test evidence or claim full completion from source acceptance.
7. AC-007. Existing CLI/MCP reports, desktop details and IntelliJ details expose readable committed context for the current criterion, used and remaining failures, queue position, latest gap and final-audit requirement. Preserve stale caveats, unavailable history and phase/subtask progress. Reporting readers have no repair mutation authority.
8. AC-008. Audits, plans and repair reports remain prose in existing envelopes and reporting text. This feature adds no audit-result, repair-state, repair-handoff or adoption schema, no semantic validator, no required prose field format and no contract version bump for criterion reporting. Remove the superseded feature's schema/admission machinery and dedicated registrations. Existing unrelated transport contracts remain unchanged. Preserve historical outputs and attempts without inventing per-criterion attribution from old aggregate retries.
9. AC-009. A few behavior-focused regressions cover serial routing, one failure per cycle, third-failure blocking, retained budgets on restart/replay/regression and coupled rollback. Reporting coverage checks actual reader behavior where needed. Tests do not pin prose wording, enforce audit fields or recreate the discarded schemas.
10. AC-010. Prompts and runtime command guidance describe the prose loop, criterion budget and final full audit. Preserve existing commands, worker ownership, cancellation, checkpoint and commit rules, architecture guards and attributable diagnostics. Necessary skill instructions belong in content.md or governed sidecars; generated install outputs remain uncommitted.

## Policy decisions

The allowance counts unsuccessful repair cycles, not planning calls or successful repairs. Success and reopening never clear previous failures. A criterion that closes after two failures and later regresses has one unsuccessful attempt left.

The authored spec supplies criterion identity and ordering. The runtime supplies focus and attempt metadata. Auditors explain findings and prerequisite work in prose. Necessary shared edits can occur during a focused repair, but other criteria still receive their own audit before closure. There is no new graph or exact catalog response protocol.

Use the existing audit verdict interpretation and phase result. Do not make phrases, tags, JSON fields, disposition lists or evidence receipts prerequisites for progress. If an audit needs clarification, request a prose follow-up through the existing audit path. Read-only clarification does not reserve a repair or consume its allowance.

Persist the workflow facts needed for accounting through the existing owning persistence path. Private domain models and transaction preconditions can express those facts; they are not agent output contracts. The runtime associates reports with its invocation and checkpoint rather than demanding that an agent reproduce identifiers or hashes.

## Compatibility decision

Preserve existing workflow history, checkpoints and output envelopes. Resume uses known committed criterion accounting where available. Older aggregate attempts remain historical aggregate attempts; do not assign them to criteria or silently reset known per-criterion failures. Explain unavailable historical attribution in prose. Any prospective start from older history must be an explicit operator decision, separate from source acceptance.

Remove exact historical tuple reconstruction, descriptor capture and adoption receipt requirements introduced solely for this feature. Keep recorded incident evidence inspectable. This respecification does not authorize migration or automatic retry of SKILL-422 or SKILL-426.

## Constraints

Implement in the workflow-supplied checkout and preserve unrelated edits. Apply AGENTS.md, runtime command guidance, runtime architecture/design principles, code principles and observability policy. Review applies the architecture section 5 checklist with rule IDs. Keep one authoritative transaction owner, propagate cancellation and retain bounded attributable recovery and failure reports.

The operator's prose requirement overrides the superseded requirement to add schemas. Existing unrelated schema enforcement, architecture guards and project quality checks are outside the correction. Do not weaken them to remove this feature's machinery. Tests use disposable storage and homes, not production workflow data.

Later implementation applies AGENTS.md, docs/runtime-command-guidance.md, docs/architecture-guidelines.md A1-A12/G1-G7/P1-P8, runtime-kotlin/ARCHITECTURE.md Design Principles, docs/code-principles.md and docs/observability-policy.md. Review owns the section 5 checklist with rule IDs. Use narrow typed observations/requests, one authoritative transaction owner, shared keys only for actual multiple consumers, injected Clock/host facts and cancellation propagation. No authored Kotlin line/block comments; KDoc only on interfaces/members. Do not weaken guards, baselines, exemptions, payload budgets or scan fixtures.

Preserve SKILL-422 work, evidence, ACs, retry/workflow rows and checkpoints, plus unrelated desktop edits. Apply implementation and repair edits in the repository checkout supplied by the active workflow briefing. Reconcile separate worktree changes into that checkout before reporting implementation complete. Required review/validate repairs to production wiring, test bodies/setup, formatting and lint remain permitted. Source ownership maps are starting points, not blanket file allowlists.

Experiments use isolated SQLite fixtures, explicit disposable homes/environments, injected time and owned controlled processes. Production databases and incident evidence remain untouched. No tracker is required or contacted.

## Non-goals

No new command, generic workflow framework, dependency upgrade, speculative UI redesign, automatic incident resume or hidden budget increase. Repair does not rewrite the target workflow's acceptance criteria. This operator-authorized respecification changes SKILL-426's design requirements only.

## Dependency notes

Keep the existing three-subtask decomposition and runtime-owned manifest state. Subtask 2 consumes useful subtask 1 accounting and transaction work after removing its superseded contracts. Subtask 3 depends on the connected executor. Earlier recorded acceptance is historical evidence and does not excuse incompatible code in the completed branch.

## Subtask acceptance boundary

Audit each subtask against its revised S1, S2 or S3 criteria. Parent references describe contributions, not a demand for the entire feature in subtask 1. Subtask 2 must finish both cleanup and execution integration before serial enforcement is complete. Subtask 3 formats committed facts without adding a second state owner or protocol.

## Validation strategy

This respecification executes no builds or runtime tests. Later phases run existing required project checks under their normal ownership. Source audit reads source and authored tests; project validation runs the required repository checks and repairs failures. No audit-specific schema validation or exact-prose tests are added. Compilation alone does not replace the project's existing checks.

## Next path

Refresh the parent/subtask planning and runtime briefings from these corrected documents before retrying the blocked implement phase. The existing goal entry is skill-bill goal SKILL-426, with skill-bill SKILL-426 as its intake equivalent. Retry and resume remain runtime-owned operations that preserve history. This document change does not execute either operation.
