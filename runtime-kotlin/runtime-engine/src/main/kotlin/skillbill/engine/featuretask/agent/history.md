# featuretask runtime boundary history

## [2026-09-26] SKILL-380 subtask 4 — Skeleton definitions and quality_gate strategies
Areas: runtime-engine featuretask (slot, slot/qualitygate, runloop, runner, validation), runtime-domain taskruntime, runtime-core di, runtime-cli, runtime-mcp, orchestration/contracts
- SkeletonDefinition (STANDALONE, GOAL_CHILD) names the slot traversal. The quality_gate slot resolves to PackBuildStrategy or AgentValidateStrategy through the registry. FeatureTaskRuntimeQualityGateRouting and RoutedQualityGateStrategy are deleted.
- Each strategy lives in its own package under slot/qualitygate with its own gate cycle and step hooks. Only code under slot/qualitygate imports those packages.
- Selection facts are generic. Unselected steps are left out of projections, and handoffs that reference them are rejected. The completed-upstream repair maps the selection to omitted step ids because it has no strategy lookup.
- Gate progress goes through the PhaseRunState port. The build/validate progress stores are reached only through that port. reusable
- Validate settles with the uniform output plus a progress/no_progress verdict. A missing or unknown verdict counts as no_progress. One no_progress session blocks. This replaces the 2026-09-20 "repair until true" loop. On resume, validate success comes from the step status.
- A misspelled selection such as `biuld` is a CLI usage error. The MCP settlement tools accept validate, and feature_task_phase_block takes an optional verdict. The change is additive: contract_version stays 1.12.0 and phase-output stays 0.6.
- Limitation: the legacy `?: VALIDATE` defaults remain in the goal-continuation patcher and the artifact-presence resolver. The status service applies the legacy selection in memory only.
Feature flag: N/A
Acceptance criteria: 15/15 tasks implemented (check pending in validate)

## [2026-09-20] Validate keeps repairing until true
Areas: runtime-kotlin/runtime-engine/featuretask
- Validate no longer treats `validation_passed: false` as a schema-gate exhaust. The agent keeps repairing until true.
- The same remaining-failure text twice is a stall. Malformed envelopes retry twice. Wall-clock timeout still ends the subtask.
- Pattern: remaining check failures are repair occupancy, not a completed boolean handoff. reusable
Feature flag: N/A
Acceptance criteria: N/A (hotfix)

## [2026-09-20] Commit_push ignores validate tree fingerprint drift
Areas: runtime-kotlin/runtime-engine/featuretask
- commit_push no longer treats a drifted source_tree_sha or HEAD as stale identity. It adopts the current write-tree, stages every dirty non-ignored path, and commits.
- Base-ref mismatch still blocks. After commit, readiness evidence is rebound to the current source tree and new HEAD instead of failing the fingerprint.
- Pattern: dirty worktree at commit_push is payload, not a stale snapshot. reusable
Feature flag: N/A
Acceptance criteria: N/A (hotfix)

## [2026-09-20] Validate discovers project checks, not pack argv
Areas: runtime-kotlin/runtime-engine/featuretask
- Validate no longer runs pack `validation_gate` collect-all or cache-bypassing argv. The agent discovers commands from the project, repairs, and reruns them up to three times, then returns `validation_passed`.
- False or malformed results relaunch the phase up to three times. Commit_push readiness no longer selects pack-collect-all; it keeps project CI checks.
- Pattern: quality commands come from the repository, not from a platform pack recipe. reusable
Feature flag: N/A
Acceptance criteria: N/A (hotfix)

## [2026-09-19] SKILL-364 subtask 1 — Make PR readiness authoritative
Areas: orchestration/contracts, runtime-kotlin/runtime-engine/featuretask, runtime-kotlin/runtime-domain/taskruntime, runtime-kotlin/runtime-ports, runtime-kotlin/runtime-infra
- Added durable PR-readiness evidence binding validation to exact tree, head, and base identities plus selected checks before commit/push or PR.
- Routes affected runtime Kotlin paths through the governed pack gate and IntelliJ plugin paths through the plugin clean-check; reruns only invalidated checks.
- Pattern: snapshot identities and explicit check selection at the boundary; exclude allowed history writes from source invalidation. reusable
- Failed, missing, skipped, unpersisted, stale, and degraded checks remain blocking and retain diagnostics and observability.
- Limitation: no repository-wide workflow replacement; selection remains governed by affected paths and declarations.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-09-18] commit_push is runtime-owned with no agent turn
Areas: runtime-kotlin/runtime-engine/featuretask
- `commit_push` no longer launches an agent. The runtime stages dirty paths, writes the commit subject from the issue key and subtask name, pushes, and records `commit_sha`.
- Pattern: do not ask an agent for structured output when the runtime already owns the mutation and the only remaining field is a name the manifest already has. reusable
- Limitation: commit subjects no longer describe the post-review outcome in agent prose; they use the planned subtask name.
Feature flag: N/A
Acceptance criteria: 1/1 implemented

## [2026-09-16] commit_push stages every dirty path including feature specs
Areas: runtime-kotlin/runtime-engine/featuretask
- commit_push briefing no longer tells the agent to omit `.feature-specs/` from `changed_paths`.
- Finalisation stages every dirty non-ignored path, including `.feature-specs/`, so spec moves are not left uncommitted.
- Pattern: do not invent an extra-files category at a non-retrying phase; one envelope lists the whole dirty tree. reusable
- Limitation: goal finalize still ignores leftover spec dirt after the parent records `commit_sha`, so same-branch completion does not block on that post-commit write.
Feature flag: N/A
Acceptance criteria: 1/1 implemented

## [2026-09-15] SKILL-247 subtask 3 — Narrow run-loop helper dependencies
Areas: runtime-kotlin/runtime-engine/featuretask, runtime-kotlin/ARCHITECTURE.md
- Narrowed run-loop helper seams to request facts, state operations, recorder/observability capabilities, and cohesive collaborators instead of all-access loop context.
- Removed duplicate PlanningBranch overloads and public loop collaborator aliases while preserving phase advancement, session ownership, review repair, checkpointing, and resume behavior.
- Pattern: keep broad context only at orchestration seams that coordinate launch, diagnostics, transition, and session concerns. reusable
- Limitation: retained context inputs remain at validation agent-turn/fix-loop orchestration and review launch capture where those combined capabilities are still required.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-09-15] SKILL-236 subtask 3 — Review execution and generated-evidence ownership recovery
Areas: runtime-kotlin/runtime-engine/featuretask, runtime-kotlin/runtime-application/review, runtime-kotlin/runtime-infra-fs, runtime-kotlin/runtime-domain/review
- The verification stage boundary is gated on an observed lane disposition. An empty, failed, interrupted, or truncated review pass yields a typed `ReviewVerificationNonSuccess`, preserves prior findings and verdicts, and emits `review_pass_output_absent` instead of reaching the boundary.
- The evidence broker serves an unchanged, identically-scoped repeat read from the same lane and charges `lane_evidence_bytes` once. A repeat that widens projected hunks to a complete file is refused as `evidence_scope_expansion_repeat`; stale-checkpoint, unassigned, and foreign-assignment rejections are untouched.
- Run-evidence ownership derives from the active run's publication address rather than the `.skill-bill/` prefix, so a foreign workflow's artifact and a forged file under the same store root stay actionable.
- Pattern: when a placeholder substitutes for an absent signal downstream, carry the observation from where it was made rather than re-deriving it. reusable
- A failed diagnostic write reuses `RuntimeOwnedPersistenceBoundary.optionalWrite`: it records a bounded local record without marking a stage reached, clearing a finding, or replacing the primary error, so diagnostics cannot fabricate clearance. reusable
- A checkpoint commit that returns an empty sha blocks after index mutation and restores the captured index snapshot; post-commit `recordCommit` and push failures deliberately leave the index alone.
- Limitation: ownership provenance is derived from the deterministic address, not a durable published inventory; preflight/resume re-derivation of runtime-owned ignored or deleted evidence is not covered here.
Feature flag: N/A
Acceptance criteria: 5/6 implemented (AC-4 partial: index restore landed, preflight/resume re-derivation deferred)

