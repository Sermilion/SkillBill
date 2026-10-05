# SKILL-403 Claude Opus 5.5 slot strategies with automatic model selection

## Outcome and scope

Existing model configuration automatically selects substantive, versioned Opus 5.5 strategies for qualifying agent steps. Operators do not configure strategy flags. Selection uses the launch adapter's effective model resolution, preserves per-phase assignments and effort, and records immutable strategy and model facts before execution. All ten slots have an explicit policy. `commit_push` retains its canonical runtime-only strategy.

This MEDIUM feature has full preplan ceremony, branch-diff review, and full per-criterion audit. The implementation base is `base/SKILL-380-phase-slot-strategies`. Existing unrelated dirty changes remain intact, especially checkpoint recovery and domain decoder work. Planned paths identify owners, not an exclusive edit allowlist. Later authorized repair phases may fix production wiring, test setup, formatting, and lint defects required to satisfy this specification.

This bundle has one executable subtask. Adapter resolution, registered directives, durable admission, child propagation, and tracking form one automatic-selection change. The preplan digest recommended four subtasks, but its first two commits would preserve canonical selection and leave registered variants unselected. No part needs a separately shipped result or facts that become knowable only after an earlier implementation. One coherent commit avoids paying four complete ceremonies for dependent pieces.

Planning uses only the supplied preplan digest. No repository discovery, source verification, or executable validation belongs to this planning invocation.

## Evidence and decisions

