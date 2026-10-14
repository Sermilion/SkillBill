# SKILL-426 Serial acceptance-criterion repair with durable per-criterion attempt budgets

## Mode

decomposed

## Intended outcome

After the initial source audit admits production gaps, the runtime repairs one authored AC at a time. A focused plan, repair and targeted audit stay bound to the current AC. Its third unsuccessful attempt blocks with the remaining gap and evidence. Durable state survives restart, recovery and duplicate delivery. An empty queue requires a final full production audit before the existing review and quality-gate sequence.

SKILL-422's audit reported six unresolved production criteria and a 20-item broad repair plan. Aggregate phase retry exhaustion stopped work before another audit could verify closure. Those records do not establish six independently exhausted AC budgets. Reporting eight authored criteria minus six gaps as two completed criteria also obscures deferred validation.

This invocation prepares artifacts only. It does not authorize implementation, SKILL-422 resume, migration, branch changes, builds, tests, installation, commit, push or publication. These restrictions govern this planning invocation. A later separately authorized full launch gives each phase its normal authority subject to the constraints below.

## Scope

Use the existing acceptance-audit strategy and phase/slot owners, authoritative transaction boundaries, schema admission, bounded handoff, final-audit permission, compatibility refusal, block diagnostics, common status serialization, and existing desktop/IntelliJ details. Policy stays in domain/application/engine, adapter effects in infrastructure, and composition in runtime-core.

Two dependency-ordered subtasks deliver independently shippable increments. Subtask 1 contains the complete serial policy, contracts, persistence, recovery, final-audit gate, compatibility and production-boundary regressions in one reviewable commit. No contract-only intermediate state is allowed. Subtask 2 can ship separately against the established read-only snapshot. Its absence does not disable enforcement or the runtime's budget-block explanation. This split keeps substantial fault/recovery work in one implement pass and postpones an independently useful presentation increment. It does not assign one ticket to each layer or AC.

Each subtask spec contains an executable Implementation Details section. implementation_plan_subtask_1.md and implementation_plan_subtask_2.md save the detailed execution checklist and coverage mapping. These artifacts grant no additional handoff sources; declared runtime consumer projections remain authoritative.

## Acceptance Criteria

1. AC-001. Initial audit admission records explicit dispositions for every admitted authored criterion and one durable unresolved-production queue/current AC. It retains catalog/spec and execution identities and original text. Structured dependency edges record reasons and evidence provenance. Stable topological order uses admitted spec ordinal for ties. Typed admission refuses unknown references, self-edges, cycles and validation-owned prerequisites impossible in source repair. Composite ACs retain their authored identity and budget.
2. AC-002. Existing audit, audit_plan_fix and audit_implement_fix owners use durable initial-discovery, current-criterion-targeted and final-full modes. Their validated bounded handoff binds current requirements/findings, prerequisite facts/reasons, prior attempt evidence, budget/position and admitted tree/checkpoint. Only runtime-accepted targeted satisfaction closes the current AC and advances once. Shared/prerequisite edits cannot close or skip another AC.
3. AC-003. Transition code reserves one repair cycle before effects and pairs it with one targeted verification outcome. Discovery and read-only planning consume no attempt. Incomplete/retryable blocked repair consumes one failure without requiring verification. Failed targeted verification consumes one failure for that same reservation. The third unsuccessful outcome blocks. Success retains earlier failures. Serial repair neither nests the phase's three semantic retries nor uses whole-set non-shrinking rounds as permission; unrelated process/output/build/review/monitor protections retain their scope.
4. AC-004. Authoritative serialization and acquired-owner-fenced transactions persist queue order/current AC, reservation/outcome, non-resettable counters, admitted evidence identities, latest gap and closure/reopen history with coupled phase/ledger/workflow changes. Admission binds expected revision/current AC/reservation/owner generation. Recovery/replay preserves counts, rejects stale/conflicting output and prevents success publication after failed commit. Filesystem projection recovery never replays committed domain mutations.
5. AC-005. Empty queues record final-audit-required. Review and hydrated/resumed completion paths require accepted final-full source audit bound to the production catalog/current tree. Only this boundary reopens demonstrated regressions, retaining identity/history/consumed budget. Exhausted reopened ACs block immediately. Passing final audit restores existing review/quality gates. Source audit has no build/test authority.
6. AC-006. Durable state and reporting distinguish source dispositions from validation obligations, including mixed criteria. Source-closed/unresolved/deferred counts use admitted evidence, never authored total minus gaps. Deferred execution stays deferred until validation-owned evidence exists; source satisfaction alone never reports full completion or fabricates a receipt.
7. AC-007. Runtime budget blocks and common CLI/MCP/status payloads expose active AC, used/remaining unsuccessful attempts, queue position/size, source-closed/unresolved/deferred counts, audit mode and bounded actionable gap/evidence. Existing desktop details and IntelliJ tooltip/accessibility/cached states carry the same facts with stale/legacy absence intact. Readers cannot reserve or advance; phase/subtask progress keeps its meaning.
8. AC-008. New durable/wire artifacts have Draft 2020-12 YAML schemas, owning keys/enums, versions, validator dispatch, parity/rejection coverage and typed diagnostics. Exact supported stopped pristine legacy tuples have validated adoption provenance and preserve original descriptors, phase outputs, aggregate retry records, ledger and checkpoints. Ambiguous previous repair/in-flight history explicitly refuses adoption. No migration invents per-AC historical attempts or deletes evidence.
9. AC-009. Authored behavior-focused boundary regressions cover serial routing/budgets, once-only incomplete/audit outcomes, restart/replay/fencing/failed commit/projection recovery, prerequisite non-closure, retained-budget final regressions, deferred validation and safe/unsafe admission. Tests reuse runner and isolated production SQLite fixtures. Every new test names its realistic bug and asserts observable behavior rather than exact prompt prose or mapper structure.
10. AC-010. Runtime command guidance and strategy prompts describe focused repair, accounting, final audit and legacy refusal. Skill instructions change only where this behavior requires it, in content.md or governed sidecars. Generated SKILL.md/support pointers stay uncommitted. Architecture guards, payload budgets, cancellation and bounded attributable refusal/recovery/degradation diagnostics remain enforced.