## [2026-09-14] SKILL-52.5 subtask 4 — Feature task engine typing
Areas: runtime-kotlin/runtime-engine/featuretask, runtime-kotlin/runtime-core/architecture, runtime-kotlin/runtime-mcp/featuretask
- Replaced raw-map handoffs across feature-task run-loop, verification, settlement, phase-artifact, and MCP adapter boundaries with typed engine models.
- Removed the feature-task cluster from the raw-map architecture allow-list and ratcheted the inventory baseline.
- Pattern: keep map decoding at narrow artifact or wire seams while typed records cross engine boundaries. reusable
- Limitation: goal-runner and remaining application/workflow surfaces are handled by later SKILL-52.5 subtasks.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-09-13] SKILL-241 stateless acceptance-criteria audit
Areas: runtime-engine/featuretask, runtime-engine/goalrunner, runtime-domain/workflow/taskruntime, runtime-application/telemetry, runtime-cli/featuretask, runtime-infra-fs, runtime-infra-sqlite, runtime-ports, runtime-mcp, orchestration/contracts
- Audit checks every planned criterion against implementation and meaningful test coverage, repairs gaps in one fresh session, and restarts the full inspection after interruption. Build and test execution remain with their existing phase owners.
- Removed active audit-gap repair routing, audit-generation storage, progress telemetry, and downstream audit findings projections. Ordinary workflow bookkeeping and process diagnostics remain.
- Resume and status share in-memory normalization of legacy audit records and ledger edges before deriving blocked state, reentries, and budgets. Ledger normalization remains idempotent when records are already normalized. reusable
- Fresh databases omit migration 23 and audit-generation storage; existing databases may retain that legacy storage without audit reading or writing it. Compatibility decoders do not restore audit progress.
- Limitation: superseded by SKILL-242 remaining-AC retries below.
Feature flag: N/A
Acceptance criteria: 10 total in the referenced spec; the upstream receipt does not enumerate an implemented count. Remaining-AC retry semantics are deferred to SKILL-242.

## [2026-09-13] SKILL-242 remaining-AC audit retries
Areas: runtime-engine/featuretask, runtime-domain/workflow/taskruntime, orchestration/contracts, runtime-infra-fs
- Audit prompts request remaining acceptance criteria in `produced_outputs.value`, with explicit `[]` when none remain; SKILL-241 full-list verification, same-session repair, and no nested delegation stay unchanged.
- Completed audit output no longer requires envelope `verdict`; the runtime interprets the final remaining-criteria response, stamps `satisfied` only for an explicit empty list, and retries in fresh sessions when the response is non-blank.
- Each completed audit round commits or amends the subtask commit before the next retry or review advance; in-memory focus hints discard on resume.
- Pattern: keep remaining-criteria text as a transient focus hint while the full planned list remains authoritative on every fresh audit launch. reusable
- Limitation: temporary-repository coverage for first-round commit creation, retry amend, HEAD fallback, and Git-failure blocking remains deferred.
Feature flag: N/A
Acceptance criteria: 7/8 implemented

## [2026-09-12] 0AC-16 — Evidence-backed validation settlement
Areas: orchestration/contracts, runtime-kotlin/{runtime-cli,runtime-contracts,runtime-domain,runtime-engine,runtime-infra-fs}
- Validation settlement now requires schema-valid command identity and integer exit-code evidence, with missing, malformed, or non-zero required results unable to advance completion.
- Evidence persists through settlement and resume, remains readable for supported legacy records, and appears in goal status with visible integrity failures for absent or invalid evidence.
- Pattern: make validation evidence a runtime-owned projection enforced at the producer boundary and reuse the same command validation for live and persisted settlement. reusable
- Known limitations: platform validation commands and routing are unchanged; finalized subtasks are not re-executed automatically.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-09-11] SKILL-233 — last-commit review phase, caller-scoped standalone
Areas: runtime-engine/featuretask, runtime-cli/codereview, application/review, skills/bill-code-review
- Feature-task and goal `review` always diffs `HEAD^..HEAD`. It no longer sends a worktree-from-base packet or a pre-baked diff blob.
- Standalone `skill-bill code-review` / `/bill-code-review` still reviews the caller target: `pr`, a commit SHA, `last`/`HEAD`, or uncommitted work.
- Pattern: phase scope is last commit; named standalone tokens map in the CLI so `pr` is never treated as a git revision. reusable
Feature flag: N/A
Acceptance criteria: N/A (hotfix)


## [2026-09-11] Active subtask owns every dirty path
Areas: runtime-application/featuretask, runtime-domain/workflow/taskruntime
- commit_push and checkpoints stage every non-runtime-private dirty path. Review identity no longer blocks on files missing from the frozen ownership inventory.
- Pattern: the active subtask owns the dirty tree; `.skill-bill/` run-evidence stays unstaged. reusable
Feature flag: N/A
Acceptance criteria: N/A (hotfix)


## [2026-09-11] Validate repair is one occupancy with in-session proofs
Areas: runtime-application/featuretask, runtime-application/featuretask/validation
- Validate `MAX_REPAIR_TURNS` is 1: discovery, one agent occupancy, one POST_REPAIR_VERIFY, then complete or block. Operator resume still resets an exhausted window.
- Repair prompts require iterating targeted proofs in that occupancy and require project-wide `./gradlew spotlessApply` when findings name spotless, ktlint, or format.
- Pattern: runtime-owned collect-all stays after the agent stops; format findings cannot close by indent guesswork without Apply. reusable
Feature flag: N/A
Acceptance criteria: N/A (hotfix)


## [2026-09-10] SKILL-236 — write_history and commit_push stay forward-only
Areas: runtime-application/featuretask, runtime-domain/workflow/taskruntime
- Removed the commit_push stale-review reaudit path that wiped audit/review and bounced the drive loop back to audit after implement_fix left owned dirty files.
- Identity and finalisation treat owned implement/implement_fix plus declared boundary-history as eligible to stage; matching subtask trailer on HEAD is still authoritative across tree drift. Foreign dirty blocks needs_human and does not reopen earlier phases.
- Shipped topology keeps only `audit_gap` and `review_fix` backward edges; `write_history` and `commit_push` never originate a reopening.
- Pattern: post-review owned repair is absorbed at finalisation, not by replaying the pipeline. reusable
Feature flag: N/A
Acceptance criteria: 2/2 implemented


## [2026-09-01] SKILL-228 subtask 1 — Validate/build needs_user_action operator block
Areas: runtime-application/featuretask, runtime-application/featuretask/validation, runtime-application/goalrunner
- Validate and build `blocked` + `failure_disposition: needs_user_action` settle once as terminal operator blocks: phase record + ledger before any parent-PID hold; no repair-turn increment or gate relaunch.
- `terminalOutputAttempt` treats NEEDS_USER_ACTION as non-retryable; `gateOutputEarlyExit` keeps repair-segment completion from swallowing the operator path; `isRecoverableValidationBlock` excludes that disposition so disposition-less blocked validate still repairs.
- Goal continuation / stop reports thread non-empty `blocked_reason` from summary and `blocking_reasons` so `skill-bill goal status` shows the operator block without chat.
- Pattern: explicit disposition owns terminal vs repair; omit disposition to keep the existing validate repair loop. reusable
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-08-29] SKILL-219 subtask 2 — Briefing-only triage for unparseable gate blobs
Areas: runtime-application/featuretask, runtime-application/featuretask/validation, runtime-domain/workflow/taskruntime
- When collect-all still yields exactly one `unparseable_gate_failure`, validate and build cycles insert one triage agent turn before repair turn 1; discrete finding sets skip triage.
- Triage emits recommended-shape `validation_repair_plan` prose (not schema-gated). Soft-parse keeps `produced_outputs.value` / plan capture for repair briefing; repair-turn stdout still short-circuits without folding triage into that discard path.
- Triage prompts forbid gate/quality-check runs with repair-prompt strength; missing or empty triage does not block repair. Gate verify remains the only repair proof.
- Pattern: optional briefing-only triage ahead of the existing three-turn repair window, without durable triage artifacts or receipt/coverage gates. reusable
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-28] SKILL-217 subtask 1 — Retarget finalization consumers onto phase_prose
Areas: runtime-domain/workflow/taskruntime, runtime-application/featuretask (tests)
- Validate, build, write_history, commit_push, and pr take producer words through `phaseProseDeclaration` (`feature_task_runtime.phase_prose`). A value-only implement still launches those consumers; briefings carry `value` and optional `prompt`.
- `finalizationProjectionContext` no longer reads implement `changed_paths` / `completed_task_ids` / `tests_*` / `deviations` or plan `validation_strategy` / `tasks`. Path inventory is checkpoint `workingTreeOwnedPaths` only. Kotlin does not parse `value`.
- Dropped `pr_request` fields `completed_task_ids`, `tests_added`, `tests_updated`, `deviations`; dropped `commit_request.required_exclusions` and `validation_request.required_checks` / `validation_strategy`. No new handoff contract id.
- Pattern: reuse `phase_prose` for agent-facing words; keep measurement (paths, checkpoints) runtime-owned. reusable
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-28] SKILL-216 subtask 1 — Slim verify and fix to an id-enum census
Areas: runtime-application/featuretask, runtime-domain/workflow/taskruntime, runtime-infra-fs/contracts/workflow, orchestration/contracts, runtime-contracts, runtime-infra-sqlite
- Slimmed `verify_findings` dispositions to required `finding_id` + `disposition`. Extra keys, including the former fat fields, are ignored. Envelope `verdict` is the sole routing signal. Kotlin no longer derives it from the census or falls back to ADVANCE.
- Slimmed `implement_fix` repair-receipt entries to required `finding_id` + `outcome` (nested contract 0.3). Dropped construct-symbol, intent, and reason-pattern gates. Coverage still keys on ids. Unresolved-twice still blocks.
- Rejected ledger rows copy identity from the review finding. Optional census `reason` persists or truncates. A missing reason does not retry. Body delivery still runs when headings are selected.
- Envelope contract 0.6 and nested receipt 0.3. Pre-bump records loud-fail and regenerate in-band. sqlite CHECK rebuilds accept 0.6.
- Pattern: where the runtime already owns the set, gate a census of ids and enums, not grammar. reusable
- Limitation: neither phase `$ref`s `phase_prose`. Finalization receipts stay measurement versus grammar, not converted onto prose by default.
Feature flag: N/A
Acceptance criteria: 12/12 implemented