[Getting the most out of Opus 5.5](https://claude.dev/blog/getting-the-most-out-of-opus-5-5/) supplies the prompting recommendations reported by preplan. Apply outcome-based prompts, explicit stopping conditions, continuous work within existing authorization, checks of delegated evidence, persistent tracking for long implementation work, actionable final reports, and explicit uncertainty. Remove generic thinking exhortations. Ask for concise decision explanations, never private reasoning. Keep the existing findings taxonomy rather than adopting the article's example severity filter. Do not activate paid fast mode or change effort.

Per-step specialization within a slot, immutable admitted model profiles, runtime-private tracking, and runtime-only commit execution are project design decisions. They adapt those recommendations to existing phase authority and output contracts.

Preplan reports `claude-opus-5-5` for the Anthropic API and `anthropic.claude-opus-5-5` for Bedrock. These are provisional exact recognition candidates, not invented or permanently verified identifiers. Implementation confirms supported identifiers against [Claude model documentation](https://platform.claude.com/docs/en/models/overview) and confirms alias/default precedence against [Claude Code model configuration](https://code.claude.com/docs/en/model-config). Provider-specific identifiers qualify only where that adapter can establish their exact meaning. Unversioned `opus`, arbitrary deployment names, regional profile spellings, and the agent name `claude` do not prove version 5.5. No billed probe or interactive UI scraping is required.

## Selection and launch design

First resolve the canonical skeleton definition, mode, gate, selected steps, and optional-step participation. Then resolve requested assignments with existing precedence. Explicit per-phase directives outrank the execution matrix. Within the matrix, per-agent phase overrides outrank tier defaults. Preserve explicit effort exactly.

A purpose-built operation on the existing agent-launch boundary resolves launch-model facts before strategy selection. Provider environment and remapping remain adapter-owned. The eventual command builder consumes the same resolved assignment rather than interpreting provider configuration a second time. Requested identity, effective prelaunch identity, resolution provenance, explicit effort, and bounded unknown reason remain separate typed facts. Domain facts are immutable; provider I/O stays in infrastructure; engine selection consumes facts through ports; runtime-core remains the composition root.

Apply provider remapping before exact classification. A requested Opus identity remapped to `deepseek-v4-flash` selects canonical behavior. Explicitly pinned aliases may specialize only after authoritative adapter resolution establishes a documented exact version. There is no account-default lookup in the observed launcher boundary. Flag-free opaque defaults and server aliases remain unknown unless an existing authoritative configuration rule resolves them. One owner implements exact profile classification; strategies and shared process runners consume profile facts without provider/model-string branches.

Select an Opus-capable variant for a slot when any participating agent step resolves to Opus 5.5. Supply Opus directives only to qualifying steps. Nonqualifying steps retain canonical directives even within the same slot. Include selected loop-only repairs and their assignments in admission. An absent optional step or an unselected `validate` declaration cannot change a build-only slot's profile. Child assignments count only for selected launch paths and stay explicit. Native child overrides remain authoritative. An inheriting child receives its parent's recorded assignment only where the adapter supports that inheritance; otherwise its identity remains unknown. Never label all specialists Opus because their parent qualifies.

Unknown identity preserves canonical behavior and emits the existing attributed unknown-resolution record. Ordinary known non-Opus selection is a normal diagnostic decision. Contradictory assignments, inconsistent profile facts, and missing required registrations fail loudly through the owning typed refusal contract, before mutation or launch. There is no partial specialization fallback.

## Slot policy matrix

Every family listed below has a registered versioned Opus variant with substantive directives, except the explicit runtime-only commit policy. Variants reuse behavior within their owning slot. A wrapper that only forwards all operations is not an implementation.

| Slot and selected steps | Compatible families | Opus guidance and preserved authority |
| --- | --- | --- |
| preplan, `preplan` | AgentPreplanStrategy | Gather relevant evidence, name missing facts, stop at the existing bounded planning projection. No speculative implementation. |
| plan, `plan` | AgentPlanStrategy, GoalPlanFanOutStrategy | Produce executable acceptance-based plans, check evidence from selected units, retain waves, chunk caps, settlement, and binding release. |
| implementation, `implement`, `simplify` | ImplementThenSimplifyStrategy | Complete scoped changes and required output, use a resumable runtime-private checklist, retain both steps and their different session policies. |
| audit, `audit_plan_fix`, `audit_implement_fix`, `audit` | AcceptanceAuditStrategy | Retain initial full coverage, later unresolved coverage, criterion monotonicity, read-only inspection, persisted repair planning, ordered repair, receipts, and caps. |
| code_review, `review`, `verify_findings`, `implement_fix` | InlineReviewStrategy, DelegatedReviewStrategy | Request file/line evidence and explicit uncertainty, retain taxonomy, verification, authorized repairs, and checkpoint ownership. Register the delegated counterpart without activating previously unselected delegated full-run routing. |
| standalone_review, `present_findings` | InlineStandaloneReviewStrategy, DelegatedStandaloneReviewStrategy | Deliver evidence-based reports only. Preserve read-only delegation, register validation, both valid verdicts, and dirty index/worktree state. No repair or staging. |
| quality_gate, definition-selected `build` or `validate` | PackBuildStrategy, PackValidationStrategy, AgentValidateStrategy | Preserve pack command families, receipt and successful-command checks, timeouts, and retries. Carry directives into ordinary, triage, and repair turns. Build is one agent session without delegated workers and uses only its pack build commands. Validation is full project validation. |
| write_history, `write_history` | BoundaryHistoryStrategy | Record demonstrated changes, decisions, actual validation, and unresolved facts in the existing format. |
| commit_push, `commit_push` | RuntimeCommitStrategy | Always retain `runtime-commit`; launch no new agent and add no Opus forwarding class. Existing recovery uses the preceding accepted step's recorded assignment and existing budget. |
| pull_request, `pr` | PrDescriptionStrategy | Describe resulting behavior, actual validation, and material unresolved issues. Retain template lookup, readiness, runtime branch/push ownership, and authorization. |

`promptSections` and accepted `runStep` remain the authority-bearing seams. Model-specific guidance reaches the accepted runner, slot-owned direct launches, gate repair turns, fan-out units, and delegated review launch paths. It does not alter graph membership, phase identifiers, entry steps, validators, role bindings, retry budgets, checkpoints, or supported historical audit mappings.

## Definition composition

| Definition | Retained selection |
| --- | --- |
| STANDALONE | Shared feature slots, agent validation, and PR description. |
| GOAL_CHILD | Shared feature slots and its configured pack build or agent validation gate. |
| REVIEW | Standalone report-only review. DELEGATED remains delegated; AUTO and INLINE remain inline. |
| VALIDATION | Pack full validation. |
| PLAN | Agent preplan and ordinary agent plan. |
| GOAL_PLANNING | Agent preplan and goal-plan fan-out. |
| PR | Runtime commit followed by PR description. |

Shared full-run CODE_REVIEW currently binds every `CodeReviewExecutionMode` to inline review. Preserve that mapping. Register the compatible delegated full-run variant without turning this feature into a routing change. Model selection cannot bypass platform packs, substitute validation for build, flatten goal-planning fan-out, replace a delegated standalone request with inline, or convert standalone reports into repair.

## Durable identity and recovery

New execution descriptors record strategy ID/revision and bounded effective per-step launch/profile inputs before launch. Evolve `../../../orchestration/contracts/feature-task-runtime-execution-plan.yaml` first, followed by Kotlin version constants, centralized wire vocabulary, codec, schema validation, and coherence checks. Use the next contract version consistent with the owner's conventions, confirmed during implementation. Closed fields, bounded collections and strings, canonical named-collection ordering, and ordered traversal semantics remain enforced. Each new parse failure has an owner-declared failure code and loud `SkillBillRuntimeException` handling.

Add immutable model facts to reconstruction, including `withTraversal` and `withEffectivePolicies`, cache keys, `matchesRecordedSelection`, and execution-plan mapping. Cache keys copy canonical per-step inputs and selected child assignments, including provenance when it changes launch meaning. Record effort in launch assignments even where it does not change the profile. Do not cache credentials, endpoint URLs, prompts, or raw provider payloads.

Historical version 0.1 descriptors retain their admitted canonical strategies. A narrow validated historical reader maps absent profile facts to historical unspecialized behavior. It never resolves current aliases to upgrade an existing plan. Unsupported or corrupt records retain evidence and refuse through the existing recovery contract. Resume consumes recorded facts; a current alias default or model configuration cannot silently change strategy. An incompatible launch/profile change refuses before workflow advance, parent/child controls, or lease mutation. No reset or descriptor regeneration obtains specialization.

PhaseRunEntry and goal planning use the same resolution/selection semantics without durable workflow rows. Goal planning carries the resolved plan-step model and effort into each qualifying unit. Application-owned delegated review requests carry explicit parent/child facts into supported launch paths. Checkpoint recovery uses the preceding accepted step's recorded effective assignment, preserving its two-attempt ledger budget, persistence before launch, index restoration, branch/HEAD checks, and hook policy.

## Reported model evidence and diagnostics

Structured provider output may report more than one identity during a session. Adapter decoders retain bounded distinct reported identities separately from requested and effective prelaunch identities. They extract documented fields when present; malformed fields produce bounded degradation evidence without persisting raw output. Unknown actual identity is explicitly unavailable. Requested or effective prelaunch identity is never proof of actual execution.

A reported deviation or automatic fallback emits the existing observability record. It does not reselect the admitted strategy during an accepted attempt, change ordinary output admission, or expand authorization. Undisclosed provider fallback remains an external limit. No terminal identity claims to characterize every turn.

RuntimeDiagnostics and existing run observability explain slot, strategy ID/revision, selected profile, safe identities, unknown/fallback reason, seam, expected value, and used value. Bound string lengths and collection cardinality at owned parse/serialization seams. Concrete bounds follow existing owner conventions confirmed during implementation. Opaque model strings are sanitized or represented by a bounded reason when they could contain secrets. Records contain no prompts, credentials, endpoint secrets, raw provider payloads, or provider usage accounting restored by this change.

## Runtime-private implementation tracking

The runtime supplies a run/subtask-scoped checklist path under an existing private `../../../.skill-bill` location. Seed and reconcile tasks from authoritative plan tasks and implementation continuation evidence. The checklist helps resume work but cannot advance a workflow, complete a task by itself, change a phase receipt, or replace authoritative state. Both implementation and simplify receive the appropriate tracking guidance within their existing session policies.

Missing, corrupt, or unwritable tracking emits existing degradation records and leaves task completion unproven. Filesystem effects belong to an adapter. Keep `../../../.skill-bill/config.yaml` trackable, preserve active-run evidence attribution, and do not widen evidence-directory exclusions to hide the checklist. Do not add committed `TASKS.md` by default. If the private location's exact shape is not established by preplan, implementation confirms its owner and selects a bounded run/subtask address there rather than inventing another state store.

## Acceptance Criteria

1. AC-001. All ten slots have the explicit policies in this spec. Every compatible agent-launching family has a registered versioned Opus variant with substantive directives, and commit_push retains `runtime-commit`. Existing PhaseStrategyRegistryTest and PhaseStrategyCompositionTest contain registration/coverage assertions.
2. AC-002. Model resolution preserves per-phase-over-matrix and phase-override-over-tier precedence and explicit effort. Exact documented supported Opus 5.5 identifiers and demonstrably pinned aliases qualify; older, unknown, unrelated, unversioned, and substring matches do not. FeatureTaskRuntimeModelResolverTest and launcher tests assert these boundaries.
3. AC-003. Adapter-owned remapping precedes classification and the builder consumes the same resolved assignment used for selection. Requested Opus remapped to non-Opus stays canonical. Adapter behavioral coverage asserts resolution/launch parity. A1, A2, and A4 ownership and dependency placement remain review checks beyond mechanical layering guards.
4. AC-004. Typed per-step facts select Opus-capable variants only for participating steps and deliver specialized directives only to qualifying steps and children. Selected repair assignments are admitted; absent optional steps cannot influence selection. Composition, cache isolation, and captured child-launch tests assert mixed-model behavior.
5. AC-005. Every definition, review-mode, and gate combination retains its selected steps, graph, fan-out, output validators, roles, retries, and checkpoint authority. PhaseStrategyTraversalTest, PhaseStrategyCompositionTest, and StrategyCapabilityBoundaryArchitectureTest retain these assertions, including existing full-run inline routing and delegated standalone routing.
6. AC-006. New durable descriptors record immutable selected strategy/model/profile facts before launch. Codec reconstruction and compatibility consume recorded facts. A validated historical reader preserves admitted 0.1 canonical plans; incompatible or corrupt inputs refuse before workflow or lease mutation. Schema parity, creation, reconstruction, and admission tests assert these outcomes.
7. AC-007. In-memory plan, review, validation, PR, and goal planning share effective selection semantics without creating workflow rows. Existing phase-run and goal-planning fixtures assert selected assignments and the storage boundary.
8. AC-008. Accepted launch requests contain the Opus directives for qualifying steps, including direct launches, gate repair, and fan-out. Existing output rejection, correction, ordered repair, caps, checkpoint, and supported audit-history regressions remain present and unchanged in behavior. Captured-launch and existing audit/review/retry tests assert these boundaries.
9. AC-009. Inline and delegated standalone review remain report-only. Both valid verdicts complete under the existing admission contract; invalid reports block with retained findings. Existing standalone admission and phase-run tests assert dirty index/worktree preservation and absence of repairs or staging.
10. AC-010. Quality gates retain pack-owned build/full-validation command families, receipts, evidence, timeouts, budgets, and delegation restrictions. Runtime commit launches no agent. Recovery consumes the recorded preceding assignment with its existing two-attempt ledger and safety checks. Gate/effective-policy, commit-cycle, and recovery tests assert these boundaries.
11. AC-011. Requested, effective prelaunch, and provider-reported model identities are distinct bounded evidence. Decoder and launch-evidence tests assert multiple reported identities, explicit unavailability, and fallback recording without mid-attempt reselection. Provider-hidden identity remains a documented external limit.
12. AC-012. Implementation tracking is runtime-private, resumable, nonauthoritative, and observable on failure; config and run-evidence exclusions retain their existing scope. Checklist failure/staging tests assert ownership. History and PR directives retain evidence and format requirements; prose quality remains review-only.
13. AC-013. Ownership and dependencies follow A1-A12, Design Principles, and code principles without dependency bags, pass-through interfaces/wrappers, a second composition root, duplicated wire keys, unowned state, prohibited comments, or widened guard baselines. Existing architecture guards retain their mechanical coverage. A2, A5, and A11 necessity and ownership judgments remain review-only; guard changes follow G1-G7 with rejecting fixtures and nonempty discovery.


14. AC-014. `../../../docs/model-specific-phase-strategies.md` explains how to add another model using the landed Opus strategy as a concrete example. It documents verified identifiers and aliases, effective-model precedence and remapping, mixed-model slots and children, slot/definition registration, directives and authority, cache and durable identities, semantic revisions, resume refusal, diagnostics, and required behavioral tests. It links the actual owners and distinguishes available behavior from planned behavior. Documentation review checks the examples against production registration, configuration, and compatibility tests. This criterion is review-only; no test pins prose wording.

## Model-strategy authoring guide

Maintain [the authoring guide](../../../docs/model-specific-phase-strategies.md) in the same change as this feature. The guide already records the design requirements and explicitly marks model selection as planned. Once implementation lands, replace that status with verified registration and configuration examples drawn from the real Opus implementation. Include a worked example of adding a future model, showing what gets registered, how effective model facts select it, which contracts remain unchanged, and which tests prove substitution. Do not invent an available API or require manual strategy flags. Future model changes update the guide alongside their implementation.

## Implementation order and dependency notes

The executable detail and test obligations live in `spec_subtask_1_automatic_selection.md`. Implement in ownership order within one coherent change: establish authoritative resolution and reported evidence; add substantive variants; integrate typed selection, durable admission, cache identity, and child propagation; finish private tracking, diagnostics, and documentation. Keep canonical registrations available throughout. Automatic selection is the shipped end state, not a manual activation stage.

Read `../../../docs/architecture-guidelines.md`, Design Principles in `runtime-kotlin/ARCHITECTURE.md`, `docs/code-principles.md`, `docs/runtime-command-guidance.md`, and `docs/observability-policy.md` during the authorized implementation phase. Preplan's boundary-memory decisions govern accepted-step authority, admission before mutation, immutable reconstruction, report-only ownership, runtime commit, audit progression, and evidence attribution. Superseded compile-in-audit or stricter shrinking guidance does not enter new directives.

## Non-goals

No new standalone command, manual strategy switch, model upgrade, paid fast mode, automatic effort/cost tuning, hard-coded platform selection, global user-instruction rewrite, changed review severity, checklist-owned durable state, billed model probing, UI scraping, or implementation during planning. Do not author generated SKILL.md output or commit generated support pointers/provider outputs. Do not activate delegated full-run routing that is currently inline.

## Validation Strategy

Only validate executes tests and full repository checks. Only build executes the pack build command. Implement and audit inspect repository states and author evidence-bearing tests; they do not compile, test, run generators, or mint validation receipts. These ownership rules supersede the preplan sentence suggesting implementation run validation.

Validate discovers and runs the repository-required checks, including the Kotlin pack's collect-all full-validation command, required runtime Gradle checks, strict agnix validation, and `../../../scripts/validate_agent_configs` required by CI. Compilation alone is insufficient. Use the installed runtime's phase reporting contract rather than new checkout schemas for this run's settlement. Build uses only the owning pack's build command in its own phase.

Extend existing behavioral tests instead of adding a parallel harness. Every new test obligation in the subtask names a concrete regression and an observable assertion. Preserve real past-bug regressions and governed parity/validator rules. No tests are needed for trivial forwarding glue. Architecture review applies the section 5 checklist with A1-A12 and G1-G7 rule IDs. Mechanical checks include layering, composition-only, port declarations, raw-map seams, wire vocabulary, typed parse failures, failure-code totality, ambient effects, cycles/clustering, comment/KDoc rules, and file ceilings. New scans use catalog coverage, declared build inputs, nonempty discovery, and rejecting fixtures without wider exceptions.

Only the owner-authorized later phase or parent runtime performs install refresh when authored skill source, renderer behavior, or support-pointer generation changes. Engine prompt changes alone do not require `./install.sh`. History, commit/push, and PR work stay with their owning phases. Review/validate may repair defects found by required checks within product and architecture constraints.

## Assumptions and rollout risks

The repository default branch was not supplied. The manifest uses `base/SKILL-380-phase-slot-strategies` because it is the explicit required implementation base, not a claim that it is the default branch. Implementation confirms that base through its normal runtime admission without moving work to a different base or disturbing existing changes.

Exact provider identifiers, adapter bounds, descriptor version conventions, historical-reader details, child inheritance support, and checklist address shape need implementation confirmation from their existing owners. Decisions here are fixed: unavailable facts stay unknown; historical plans stay canonical; contradictory authoritative facts refuse; tracking failures degrade tracking only. No operator answer is needed to choose those behaviors.

The main risks are descriptor compatibility, selection/launch drift, lost instructions in direct launch paths, and incorrect child inheritance. Keep recorded identities immutable, use the same adapter resolution for selection and launch, test captured accepted requests, and refuse before mutation. Do not widen baselines or regenerate saved plans to make them qualify.

## Next path

The runtime consumes `spec_subtask_1_automatic_selection.md` for the single executable subtask. The future entry command is `skill-bill goal SKILL-403`. This planning invocation does not run it or continue another workflow.