## Policy decisions

The budget counts unsuccessful outcomes, not all reservations. Persist total reservation history and successful closures separately from unsuccessfulBudgetUsed. Remaining is three minus that counter. Every unsuccessful reserved cycle consumes once. Success and reopening never erase failures. An AC that closes with two failures and later regresses has one unsuccessful allowance left. Its third failure blocks.

Dependency declarations accompany structured initial audit admission against the whole catalog. Each edge identifies dependent/prerequisite canonical IDs, concrete reason, declaring audit output identity and bounded spec/source evidence references. Runtime validates the graph before freezing the initial queue. No declared edges means spec order. Unclear edges require corrected bounded admission, never prose-inferred ordering. Final regressions reuse the graph and spec ordinal; satisfied prerequisites remain facts. New/changed edges require validated final-audit admission, preserve counters/identities, and cannot bypass an unresolved prerequisite.

Mixed ACs can be source-closed and validation-deferred simultaneously. Deferred count is criteria with pending validation obligations, so it can overlap source-closed count. It is not a completed count or exclusive partition. Criteria without production obligations are explicitly source-not-applicable. Unknown dispositions refuse admission. Queue membership concerns unresolved production only; final-full audit covers every admitted production AC, including initially source-satisfied ones.

## Compatibility decision

Introduce canonical acceptance-audit semantic revision 4, including the delegating model-specific strategy. Admit exact stopped pristine revisions 1, 2 and 3 only after historical descriptor/policy digests, catalog, ownership, tree/checkpoint and audit evidence validate and no prior repair execution, ambiguous aggregate repair attempt or in-flight repair exists. Preserve historical policy reconstruction and original descriptors; retain adoption as separate provenance. Unresolved-only legacy prose requires a new explicit discovery admission before reservation. Do not reconstruct state from telemetry or erase old evidence.

Blocked broad-repair workflows such as the described SKILL-422 case are unsupported in this release. Refuse with typed unsafe-adoption diagnostics, preserving all records. Recovery is inspection with a compatible runtime followed only by a separately reviewed, later authorized mapping that defines prospective versus historical budget semantics and preserves identities/evidence. That mapping, actual migration and SKILL-422 resume are outside this bundle. A new runtime alone cannot safely resume that workflow.