## [2026-08-28] Validate repair parses detekt and uses checklist repair
Areas: runtime-infra-fs/validation, runtime-application/featuretask, platform-packs/kotlin, platform-packs/kmp
- Validation gate COLLECT_ALL now unions detekt XML under `build/reports/detekt`, detekt/spotless stdout lines, compiler diagnostics, and JUnit artifacts so repair turns get discrete findings instead of one `unparseable_gate_failure` blob.
- Kotlin and KMP packs declare the detekt report glob on `validation_gate.findings.artifact_globs`.
- Validate repair prompts require unconditional project-wide `./gradlew spotlessApply` at turn start, a numbered checklist worked one item at a time with targeted detekt/ktlint/compile/test proof, and explicit detekt-threshold refactor guidance (no @Suppress).
- Pattern: cheap format preflight plus discrete findings plus checklist repair before the three-turn cap. reusable
Feature flag: N/A
Acceptance criteria: n/a (main hotfix)

## [2026-08-28] SKILL-214 — Prose-centric audit phase I/O
Areas: runtime-application/featuretask, runtime-application/model, runtime-domain/workflow/taskruntime, runtime-infra-fs/contracts/workflow, orchestration/contracts, runtime-contracts, runtime-infra-sqlite
- Extended `phase_prose` to audit: preplan, plan, implement, and audit share one `value`/`prompt` shell; former `gaps` / `non_blocking_findings` JSON is stuffed inside `value`.
- Envelope `verdict` (`satisfied` | `gaps_found`) stays required and schema-gated; it is the sole routing signal for review entry, `audit_gap`, and `audit_clearance`. Kotlin no longer re-checks it.
- Dropped gap-array parse, criterion-ref scrape, sticky ids, `auditVerificationSignal`, and dead `FeatureTaskRuntimeStrictWireMapping.kt`. Extra keys beside `value` are ignored.
- Prior-gap memory is `round` plus prior audit `value` strings; implement gap re-entry reads audit `value` verbatim. Recurrence re-justification is agent-side.
- Phase-output contract bump to 0.5 rebuilds sqlite `goal_subtask_plans` / `goal_shared_preplans` so the CHECK accepts `0.5`. reusable
- Pattern: one prose kit through audit; keep only the envelope field the runtime routes on. reusable
- Limitation: `verify_findings` and `implement_fix` stay on coverage-gated payloads; runtime does not parse audit `value`.
Feature flag: N/A
Acceptance criteria: 15/15 implemented

## [2026-08-27] SKILL-213 — Prose-centric implement phase I/O
Areas: runtime-application/featuretask, runtime-domain/workflow/taskruntime, runtime-infra-fs/contracts/workflow, orchestration/contracts, runtime-cli, runtime-core
- Extended `phase_prose` to implement: preplan, plan, and implement share one `value`/`prompt` shell; former receipt JSON is stuffed inside `value`.
- Dropped gated `implementation_receipt`, `regenerate_implement`, receipt-shaped completion gates, and mutating-reconciliation for implement; audit consumes raw implement `value`.
- Finalization paths (validate, write_history, commit) take changed paths from repository checkpoint inventory, not receipt fields.
- Pattern: one prose kit through implement → audit; attempt persistence and continuation rebuild from stuffed `value` segments. reusable
- Limitation (superseded by SKILL-214 for audit): later phases stay on their own contracts; runtime does not parse `value` at prose handoffs.
Feature flag: N/A
Acceptance criteria: 14/14 implemented

## [2026-08-27] SKILL-212 — Prose-centric plan phase I/O
Areas: runtime-application/featuretask, runtime-domain/workflow/taskruntime, runtime-infra-fs/contracts/workflow, orchestration/contracts, runtime-cli, runtime-core
- Generalized SKILL-211 preplan prose into phase-neutral `feature_task_runtime.phase_prose` (`value`, optional `prompt`); preplan and plan `$ref` one `$defs` shape.
- Dropped gated `executable_plan` / `plan_commitment`, `regenerate_plan`, and plan-task-id closure on implement; audit and audit-gap consume the same prose kit.
- Decompose stop requires `produced_outputs.decomposition_package`; leftover `mode: decompose` beside `value` is a plan handoff.
- Pattern: one declaration helper + decoder for every prose planning edge; later matrix rows reuse `phase_prose` without a new contract. reusable
- Limitation (superseded by SKILL-213 for implement): plan prose is not parsed for task ids; later non-prose phases stay on their own contracts.
Feature flag: N/A
Acceptance criteria: 11/11 implemented

## [2026-08-27] Salvage spaced repair-receipt construct symbols
Areas: runtime-domain/workflow/taskruntime, runtime-infra-fs/contracts/workflow, runtime-application/featuretask, orchestration/contracts
- Shape alignment coerces `Type.<spaced display name>` construct symbols down to `Type` before the phase-output schema gate; decode applies the same salvage.
- implement_fix prompts and the schema description forbid spaces and Kotlin backtick / JUnit display names in `symbol`.
- Pattern: keep the compact-symbol regex; salvage the predictable Kotlin test-name near-miss instead of burning the one-shot output-gate budget. reusable
Feature flag: N/A
Acceptance criteria: n/a (main hotfix)

## [2026-08-27] SKILL-211 — Prose-centric preplan phase I/O
Areas: runtime-application/{featuretask,goalrunner,workflow}, runtime-domain/{agent/model,workflow/taskruntime}, runtime-infra-fs/contracts/workflow, orchestration/contracts
- Preplan `produced_outputs` is now prose `PhaseOutput` (`value`, optional `prompt`); the planning-projections digest schema gate and `regenerate_preplan` consumer edge are gone.
- Plan briefings carry `value`/`prompt` text; goal-sweep `_goal_planning_shared_context` still round-trips; stale shared-preplan refresh hashes prose fields instead of selected headings.
- Pattern: first planning edge uses an ungated prose handoff while later phases keep bounded projection contracts. reusable
- Limitation: plan `executable_plan` and later edges are unchanged; heading bodies are not auto-resolved from preplan prose.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-08-27] Validate repair uses project-wide spotlessApply
Areas: runtime-application/featuretask
- Validate Task and findings preamble tell the agent to run `./gradlew spotlessApply` once at the Gradle root for format findings, never `:module:spotlessApply`.
- Module-scoped apply left open Spotless violations in other modules after three repair turns (SKILL-211).
- Pattern: format repair is cheap project-wide; do not trust the agent to pick the failing module from an unparseable gate dump. reusable
Feature flag: N/A
Acceptance criteria: n/a (main hotfix)

## [2026-08-25] Goal execution liveness falls back to the parent lease
Areas: runtime-application/goalrunner
- When a child workflow exists but its worker lease is missing or expired, status reads the parent goal execution lease before reporting idle.
- Prevents the IDE from projecting paused while the goal runner is still live and driving a phase without a fresh child lease.
- Pattern: child LIVE/UNKNOWN wins; child IDLE falls through to parent ownership. reusable
Feature flag: N/A
Acceptance criteria: n/a (main hotfix)

## [2026-08-25] Validate repair Task forbids re-running collect-all
Areas: runtime-application/featuretask
- Runtime-owned validate repair turns use `validateRepairPhaseTask` as the Task line when findings are projected; it forbids `bill-code-check`, pack collect-all, and `check --continue`.
- Findings preamble matches build: fix the listed set; targeted checker tasks only; no rediscovery via full suite.
- Agent-run validate fallback (no pack `validation_gate`) keeps the collect-all Task unchanged.
- Pattern: Requested/Task text for repair must not invite a second full gate, or agents buffer `check | tail` and stop editing. reusable
Feature flag: N/A
Acceptance criteria: n/a (main hotfix)

## [2026-08-24] SKILL-207 subtask 1 — Phase I/O envelope and prose review handoff
Areas: runtime-application/{featuretask,model,review}, runtime-domain/{agent,review}, runtime-cli/codereview
- Added shared `AgentPhaseInput` and `AgentPhaseOutput` string envelopes for phase handoffs.
- Review driver, runner, and CLI preserve worker-returned prose as authoritative output; register parsing remains optional diagnostics, so near-miss lines survive.
- Typed launch dependencies remain on launch plumbing outside the phase string fields.
- Pattern: preserve full worker prose at the phase boundary while keeping structured register parsing non-authoritative. reusable
- Limitation: claim verification and governed skill-content rewrites remain subtask 2; plan, implement, and validate do not use the envelope.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-08-23] SKILL-202 subtask 3 — scoped boundary memory for verification
Areas: runtime-application/{featuretask,goalrunner,review}, runtime-domain/{review,taskruntime}, runtime-infra-fs/goalplanning, runtime-ports/goalrunner, runtime contracts, runtime-cli/goal, governed feature-task skills
- Verification now discovers boundary memory by title first and scopes it to boundaries owning each finding's paths; excluded roots contribute nothing and unmatched paths stay intent-only.
- Verification-specific discovery and body caps are declared below planning caps; the shared maximum boundary-file size remains unchanged, and over-budget resolution loud-fails without truncation.
- Dispositions persist selected heading ids with source paths and expose that provenance through goal findings; unselected history and decision bodies never enter prompts.
- Pattern: reuse GoalPlanningContextDiscovery and GoalPlanningBoundaryBodyResolver with a path-scoped, titles-first projection. reusable
- Limitation: findings with no eligible owning boundary retain intent-only verification and record unavailable boundary context.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-22] SKILL-205 subtask 2 — Prior-gap memory for continuing audit_gap rounds
Areas: runtime-application/featuretask, runtime-application/goalrunner, runtime-domain/workflow/taskruntime/model, orchestration/contracts, platform-packs/{kotlin,kmp}/quality-check
- Added bounded durable `prior_gap_memory` to audit-gap handoffs, retaining unmet criterion refs/notes and subsequent implement claims while degrading legacy workflows to empty memory.
- Implement re-entry prioritizes sticky unmet criteria while still closing every current gap; follow-up audit requires explicit re-justification for repeated sticky ids and rereads the repository as authority.
- Schema-first handoff projection and envelope contracts are validated at producer and consumer seams; Kotlin and KMP quality-check guidance stays aligned. reusable
- Pattern: carry only bounded criterion memory across remediation edges, deriving it from durable audit and implement receipts rather than treating implement claims as proof.
- Limitation: memory improves audit_gap retries only; subtask 1's no-progress comparison and pause policy remain authoritative.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-08-22] SKILL-205 subtask 1 — No-progress and warn-threshold pause for audit_gap
Areas: runtime-application/featuretask, runtime-application/goalrunner, runtime-domain/workflow/taskruntime/model, runtime-domain/config/model
- `audit_gap` re-entry now compares the new unmet criterion set against the prior round's: any cleared prior criterion ref is progress; a non-shrinking set with an unchanged (or unprovable) repository mints a durable no-progress pause instead of another implement launch.
- Crossing `warnAfterIterations` (iteration 4 at threshold 3) is control flow now: it pauses for an operator decision; the advisory side channel still emits.
- `FeatureTaskRuntimeAuditGapPause` durable artifact carries `pause_kind` (no_progress | warn_threshold), `edge_iteration`, `operator_decision`, `grant_consumed`; the recorder gains load/persist seams for the progress and pause artifacts and canonical criterion-ref extraction moves into output verification.
- Goal operator decision handles an audit-gap pause without review state: `retry_fix` persists the decision, resume settles the paused audit from carried-forward output and consumes the grant for exactly one further attempt; `abandon_subtask` consumes the grant; re-pausing clears `operator_decision` so a second no-progress or threshold condition pauses again.
- Status and blocked reasons source from the pause artifact, so no-progress and warn-threshold pauses read distinct from output-gate/schema failures; a consumed grant is no longer an active pause.
- Progress detection fails closed when the previous repository fingerprint is unproven (`UNPROVEN_REPOSITORY_FINGERPRINT`), so an unchanged audit cannot pass as progress when change cannot be proven.
- Reusable: durable pause artifact + single-grant operator-decision loop for any unbounded remediation edge; fails-closed shrink-or-fingerprint progress comparison.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-08-22] SKILL-204 subtask 1 — First-class build phase and pack build command
Areas: runtime-application/featuretask, runtime-domain/workflow/taskruntime, runtime-infra-fs/contracts/workflow, orchestration/contracts, platform-packs/{kotlin,kmp}, AGENTS.md
- Added a real `build` phase between clean review and write-history, with runtime-owned discover → repair → confirm gate execution.
- Platform validation gates now declare a build command; Kotlin and KMP use compile/buildability argv distinct from their collect-all full gate.
- Settled build output projects a schema-validated `build_receipt`, while downstream receipt acceptance remains deferred to subtask 2.
- Build prompts and repository guidance prohibit suite tests, full checks, substitute agent-run gates, and delegated subagents; targeted repair commands remain allowed.
- Pattern: keep pack command ownership in manifests and share typed gate/projection validation across launch and settlement. reusable
- Limitation: goal-child routing still defaults to `review → validate`; selecting `build` for intermediate children is subtask 2.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-08-21] SKILL-202 subtask 2 — verify_findings phase
Areas: runtime-application/{featuretask,goalrunner}, runtime-domain/taskruntime, runtime-infra-sqlite, runtime-cli/goal, runtime-infra-fs/install, runtime contracts, governed feature-task skills
- Added the declared `verify_findings` phase between review and implement_fix; each review finding receives one bounded `verified` or `rejected` disposition against the spec intent projection.
- Verified findings of every severity drive the single capped implement_fix round; rejected findings retain their reason and severity in the unaddressed-findings ledger and are never fixed.
- Pattern: keep phase topology, entry gates, durable records, status, telemetry, CLI, and governed prompts aligned around one review pass and one fix round. reusable
- Durable verification records and retired values loud-fail rather than being coerced; resume reuses in-flight dispositions without rerunning review.
- Limitation: verified findings that remain after the one fix round proceed to validate under the accepted bounded-remediation trade-off.
Feature flag: N/A
Acceptance criteria: 15/15 implemented

## [2026-08-21] SKILL-202 — single-pass review remediation
Areas: runtime-application/featuretask, runtime-application/goalrunner, runtime-domain/taskruntime, runtime contracts, governed feature-task skills
- Review remediation is one `implement_fix` round: `review --changes_requested` uses a per-subtask cap of one and advances to validate after that round.
- Removed `plan_fix`, second-round escalation, churn, and unresolved-finding pause vocabulary from runtime projections, durable review state, transition wiring, and prompts.
- Pattern: declare bounded remediation edges in workflow topology and keep prompts, persistence, schemas, and projections aligned. reusable
- Durable records naming retired phase, verdict, or ledger values loud-fail rather than being coerced; audit-gap and rejected-output loops retain their independent behavior.
Feature flag: N/A
Acceptance criteria: 11/11 implemented

## [2026-08-20] Validate runs only the pack collect-all command
Areas: runtime-application/featuretask (validate directives, prompt composer, run loop), AGENTS.md, skills/bill-feature-task-runtime
- Validate briefing names the pack `collect_all_full_gate_command` and forbids `skill-bill validate`, `npx agnix`, `scripts/validate_agent_configs`, and `bill-code-check`.
- The launch seam interpolates the declared argv into the Task line so the session sees the exact command.
- Pattern: platform pack owns the validate command set; repo-root checklists stay maintainer-only. reusable
Feature flag: N/A
Acceptance criteria: 1/1 implemented