New repair-state, structured-audit-result and focused-projection contracts start at 0.1. Extend the closed handoff vocabulary under 0.3 with explicit supported 0.2 admission. Keep phase-output envelope 0.7 and its supported 0.6 migration; validate audit payload separately under revision 4. Keep persistence 0.2 for existing private-record/delivered-projection shapes and validate the new durable artifact under its own 0.1 schema. If implementation proves an existing closed shape must change, version that owning schema first with exact admission/refusal coverage. No unversioned interpretation change is allowed. Subtask 2 owns IDE status 0.5 and optional-absent 0.4 admission.

## Constraints

Preplan is this phase's only repository knowledge. Planning reads only its own seeded bundle, installed skills and installed config resolution. No source, tests, governance text, git state or other bundle is rediscovered. Config resolution returned local, so omit spec_source and make no tracker calls. Digest observations are not claims of a freshly checked tree.

Later implementation applies AGENTS.md, docs/runtime-command-guidance.md, docs/architecture-guidelines.md A1-A12/G1-G7/P1-P8, runtime-kotlin/ARCHITECTURE.md Design Principles, docs/code-principles.md and docs/observability-policy.md. Review owns the section 5 checklist with rule IDs. Use narrow typed observations/requests, one authoritative transaction owner, shared keys only for actual multiple consumers, injected Clock/host facts and cancellation propagation. No authored Kotlin line/block comments; KDoc only on interfaces/members. Do not weaken guards, baselines, exemptions, payload budgets or scan fixtures.

Preserve SKILL-422 work, evidence, ACs, retry/workflow rows and checkpoints, plus unrelated desktop edits. Apply implementation and repair edits in the repository checkout supplied by the active workflow briefing. Reconcile separate worktree changes into that checkout before reporting implementation complete. Required review/validate repairs to production wiring, test bodies/setup, formatting and lint remain permitted. Source ownership maps are starting points, not blanket file allowlists.

Experiments use isolated SQLite fixtures, explicit disposable homes/environments, injected time and owned controlled processes. Production databases and incident evidence remain untouched. No tracker is required or contacted.

## Non-goals

No automatic SKILL-422 retry/resume, blocked-aggregate recovery mapping, desktop freeze investigation, new slash command/framework, broad architecture cleanup, AC rewriting, hidden budget increase or blanket unrelated retry changes. No speculative transport or UI redesign. Planning performs no implementation, migration, installation or publication.

## Dependency notes

Subtask 1 has no implementation dependency on SKILL-422. Subtask 2 depends on subtask 1's admitted state/read-only snapshot semantics. Integration repairs may not reset counters or weaken authority.

The digest does not name the default branch. Manifest base_branch assumes main as a planning descriptor only. Later launch must confirm the actual default and active-fix merge/worktree facts before selecting an implementation branch and correct that descriptor if needed. Planning changes no branch. Proposed filenames and packaging paths need implementation confirmation within the supplied owners. Installed packaging availability is a later launch fact.

## Validation strategy

Planning checks only this bundle for contained regular paths/no symlinks, supplied manifest 0.5 shape, earlier-only dependencies, required executable sections, numbered AC syntax and all ten requirements' coverage. This is artifact inspection. tests_executed is empty.

Build alone owns its explicit pack build command. Validate discovers and runs all required full checks from repository instructions/build/scripts/CI, including collect-all pack gates and applicable Gradle checks, then repairs failures. Implement authors tests; source audit reads production/guards without builds, test execution or generators. Validate covers runtime domain/engine/contracts/SQLite, CLI, IDE status, desktop and IntelliJ boundaries. Compilation alone is insufficient.

Later authorized skill/renderer/support-pointer changes require their render/install owner to read docs/skill-source-generation.md and perform required install/render and packaged-runtime checks using disposable staging/home paths. These are not implement/audit criteria. Review/history/commit/checkpoint/PR/monitor belong to their runtime owners. No AC requires a later phase's generated artifact.

## Next path

Stop after bundle inspection and plan MCP settlement. Record this later separately authorized launch path without executing it:

```bash
skill-bill goal SKILL-426
```

The installed intake equivalent is skill-bill SKILL-426. Later launch first resolves branch/worktree facts. Do not launch either form or call workflow continue from this phase.