## [2026-08-20] Validate agent runs collect-all, fixes, then confirms
Areas: runtime-application/featuretask (validate directives, prompt composer, projection shapes), AGENTS.md, skills/bill-feature-task-runtime, skills/bill-code-check, platform-packs/kotlin and kmp quality-check
- Validate briefing tells the single session to run the pack collect-all gate, read that output, fix every finding, then run one confirmation check. Parsed runtime findings are a hint, not a substitute for the log.
- Repair windows no longer forbid Gradle during repair; they forbid rerunning the full gate after each individual finding. Targeted compile/test stays allowed.
- Pattern: collect-all once, repair the set, confirm once, in the same process. reusable
Feature flag: N/A
Acceptance criteria: 1/1 implemented

## [2026-08-20] SKILL-190 subtask 6 — Checkpoint-ref prune lifecycle and docs
Areas: runtime-application/featuretask, runtime-application/goalrunner, runtime-ports/workflow, AGENTS.md, orchestration/workflow-contract/PLAYBOOK.md, skills/bill-feature-task-runtime
- `FeatureTaskRuntimeCheckpointRefPrune` deletes refs under `refs/skill-bill/checkpoints/<issue>/<subtask>/` only after push plus a recorded manifest `commit_sha`; hard reset bypasses the gate; pruning is idempotent.
- Goal runner prunes on subtask completion and on status reconcile for complete subtasks; hard reset prunes every reset subtask namespace.
- `commit_push` finalisation defers prune until the manifest records `commit_sha`; agents emit message and paths only.
- Pattern: gated ref lifecycle paired with runtime-owned finalisation; reset-driven namespace bounding. reusable
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-08-20] SKILL-190 — Completed-upstream-missing-output goal repair wedge
Areas: runtime-application/featuretask, runtime-application/goalrunner, runtime-application/model, runtime-cli/goal
- Added `COMPLETED_UPSTREAM_MISSING_OUTPUT` goal repair wedge: blocked consumers missing upstream projections diagnose `completed` phase rows with no settled output
- `FeatureTaskRuntimeCompletedUpstreamRepair` reopens the earliest unsettled upstream and dependent blocked phases via operator-resume repair input; `asPendingForOperatorResume` lives in `FeatureTaskRuntimePhaseRecordRepair`
- `GoalRunnerChildRepairApplyResult` carries manifest projection artifacts; outcome store writes decomposition projection after `updateGoalParentForBlockedPhaseRetry`
- `RemediationBaseReconciler` quarantines superseded checkpoint-identity stores to sibling evidence and clears the live key so children regenerate identities instead of dying on version errors
- Pattern: operator `goal repair` wedge for durable phase-record inconsistencies that otherwise strand children with nonzero exit and no recovery path. reusable
- Limitation: runtime-owned `commit_push` finalisation from subtask 5 spec remains outstanding on this branch checkpoint
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-08-19] SKILL-190 subtask 4 — Ref-based remediation reconciliation and rollback under amend
Areas: runtime-application/featuretask (goal continuation recorder, run loop), runtime-kotlin/agent, runtime-ports/workflow
- `reconcileRemediationBaseCoherence` resolves the latest `review_fix` base through checkpoint refs instead of branch ancestry; unresolvable bases block with operator guidance rather than rewriting to HEAD.
- `rollbackRemediationCheckpointCommit` restores the prior checkpoint ref (or removes the first subtask commit) and no-ops when HEAD already moved; compensating rollback no longer soft-resets to `parentSha` alone.
- Durable `goal_review_base_recoveries` evidence carries seam, value used, value expected, and cause on blocked reconciliation and stored-base misses.
- Reusable: ref-resolved remediation base + typed blocked outcome at goal-child resume; ref-based compensating rollback paired with subtask 3 amend ceremony.
- Limitation: none for this bundle; pair with integrated verification before treating production-safe.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-19] SKILL-190 subtask 3 — Runtime-owned subtask commit identity and amend ceremony
Areas: runtime-application/featuretask (run loop, commit resolver, checkpoint scope), runtime-domain/workflow/taskruntime/model, runtime-infra-fs (GitCheckpointHistoryOperations), runtime-ports/workflow
- Forward and remediation checkpoints now create-or-amend exactly one subtask commit instead of appending branch commits; Skip and Block verdicts still write nothing.
- `FeatureTaskRuntimeSubtaskCommitResolver` decides create vs amend from durable identity first, falling back to the `Skill-Bill-Subtask: <issue>/<subtask-id>` trailer on HEAD with an observability record when state is unavailable. reusable
- Provisional subject comes from the manifest subtask `name`; `phase`, `loop`, and `generation` move to the commit body alongside the trailer, retiring the old single-line checkpoint subject.
- Before each amend, the pre-amend commit is written to `refs/skill-bill/checkpoints/<issue>/<subtask>/<sequence>` and must resolve before amend runs; ref failure blocks the checkpoint loudly.
- Pattern: extend subtask 1 amend/ref primitives through the checkpoint write path without changing ceremony dispatch or scope verdicts; checkpoint-identity idempotency from subtask 2 is preserved in the same transaction.
- Limitation: none for this bundle; pair with integrated verification before treating production-safe.
Feature flag: N/A
Acceptance criteria: 12/12 implemented

## [2026-08-18] SKILL-198 subtask 2 — runtime repair window owns check execution
Areas: runtime-application/featuretask (validation coordinator, policy, cycle models), runtime-domain/workflow/taskruntime/model, AGENTS.md
- Durable `repair_window_phase` (`none` | `findings_open`) on validation gate progress; while `findings_open` the coordinator runs zero pack argv and resume hands back the persisted complete finding set without a discovery rerun.
- Gate cycle phases are `INITIAL_DISCOVERY` then `POST_REPAIR_VERIFY` only; `full_gate_command` is never an intermediate repair argv; leaving `findings_open` requires the agent repaired signal, then exactly one cache-bypassing collect-all or build-only verify.
- `validationGateArgv` maps discovery to collect-all or build-only and verify to cache-bypassing counterparts; a failing verify replaces the finding set and re-enters `findings_open` without targeted module commands.
- Pattern: runtime-owned repair window aligned with subtask 1 briefing — agent edits only during `findings_open`, runtime is the sole declared-gate executor. reusable
- Limitation: agent-run fallback without `validation_gate` stays prompt-only; arbitrary shell Gradle is not intercepted
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-18] SKILL-198 subtask 1 — validate briefing repair-window contract
Areas: runtime-application/featuretask (validate directives), AGENTS.md
- Runtime-owned FULL, BUILD_ONLY, and agent-run fallback validate briefings declare an open finding set as a repair window: no gate, `bill-code-check`, targeted Gradle tasks, pack checkers, or subagent checks until every finding is repaired; verification is one cache-bypassing gate after the batch.
- `AGENTS.md` validate paragraph aligned: dropped "no cache-bypassing confirmation pass", named `detekt`/`ktlintCheck`/`test`/`compileKotlin`, and states BUILD_ONLY runs outside an open repair window only.
- `FeatureTaskRuntimePhasePromptComposerTest` pins forbidden-command phrases in composed validate prompts; `MaintainerPolicyRepairWindowParityTest` locks `AGENTS.md` and `CLAUDE.md` to the same vocabulary.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-08-17] SKILL-191 subtask 9 — Feature-task review phase delegation
Areas: runtime-application/featuretask (run loop, review driver, prompt directives, briefing assembler), runtime-core/di, runtime-application tests
- `PHASE_REVIEW` stopped being an agent phase: `runDeclaredReviewDriverCycle` prepares the pass, mints or reuses the `review_run_id`, pins the repository checkpoint, and invokes `FeatureTaskRuntimeReviewDriver` — bound in `RuntimeComponent` to `ParallelCodeReviewRunner::run`, the same driver the standalone entry uses
- A reused `review_run_id` is now claimed only when the durable record belongs to the same review pass, so a second pass mints its own id instead of inheriting pass one's
- `FeatureTaskRuntimeReviewEnvelope` assembles `produced_outputs.findings` and `review_run_id` from the driver result only, strips criterion-gap keys (`unmet_criteria`, `gaps`, `failing_criteria`), and adds `blocker_dispositions` from pass two onward
- The review directive now states the runtime owns the review; the briefing allowlist keeps `ACCEPTANCE_CRITERIA` out of the review phase, so criterion-gap detection stays exclusive to audit
- The mapper pins `ParallelReviewScope.UNSTAGED` with the caller-supplied diff, base and head revisions from `GoalSubtaskReviewInput`, and the governed spec path, so no stage rediscovers a baseline and adjudication always runs
- Reusable: the validate-phase precedent of runtime-run gate plus bounded agent projection, now applied to review
- Limitation: `code_review_mode` resolution and continuation conflict rejection are unchanged; pass two still forces `INLINE` via `FeatureTaskRuntimeReviewPassSequence.resolveForPass`
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-08-15] SKILL-192 subtask 3 — Repair plan, substantiation receipts, confirmation closure
Areas: runtime-application/featuretask/validation, runtime-domain/workflow/taskruntime/model, runtime-ports/validation, AGENTS.md, runtime-kotlin/agent
- FULL collect-all no longer treats a completed repair payload as proof: persist a covering repair plan, require a substantiation receipt per discovery identity, then run one confirmation collect-all
- Green confirmation is identity closure (every discovery identity absent from the confirmation finding set), not measured PASSED; leftover identities fail substantiation and stay in the next set; new confirmation identities join that set without fail-fast rediscovery
- Incomplete plan or receipts reject the same repair pass without a new gate run (producer-side gate, same validator as launch); grouped plan items still close every identity
- Pattern: suite proof stays one collect-all confirmation; never substantiate via pack full gate or per-test Gradle. BUILD_ONLY stays compile/build-only without receipts or closure. reusable
- Limitation: identity key remains exact module|ruleOrTestId|message|location; additive plan/receipt keys decode empty without a persistence version bump
Feature flag: N/A
Acceptance criteria: 12/12 implemented

## [2026-08-15] SKILL-192 subtask 2 — FULL validate discover, plan, repair-all, confirm
Areas: runtime-application/featuretask (coordinator, policy, projector, prompts), runtime-domain/workflow/taskruntime/model
- FULL validate replaced unbounded fail-fast `full_gate_command` looping with collect-all discovery, persist-complete-set, one repair pass over the entire set (or pages without a gate rerun), then cache-bypassing collect-all confirmation
- A cache-eligible green discovery is never terminal; confirmation with zero executed work never satisfies; a failed confirmation's complete set is the next repair input with no extra discovery; retry-cap exhaustion blocks with remaining findings
- BUILD_ONLY stays on `build_only_command` cache-eligible then forced-full attestation; goal last-child FULL vs intermediate BUILD_ONLY is unchanged
- Pattern: pack collect-all argv owns continue-on-failure and cache-bypass; coordinator never falls back to fail-fast FULL when collect-all exists. reusable
- Limitation: repair-plan receipts and confirmation identity closure are subtask 3; this cycle treats a completed repair launch as pass-attempted
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-13] SKILL-186 subtask 2 — Quarantine identity integrity
Areas: runtime-application/featuretask (recorder, run loop), runtime-application/model, runtime-domain/workflow/taskruntime/model, orchestration/contracts, runtime-contracts, runtime-infra-fs (schema validator)
- `recordRejectedOutput` returns `Written(identity)` only after the evidence transaction commits, or `Degraded(failureClass)` when it rolled back — never a `rod_` token for a row that does not exist
- Quarantine append records that outcome as exclusive wire fields: identity when the write landed, `diagnostic_degraded: true` (identity omitted) when it degraded; regeneration still fires; the store stays append-only
- Contract 0.3 stays: additive `diagnostic_degraded` const-true mutually exclusive with identity so pre-change identity-only entries still decode; `false` and undeclared envelope/entry fields loud-fail instead of being dropped
- Reusable: typed write-outcome at the persistence seam so a caller cannot persist an identity the store never wrote; exclusive optional fields over a version bump when old records must remain readable
- Limitation: already-persisted dangling identities are not repaired (spec non-goal)
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-12] SKILL-186 subtask 1 — Degraded diagnostic-persistence operator seam
Areas: runtime-application/featuretask (recorder, run loop, status), runtime-application/model, runtime-domain/workflow/taskruntime/model, runtime-ports/persistence, runtime-infra-sqlite/db/telemetry, runtime-cli/featuretask, docs
- `degradeDiagnosticFailure` now emits a content-free `FeatureTaskRuntimeDiagnosticDegradationMeasurement` through `LifecycleTelemetryRepository` beside the durable `FeatureTaskRuntimeDiagnosticSignal`; a throwing sink still appends the signal, returns null, and lets the run proceed
- Measurement pins contract 0.1, `toTelemetryMap()` keys are parity-tested, and the map is on the `@OpenBoundaryMap` allow-list; `repair_turn` is omitted when the failure was not scoped to one turn
- `producerOutput` returns `Found` / `Absent` / `Unreadable(failureClass)` instead of collapsing store refusal into null; the producer-evidence block names "no retained evidence" vs "store refused it" with the typed class, keeping `NEEDS_USER_ACTION` and `childNeverLaunched`
- Status projection reports degraded-signal count plus latest failure class/phase/attempt, or null when none exist; a malformed durable list still loud-fails; CLI `status` surfaces the same object
- Reusable: typed read-result over a nullable that hid two causes; separate-transaction telemetry so a throwing sink cannot roll back the operator signal
- Limitation: `persistDiagnosticSignal`'s best-effort catch stays silent; quarantine `diagnosticIdentity` dangling-pointer is subtask 2
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-12] SKILL-187 subtask 3 — Regression and conformance coverage
Areas: runtime-application/featuretask (integration + privacy tests, RealPhaseOutputValidator fixture), runtime-infra-fs (structural-repair + schema-validator tests), runtime-domain/workflow/taskruntime/model, runtime-contracts
- Synthetic SKILL-16 audit sentinels (nested root verdict, unauthorized observation enum, compound/oversized artifact_ref, missing-delimiter-then-schema) assert exact capture in the authorized repair section and payload-free cues; corrected envelopes advance
- Privacy helpers split surfaces: raw body allowed only inside the untrusted repair section; blocked reasons, durable phase rows, status, telemetry, and normal logs stay payload-free while private diagnostics keep sentinel bytes and value-bearing reasons
- First/schema-valid/incomplete/phase-mismatched launches omit the repair section; truncated/oversized/YAML-unsupported/degraded-observer paths keep Exact vs fallback classification without silent truncation or outcome flips
- Reusable: shared privacy assertions + RealPhaseOutputValidator so gate and consumer tests share one validator without copying real rejected payloads
- Limitation: suite uses synthetic sentinels only; does not widen durable storage or public raw-output readers
Feature flag: N/A
Acceptance criteria: 13/13 implemented

## [2026-08-12] SKILL-187 subtask 2 — Thread rejected response into corrective re-spawn
Areas: runtime-application/featuretask (run loop, observability, prompt composer, validation gate), runtime-infra-fs (phase-output validator adapter), runtime-domain/workflow/taskruntime/model, runtime-contracts
- gateOutput / settleValidatedOutput reject paths build FeatureTaskRuntimeCorrectiveRepairContext from the same capture metadata and diagnostic identity recorded privately; Exact digests prefer capture-boundary sha/bytes
- settleMalformedOutput and semantic retries carry that context through PriorAttemptCorrection into FeatureTaskRuntimePhasePromptComposer; retryable-terminal and incomplete-work paths stay separate and never render the raw repair section
- Schema rejection after successful delimiter repair retains payload-free structuralRepairEvidence on Rejected and sets acceptedAfterStructuralRepair; the retry prompt names syntax-only repair without claiming phase-schema acceptance
- Shared emitFeatureTaskRuntimeEventSafely isolates event-sink / ValidationGateProgress / RunStarted observer faults (CancellationException still propagates) so throws cannot abort retry, block, or completion or leak rejected bodies
- Integration coverage: capture↔diagnostic correlation, first-launch/stale-context routing, truncated capture fallback, enum/artifact_ref cases, delimiter-then-schema, exhaustion INVALID_OUTPUT privacy, throwing observers
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-12] SKILL-187 subtask 1 — Corrective-repair context and safe prompt projection
Areas: runtime-domain/workflow/taskruntime/model, runtime-application/featuretask (prompt composer, directives, run loop)
- Added versioned in-flight `FeatureTaskRuntimeCorrectiveRepairContext` (contract 0.1) with phase/attempt/repair-turn, payload-free constraint, diagnostic locator, response digest/byte count, and closed availability + inclusion-reason enums — no Jackson, SQLDelight, or diagnostic-store types cross the seam
- Response states stay exact vs already-truncated vs over-budget vs unavailable; UTF-8 and collection budgets are named constants validated before render so a non-exact body is never labeled exact
- Composer projects the exact response only inside an authorized untrusted repair section (delimiter-safe against fences/braces/YAML/Unicode); required output contract and payload-free guidance stay outside; unavailable/truncated/oversized paths fall back to the opaque diagnostic locator without a misleading excerpt
- Run loop builds the context for schema-invalid corrective retries only; privacy coverage keeps value-bearing validator text, secrets, and raw output out of non-authorized surfaces
- Reusable: typed repair-context + untrusted-section prompt projection for any schema-gate retry that must show prior output without treating it as instructions
- Limitation: context is non-durable and does not add a public raw-output reader or change structural-repair / retry-cap semantics (those remain later subtasks)
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-11] SKILL-177 — Test-value discipline directive
Areas: runtime-application/featuretask (prompt directives + composer)
- Added `testValueDisciplineDirective(phaseId)` beside `minimalismDisciplineDirective`: titled write-time test-value bar for plan, implement, and implement_fix only (dedicated phase set — plan is not mutating)
- Six contracted elements: nameable realistic bug first; critical-path concentration; behavior-at-boundaries / no structure-coupled tests; one strong test per rule; planning `test_obligations` only when the bar passes (empty list valid); never omit real-bug regressions or governed parity / validator-backed rules
- Composer inserts the new section immediately after minimalism; other directive content and order unchanged; prompt-only (no schema / receipt / validate-gate changes)
- Pattern followed: review-time `bill-unit-test-value-check` bar lifted into write-time phase prompts so plan and mutating agents inherit the same discipline
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-10] SKILL-178 subtask 3 — Human-resumable non-convergence pause
Areas: runtime-application/featuretask, runtime-application/goalrunner, runtime-domain/workflow/taskruntime/model, runtime-cli/goal
- Same unresolved advance-blocking set (Blocker or Major) across consecutive remediation passes with an unchanged reviewed-delta digest mints `PAUSED` via `pauseForNonConvergence` instead of re-entering `implement_fix`; an active retry grant suppresses that pause for one transition
- Goal-facing pause reasons carry severity, count, and sanitized labels only — paths, line numbers, and hunks stay behind `skill-bill goal findings`
- `skill-bill goal operator-decision` records `retry_fix` / `accept_and_advance` / `abandon_subtask` onto durable review state without editing `decomposition-manifest.yaml`; resume consumes the decision and reuses `review_base_sha`, baseline untracked inventory, and pass accounting
- `RETRY_FIX` still re-opens the consumed review pass so a hand-applied fix is genuinely re-reviewed; `operatorRetryRounds` stays unbounded — no count converts the pause into auto-advance or terminal failure
- Reusable: `detectReviewRemediationNonProgress` (severity+label+text identities) mirrors audit-repair non-progress detection for the review remediation loop
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-10] SKILL-178 subtask 2 — Remediation-delta finding union
Areas: runtime-application/featuretask, runtime-domain/workflow/taskruntime
- Reserved remediation-pass scope is all findings addressed in that round unioned with `diff(pre-fix -> post-fix)`; pass-one immutable-base and baseline-untracked framing stay suppressed so the two scope statements cannot contradict
- Review-execution-mode text keeps remediation unbounded, now until an unresolved Blocker or Major survives; `context:feature-remediation` stays inline-only
- `implement_fix` briefing carries every preceding-pass finding (Blocker through Nit) with no severity re-filter; handoff projection preserves each finding's severity instead of forcing blocker
- Pattern followed: prompt, composer, and fix-briefing surfaces stay wording-aligned on the widened finding half while the tree-delta half and anti-rediscovery prohibitions stay verbatim
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-10] SKILL-178 subtask 1 — Domain severity gates Blocker + Major
Areas: runtime-domain/workflow/taskruntime/model, runtime-application/featuretask/validation, runtime-domain/config/model, runtime-infra-fs, .skill-bill
- `requiresRemediation` and `blocksAdvance` both gate on Blocker or Major; Minor and Nit stay ledger-only and never reopen `implement_fix` or hard-block advance
- `GoalSubtaskReviewCompactFinding.blocksAdvance` mirrors that rule; a positive unresolved count with an empty itemised list stays blocking; durable artifact keys unchanged
- `REVIEW_CAP_REACHED` / `PAUSED` invariant messages state the Blocker-or-Major rule
- Repo-local `validation_gate.gradle_wrapper` rewrites pack `./gradlew` argv for monorepo layouts without editing pack manifests (reusable)
- BUILD_ONLY terminal `FORCED_FULL` keeps build-only argv and appends pack cache-bypass extras so attestation cannot be a zero-work cache hit
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-10] SKILL-180 — Validate suppression-diff gate
Areas: runtime-application/featuretask/validation, runtime-domain/workflow/taskruntime, runtime-domain/scaffold/policy, runtime-infra-fs (git ops, platformpack), runtime-ports/workflow, orchestration/contracts, platform-packs
- Validate now measures newly introduced suppression markers itself, diffing changed paths against the base ref through the git operations port; the agent's self-report is never an input to the count
- Markers come from platform-pack declarations (`platform.yaml`), not hardcoded Kotlin syntax; a pack declaring none is ungated, and a malformed declaration loud-fails instead of degrading to an empty set
- `validation_result` gained a conditionally required per-suppression justification (path, silenced rule, rationale) — required only when the measured delta is non-zero, so clean runs validate unchanged
- Absent or under-reporting justification blocks validate with the offending paths and unaccounted markers named; a fully accounted delta completes and persists the justification durably
- Gate also applies under `ValidationDepth.BUILD_ONLY`, where suppressions can still be added while fixing compile failures
- Reusable: runtime-measured evidence + conditionally-required agent justification — the pattern for any gate where the agent must account for, but cannot define, the measurement
- Limitation: existing base-ref suppressions are untouched by design; rename/move is neutral, not an audit trail
Feature flag: N/A
Acceptance criteria: 13/13 implemented

## [2026-08-10] SKILL-176 subtask 3 — Orphaned remediation-checkpoint root cause
Areas: runtime-application/featuretask, runtime-ports/workflow, runtime-infra-fs, runtime-kotlin/agent
- Remediation Stage commit and `remediation_base_sha` write are one unit: commit sha is passed into `updateReviewState`; a failed base record soft-resets HEAD to the pre-commit parent so ref and durable row stay paired
- Goal-child resume reconciles committed-but-unrecorded and recorded-but-superseded bases to the branch tip (or latest on-branch review_fix checkpoint) before review prep, with `goal_review_base_recoveries` evidence
- Investigation eliminated in-runtime sibling-orphan producers (failed-checkpoint index restore, crash between commit and record, resume Skip+re-record); stranding needs a post-record history rewrite off the recorded sha
- Reusable: `WorkflowGitOperations.resetSoftToCommit` for ref-targeted compensating rollback; resume reconciliation resolves checkpoint refs before review prep
- Limitation: only remediation bases are scope-critical at this seam; any future runtime-owned amend/rebase must call the paired base update
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-10] SKILL-176 — Remediation-base reachability recovery
Areas: runtime-application/featuretask, runtime-domain/workflow/taskruntime, runtime-ports/workflow, runtime-infra-fs
- Unreachable stored review or remediation bases are recovered to the nearest reachable ancestor of the failed sha instead of permanently blocking the goal child
- Recovery gate is disposition-pending only (no longer mutually exclusive with completed review passes / `reservedPassNumber >= 2`)
- Repoint persists to the failed field (`review_base_sha` or `remediation_base_sha`) with durable `goal_review_base_recoveries` evidence (original, replacement, reason)
- Pattern: recover the selected baseline field that materialization actually used; do not always rewrite `reviewBaseSha`
- Reusable: `GoalSubtaskReviewBaselineRecoveryRequest` + field-aware recovery outcome (`Recovered` / `Failed` / `Ineligible`)
- Limitation: still blocks when no reachable ancestor exists; does not prevent orphaning the sha upstream (subtask 3)
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-08-05] Operator resume reopens blocked goal children
Areas: runtime-application/goalrunner, runtime-ports/goalrunner
- `skill-bill goal <key>` resume of a blocked subtask now reopens the child's durable blocked phase before launch (same child-side reopen as `feature-task retry-blocked`)
- Clears `needs_user_action` / blocked phase records so operator resume continues instead of immediately re-surfacing the prior block
- Records an `operator_block_retry` artifact with reason `Operator resumed the goal after a blocked stop…`
- `non_retryable_policy_conflict` remains a distinct disposition; reopen still applies because the operator explicitly resumed the goal
- Reusable: treat an explicit parent relaunch as the operator decision that unlocks a blocked child
Feature flag: N/A
Acceptance criteria: N/A

## [2026-08-05] Uncapped implementation continuation
Areas: runtime-application/featuretask
- Removed the hard 5-segment implementation-continuation budget; honest incomplete receipts keep continuing until obligations close, the agent reports a terminal outcome, or an operator stops the run
- Malformed-output and semantic fix-loop budgets are unchanged and still independent of continuation
- Resume no longer re-blocks solely because a prior continuation segment count reached the old cap
- Durable blocks that name the removed continuation budget are treated as stale and relaunch implement on resume (same pattern as other removed terminal gates)
- Reusable: keep partial-work continuation on its own axis; do not charge honest progress to structural or semantic repair budgets
Feature flag: N/A
Acceptance criteria: N/A

## [2026-08-04] SKILL-150 — Audit repair convergence
Areas: runtime-application/featuretask, runtime-application/review, runtime-domain/workflow/taskruntime, runtime-ports/persistence, runtime-infra-sqlite/db/workflow, runtime-infra-fs, orchestration/contracts, runtime-cli, skills/bill-code-review*
- Completeness audits now persist append-only generations (repository checkpoint, satisfied criteria, gaps, closure-complete repair batch, bounded evidence) instead of living only in phase output
- Gap and repair-item identities are stable across generations with explicit `new`/`recurring`/`resolved`/`superseded`/still-open transitions; a later snapshot replacing the audit plan can no longer silently mark a gap resolved
- Repair re-entry is fed from the durable authority: every still-open item exactly once, in dependency order, with prior result evidence and non-regression constraints
- Follow-up audit must reverify every carried gap and inspect the repair batch's production blast radius before emitting `satisfied`
- New `feature-task-runtime-audit-generation-schema.yaml` plus store/migration followed the contract recipe and the append-only, name-keyed migration rule
- Reusable: identity-keyed recurrence counters over append-only generations — the pattern for any loop that must prove convergence rather than assert it
- Pattern: audit gaps stay production-only; test adequacy and failures belong to validate, and repair evidence stays read-only repository facts
- Limitation: makes `audit_gap` converge on evidence but does not cap it (SKILL-157 keeps it unbounded with an advisory)
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-04] SKILL-150 — Truthful implementation completion
Areas: runtime-application/featuretask, runtime-application/model, runtime-domain/workflow/taskruntime, runtime-infra-fs, runtime-contracts, runtime-core/di, orchestration/contracts, runtime tests
- An implementation receipt claiming `completed` advances only when the completion gate finds every authoritative-plan task ID closed with no unresolved item or actionable deviation; the gate names the exact missing task or field
- Incomplete work and malformed output are now separate loops: retryable `blocked`/`failed` stay schema-valid continuation outcomes instead of being coerced to `schemaInvalid` to reach the SKILL-153 structural-repair cap
- Semantic continuation retries get a distinct continue-the-implementation prompt carrying the complete bounded prior receipt (completed task IDs, changed paths, deviations, unresolved items, reconciliation evidence, checkpoint, disposition)
- Continuation projection is rebuilt from durable append-only attempt records, so retry and crash resume reconstruct identical context without depending on in-memory prompts or a replaceable phase-record snapshot
- New `feature-task-runtime-implementation-attempt-schema.yaml` contract with version constant, parity test, typed `Invalid...SchemaError`, and classpath bundling followed the standard runtime-contract recipe
- Status and telemetry now distinguish semantic continuation, schema correction, process retry, crash resume, and audit/review re-entry as separate continuation kinds
- Reusable: producer-side completion gate pattern — validate a phase's own claim against its declared plan before any consumer sees it, since consumers cannot repair it
- Pattern: durable bounded attempt history as the single source for continuation prompts; prompt text is derived, never authoritative
- Limitation: the gate trusts the receipt's changed-path evidence for existence, not repository behavior; behavioral proof stays with audit, review, and validate
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-03] SKILL-157 — Semantic loop warning threshold
Areas: runtime-application/featuretask, runtime-domain/workflow/taskruntime, runtime-application tests, runtime-domain tests
- Semantic remediation loops (`review_fix`, `audit_gap`) stay unbounded; crossing iteration 3 emits one user-visible advisory naming the loop, threshold, iteration, and current work
- Backward-edge declarations carry `warnAfterIterations`; non-semantic bounded edges keep their caps and leave the field null
- Warning acknowledgement is persisted per loop and per subtask, so crash or parent resume yields at-most-once emission and the two loops warn independently
- Emission is a pure side channel: destination, edge iteration, verdict, phase status, unresolved items, and completion outcome are byte-equivalent under Noop, recording, and throwing diagnostics sinks
- Pattern: express advisory-only loop policy as a declared edge field consumed by the run loop, not as control flow in the transition decision
- Reusable: acknowledgement-keyed at-most-once diagnostics for any durable-state loop
- Limitation: warns once at crossing only; no periodic reminder and no hidden hard ceiling
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-07-28] SKILL-134 — Structured audit deferral
Areas: runtime-application/featuretask, orchestration/contracts, runtime-application tests
- Replaced recursive free-text deferral detection with explicit `deferred_repairs` and exhaustive per-item `repair_results`
- Enforced exact repair identifiers, dependency order, terminal outcomes, changed-path/symbol evidence, executed verification, and blocked-item consistency
- Preserved actionable diagnostic rule IDs and JSON paths while allowing legitimate future-phase language in summaries and evidence
- Pattern: validate remediation authority from structured fields only; keep human-readable text non-authoritative
- Reusable: shared structured repair projection validation across producer retry gates, standalone runtime, and goal-child execution
- Breaking changes/limitations: planning projection contract now requires `deferred_repairs`; completed remediation must leave it empty
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-07-28] SKILL-134 — Rejected output diagnostics
Areas: runtime-application/featuretask, runtime-cli/featuretask, runtime-contracts, runtime-domain, runtime-ports, runtime-infra-fs, runtime-infra-sqlite, orchestration/contracts
- Persisted one exact rejected phase response per stable workflow/phase/attempt identity before retry or block, with typed metadata, digest, byte size, and deduplication
- Added deterministic size, retention, corruption, cleanup, and owner-only file-permission enforcement behind typed service and persistence boundaries
- Kept raw bodies out of workflow artifacts and ordinary projections; metadata is the default CLI view and exact output requires an explicit raw-output flag
- Extended quarantine metadata with structured diagnostic identity while preserving diagnostic records as non-authoritative for workflow continuation
- Reusable: `RejectedOutputDiagnosticService`, repository port/model, schema validator adapter, and shared privacy assertions
- Pattern: persist privacy-sensitive forensic output separately from workflow authority, expose metadata by default, and require explicit retrieval for raw content
- Breaking changes/limitations: diagnostic and quarantine contract versions changed; storage is local-only and exact responses are not automatically redacted
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-07-27] SKILL-138-cursor-full-agent-support
Areas: runtime-application/featuretask, runtime-infra-fs/launcher/agentrun, runtime-infra-fs/infrastructure/fs, runtime-cli/tests, skills/bill-code-review*, scripts, docs
- Extended featuretask runtime loop to support Cursor as a full agent provider with strategy-based lifecycle callbacks, stream parsing, and crash reconciliation
- Added Cursor-specific command builder supporting `/<worker>` CLI syntax, tool-denied permissions via isolated `.cursor/cli.json`, and fresh-process review isolation
- Introduced typed error hierarchy for Cursor review streams: malformed, empty, forbidden operation, provider failure, and termination errors
- Updated governed skills `bill-code-review` and `bill-code-review-parallel` with Cursor routing, native subagent instructions, and CLI-delegated parallel review
- Documented exact Cursor paths, commands, generated boundaries, and support tier across README, capabilities, getting-started guides, and internal architecture docs
- Created live parity harness testing 7 scenarios: install, MCP startup, runtime feature task, decomposed goal, delegated/parallel review, workflow resume, and uninstall preservation
- New patterns: Strategy-based agent provider injection (progressProbe, lifecycleCallbacks, idlePolicy, usePtyStdio) without conditional branching in ProcessWaitLoop
- Reusable components: `CursorNativeReviewLifecycleCallbacks` for stream event parsing, `CursorAgentRunCommandBuilder` for review detection and command building, typed `CursorReviewStreamErrors` hierarchy
- Breaking changes: None (provider-agnostic contract preserved, native-agent sources remain provider-neutral)
Feature flag: N/A
Acceptance criteria: 7/7 implemented
