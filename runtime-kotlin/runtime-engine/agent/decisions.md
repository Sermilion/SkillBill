## [2026-10-17] Public phase monitor uses the prepared standalone path
Context: The shared monitor definition is also used by goal monitoring. Rebinding it would change goal and phase-pr behavior. The prepared standalone path stayed internal until target rows could be told apart from caller-branch rows.
Decision: Public phase monitor resolves its target read-only, prepares an owned worktree, then runs the internal standalone monitor. Goal monitoring, phase pr, and the legacy monitor strategies stay on their existing definitions and bindings. The recorded public phase and launch arguments remain monitor.
Reason: Status can now keep two pull requests independent, so the public route can leave the caller checkout. Goal monitoring still enforces its workflow branch, and phase pr still publishes in that checkout.
Alternatives considered: Rebinding the shared monitor definition was rejected because goal and phase pr share it.

## [2026-10-17] Target-bearing monitors group by pull request, not caller branch
Context: Two monitors can repair different pull requests that share an issue key or head-branch spelling, including from one dirty checkout and later from an unrelated branch.
Decision: A target-bearing registration skips branch supersession, and legacy supersession ignores those rows. Reads include them through the original repository identity on any caller branch. History, cancellation and board grouping prefer the canonical pull-request key. Target-less rows keep issue, workflow and branch grouping, and branch-scoped reads.
Reason: Branch and issue identity collapse distinct pull requests once execution is in an owned worktree. Admission still refuses a second live monitor of the same pull request; a status row is not that fence.

## [2026-10-17] Retained monitor work blocks replay at ownership admission
Context: Unfinished repair must not be replaced by a fresh monitor, and a status row can outlive the process that wrote it.
Decision: Replay launches the exact recorded pull-request URL and does not check that head out in the caller. A retained dirty, unpublished or uncertain worktree blocks the new run at the ownership fence, and the monitor reports that path and reason. A stale status row alone does not keep the pull request blocked. The monitor-only recover-stale-owner token is passed to admission and is not stored as a launch argument.
Reason: Ownership files are the admission authority and carry the path. Status is best-effort reporting. Blocking from a status row would refuse the pull request after the worktree had already been recovered.

## [2026-10-17] Monitor target detail stays in existing status fields
Context: Operators need the pull-request URL, real head, invocation, worktree and cleanup outcome. IDE consumers already read summary and detail.
Decision: Store the target tuple and cleanup disposition as nullable status columns in migration 59. Project them through the existing summary and detail fields. Leave the IDE status schema unchanged. All-null metadata remains a legacy row; a partial or incoherent tuple fails the read.
Reason: The columns are storage for runtime control, not a new public wire. A schema bump would force every IDE consumer to change for fields they can already show.

## [2026-10-17] Abrupt monitor death is stale recovery, not recorded cleanup
Context: Cleanup may remove only a verified owned clean worktree, and a killed process cannot run its terminal update.
Decision: The cleanup owner starts at acquisition and records released or retained only on the terminal status update, with an idempotent final backstop. An abrupt kill leaves no cleanup record. Recovery uses the ownership file, the expected owner token and positive death evidence of that owner.
Reason: A missing cleanup row must not be read as a removed worktree. The ownership file remains the evidence for stale recovery and for the retained path.

## [2026-10-16] SKILL-427 standalone monitor publishes through one idempotent service and pins the base commit
Context: SKILL-427 subtask 2 adds the internal `standalone-monitor` skeleton, which repairs a pull request's CI failures in an owned worktree and pushes the repair to the pull request's own head endpoint. The public `monitor` definition, its strategies and its bindings stay untouched until subtask 3 owns public normalization.
Decision: (1) Publication is one idempotent `StandaloneMonitorService.publish`, reachable from the `monitor_fix` settle hook (right after the repair completes) and from later `commit_push` visits; the first `commit_push` visit has nothing pending and touches no git state. Because in-memory phase runs settle through final prose and cannot record a blocked status, the repair prose must end with the exact line `Repair outcome: fixed`; any other ending, including none, leaves the repair uncompleted, unpublished and preserved in the worktree. (2) The pre-publication guard compares the re-read pull request with the pinned target: state OPEN, head repository/ref/push URL unchanged, head SHA equal to the last head this invocation published, write evidence still holding, base repository/ref/SHA equal to the pinned base. The same check runs before repair launch, before staging/commit and immediately before push, and conflict repair merges the pinned base commit rather than a newer fetched tip. (3) The per-invocation ledger of pending repair, receipts and acknowledged heads lives in `InMemoryPhaseRunRecords` behind `StandaloneMonitorRunRecords`, never in the run facts, so no ports or callbacks leak into shared facts. (4) `StandaloneMonitorService` is a public class so `runtime-core` can construct it; the run loop reaches it only through the internal `PhaseStandaloneMonitorOperations` contract in `slot/state`, the same way the legacy monitor exposes `PhaseCiObservation`, and the ledger lives in `phaserun/monitor`, so shared code imports no strategy package. (5) `PhaseRunEntry.run` stays on the legacy path; the prepared path is the internal `runStandaloneMonitor` that subtask 3 wires to the public command. It finds its internal definition through `SkeletonDefinition.preparedExecutionFor(publicId)`, which `STANDALONE_MONITOR` declares with `preparedExecutionOf = monitor`, so phaserun names no definition constant.
Reason: One publication path keeps the no-op first visit, the three guard re-reads, the non-force push, and the receipt bookkeeping identical whichever phase reaches them. A repair is investigated and validated against one base; publishing it after the base moved would claim a fix the agent never checked against that base.
Alternatives considered: Tolerating base SHA advances was rejected because the accepted plan requires any external head/base advance to block; it would avoid blocking repairs on a busy base branch. Publishing only from `commit_push` was rejected because the existing traversal returns to `monitor` after `monitor_fix`, so a repair would be observed before it was published. Reusing the legacy `InMemoryCommitPush` was rejected because it stages and commits unconditionally and has no remote guard. Putting the ledger in `InMemoryPhaseRunFacts` was rejected by the no-ports-in-facts rule.
Revisit when: Subtask 3 routes the public `monitor` command through the prepared path, or blocking on base advances proves too disruptive on busy base branches.

## [2026-10-16] Standalone monitor worktrees live under the common git dir and are removed only when provably safe
Context: Concurrent standalone monitors of different PRs must not collide, and a monitor must never destroy work it did not publish.
Decision: Admission is keyed by the canonical common git dir and target PR, so linked worktrees contend on one lock. The worktree, branch and refs are invocation-specific under skill-bill/standalone-monitor in the common dir, and cleanup removes only an owned, clean, fully published worktree, never with force.
Reason: A common-dir key makes linked worktrees see the same owner, and invocation tokens stop two runs sharing refs. Retaining with an exact reason and path is safer than guessing.
Alternatives considered: Worktrees beside the caller checkout, rejected because they pollute it and are not keyed to the shared repository.

## [2026-10-16] Stale monitor recovery requires positive death evidence of the exact owner
Context: A crashed monitor leaves ownership state that blocks the same PR forever, but stealing it from a live or unknown owner would run two monitors on one worktree.
Decision: Recovery needs the expected owner token, an acquirable lock and positive exact-process death evidence. Live or unknown owners and retained unfinished work are refused, and the fence is checked in memory and on disk.
Reason: Unknown must read as alive, since a false takeover costs more than a manual reset.
Revisit when: A cross-process contention test or a heartbeat lease gives a safer liveness signal.

## [2026-10-14] SKILL-425: the engine drops the goal planning sweep
Context: The goal runner ran a planning sweep over `GoalPlanningPhaseRunState` and wrote `goal_*` checkpoints.
Decision: The goal runner asks `GoalPlanningSource` for planning (plan workflow, then read-only legacy tables, then `StandalonePlanRun`). Status reads planning progress from the latest plan workflow bundle and falls back to the legacy tables, and `GoalChildPlanningHydrator` reads either source. The full decision is in runtime-kotlin/agent/decisions.md.
Reason: One durable planning record serves resume, status, and hydration.
Supersedes: Run standalone plan through the durable run loop and admit by definition (2026-10-09), for the part that left a sweep to run at plan time.

## [2026-10-14] Settle intake in the run entry before invariants are read
Context: The PLAN skeleton now starts with an intake step, but no spec exists until intake runs, and run invariants and the review mode are derived from the spec.
Decision: The run entry pre-settles intake through a settleIntake hook before invariants are read. The in-loop RuntimeIntakeStrategy call is idempotent, only records the step, and resolves the spec by issue key. Opening a plan workflow uses review mode AUTO when INTAKE is in the definition's slots.
Reason: Invariants must read a spec that intake has already prepared. Running intake only inside the loop would read them too early. AUTO is the only mode derivable before a spec exists.
Alternatives considered: Seed the spec before opening the workflow (the old StandalonePlanRun behaviour), which left intake outside the durable step record.
Revisit when: invariants stop depending on the spec, or the AUTO fallback gets direct test coverage.

## [2026-10-14] Keep a never-run intake step out of non-plan skeleton surfaces
Context: Adding intake to the shared workflow definition would add a pending first step to every skeleton and shift status output, blocked-step fallback and baselines.
Decision: phaseStatuses filters intake unless a record exists, the blocked-step fallback in WorkflowGoalRunnerBlockWrites skips it, and the forwardPhaseOrder header omits it. Only the PLAN skeleton runs it.
Reason: Only PLAN binds the intake slot. Hiding it elsewhere keeps existing behaviour stable while the schema enums and workflow definition stay single-sourced.
Revisit when: subtasks 2 to 4 fold full runs and the standalone skeleton into the one planning skeleton and every run executes intake.

## [2026-10-10] Rank a live goal above a parked parent before runSequence
Context: After SKILL-416's live-cohort partition, a parked FEATURE_TASK_RUNTIME parent stayed in the live cohort as PAUSED and beat a live FEATURE_GOAL on monitor because runSequence still ranked first.
Decision: Before comparing runSequence, a goal-authoritative candidate in the live cohort outranks a non-goal PAUSED peer. Sequence, freshness, and tier stay as they were for every other pair.
Reason: Putting selectionTier before sequence would also hide the parked parent but would change among-live ranking, which SKILL-416 rejected. isExcludedGoalChild cannot hide the parent because it is FEATURE_TASK_RUNTIME, not GOAL_CHILD. Identity rows stay write-once, so selection cannot bump status_revision.
Alternatives considered: Reorder tier before sequence; hide the parked parent in collectCandidates; per-issue sequences. All rejected.
Revisit when: sequences become per-issue, or a named live-vs-live incident needs a broader comparator change.

## [2026-10-10] Project a selected parked parent through the live goal snapshot
Context: The 0AC-46 work list may contain only the parked parent and completed child, with no FEATURE_GOAL item. project() would then call projectRuntime and show plan or paused even after selection is fixed.
Decision: When a FEATURE_TASK_RUNTIME candidate's issue has a goal runner on open CI monitor or live finalization, inject GoalRunnerManifestStore and assemble the snapshot as FEATURE_GOAL through assembleGoalStatusSnapshot. Otherwise keep projectRuntime.
Reason: The spec requires the live goal projection whenever the runner is on monitor, PR, or CI, even if the parked parent is the only non-terminal row. A work-list FEATURE_GOAL candidate is not guaranteed. Operator pause and operator-decision pause still flow through the existing goal lifecycle mappings.
Revisit when: work-list always includes FEATURE_GOAL during monitor, or identity registration starts updating the parent row.

## [2026-10-10] Ignore completed-child phase id once the goal is on monitor
Context: goalCurrentStep preferred childPhaseId over projection.currentStep over monitor. currentChildWorkflowId can still name the finished child, so commit_push replaced monitor on ide-status.
Decision: Drop childPhaseId from goalCurrentStep when openCiMonitor or liveFinalizationStep is set. Bundle those two flags as GoalCurrentStepSignals.
Reason: Subtask 1 already rewrites extras for goal-status. Ide-status still read the child's FeatureTaskRuntimeStatusRequest phase id independently. Operator-pause mappings stay on the existing goalLifecycleForOperatorBlock path.

## [2026-10-10] Keep unfinished CI monitor active without an execution lease
Context: Goal IDE status treated an expired parent execution lease as idle when subtasks were finished and the goal had not completed, so the remaining CI wait disappeared from status.
Decision: When a goal has completed subtasks, no pending or blocked work, and no recorded completion, keep lifecycle ACTIVE on the monitor step with activity "CI not fixed" even without a live execution lease. Refresh updatedAt from the observation clock so the wait stays FRESH until CI is fixed or the goal completes.
Reason: Finalization still owes CI after the last subtask. Painting that wait as idle hid the monitor phase. The lease is for execution occupancy; the monitor wait has none.

## [2026-10-10] Live IDE status is heartbeat-fresh, not merely retained
Context: A dead standalone monitor stayed `active` inside 24h live retention. Selection ranked only ACTIVE/PAUSED/BLOCKED rows when any existed, so that stale row hid a later terminal monitor and the IDE showed Stale after CI passed.
Decision: The live cohort is heartbeat-fresh ACTIVE/PAUSED/BLOCKED only. When none are fresh, retained settled work competes and a later terminal monitor can win.
Reason: Live occupancy should mean a process that is still heartbeating. Retention still keeps a quiet genuine run selectable when nothing fresher exists.
Supersedes: Select live retained IDE status before sequence ranking (2026-10-10)
Revisit when: a named live-vs-live incident requires reordering freshness and tier.

## [2026-10-10] Select live retained IDE status before sequence ranking
Context: Repo-global run_sequence let a later completed standalone on the same branch outrank a live goal (2026-10-09 SKILL-414 vs SKILL-415 plan).
Decision: After retainedAt, if any ACTIVE, PAUSED, or BLOCKED row remains, rank only that live cohort. Rank FAILED, RECENTLY_TERMINAL, and IDLE only when live is empty. Keep the existing comparator inside the chosen cohort.
Reason: Putting tier before sequence in one comparator would also hide the incident but would change among-live ranking, including stale ACTIVE losing to fresh BLOCKED. Per-issue sequences would need a registry change this task forbids. Policy-only change covers both IdeStatusService select calls without a third ranking path.
Alternatives considered: Reorder comparator keys; add a service-level live filter; change ide_status_execution_registry high-water. All rejected.
Revisit when: a named live-vs-live incident requires reordering freshness and tier, or sequences become per-issue.

## [2026-10-10] Scan IDE status sequence conflicts only on the selected cohort
Context: Equal-sequence different-executionId used to throw across all retained rows.
Decision: Run that scan on the selected live or remaining cohort only.
Reason: A live row and a terminal row that share statusStoreId and runSequence with different executionIds must not fail status after the live partition. The throw stays required inside the cohort ranking uses.

## [2026-10-09] Share authoritative execution order across status sources
Context: IDE status must order eligible workflow and standalone-phase executions despite timestamp ties, skew, retention, and durable-plan correlation.
Decision: Give each root execution a persisted per-repository sequence and keep standalone status in a purpose-built repository with explicit scope and identity.
Reason: A shared authoritative order lets selection prefer the newest eligible execution without using WorkItem or telemetry as a status proxy.
Alternatives considered: Timestamp-only ordering and projecting standalone phases through workflow/telemetry rows were rejected because they cannot preserve source identity or deterministic ordering.

## [2026-10-09] Settle standalone terminal status from the authoritative result
Context: Intermediate phase events, process exit, late events, and expired leases can otherwise publish premature success or regress a terminal result.
Decision: Fence persisted updates by lease generation and revision, make terminal transitions idempotent and non-regressing, and publish terminal state only from the complete authoritative phase result or reconciliation.
Reason: The status projection must describe the settled operation, while lost execution remains interrupted/stale and cannot be turned into success by a late event.

## [2026-10-09] Keep current activity bounded and non-authoritative
Context: Status consumers need useful activity text, but raw agent output can expose secrets, commands, paths, diagnostics, or unbounded content.
Decision: Emit optional activity only from allowlisted event descriptors after sanitization and control-character removal, capped at 160 Unicode code points; never use it as progress or heartbeat evidence.
Reason: This preserves truthful availability and bounded UI details while keeping liveness and progress semantics separate from display text.
Alternatives considered: Publishing raw agent text or fabricating activity when unavailable was rejected because it is unsafe and misleading.

## [2026-10-09] Run standalone plan through the durable run loop and admit by definition
Context: SKILL-410 `phase plan` ran in memory, so a plan left no workflow a later full run could resume or link to.
Decision: Make plan a durable skeleton with an `admittedForRun` set per run kind, route it through `StandalonePlanRun`, and link the completed plan workflow to the goal parent on import.
Reason: A durable workflow gives resume, crash reconciliation and purge for free; a per-kind admitted set keeps goal_child+plan rejected without a second admission path. Keeping `phase plan` out of the dispatcher preserves the rule that the dispatcher never invokes it.
Alternatives considered: keeping the in-memory entry and copying results into the goal; rejected because a crash or intake change would lose the plan with no resume or hash check.
Revisit when: another standalone phase needs a durable workflow, or non-CLI callers must chain plan into a goal.

## [2026-10-09] Purge all matching goals and defer destructive steps after a failed directory delete
Context: SKILL-409 goal purge left rows and directories behind (WE-5006) because it removed one decodable parent and restored a DB snapshot on failure.
Decision: Purge every repo-scoped decomposed parent for the issue key, including undecodable ones. If any directory delete fails, skip the DB, checkpoint and spec steps and report the failed paths as leftovers with exit 1. The old snapshot-restore is removed.
Reason: Reporting a leftover for extra parents kept the stale state the command exists to clear. Deleting rows while directories remain would orphan the on-disk evidence and make a rerun unable to find it; deferring keeps a rerun idempotent.
Revisit when: a purge needs to be atomic across the filesystem and database.

## [2026-10-08] Build step calls from the selected owner, not the caller
Context: SKILL-403 wrappers (`*Opus55Strategy`) delegate `runStep` to a canonical strategy. `stepCall` used its receiver, so the call carried the canonical strategy id and prompt while the accepted binding's owner was the wrapper. Attempt authorization rejected every wrapper-owned step, and the wrapper's Opus directive never reached the prompt.
Decision: The step binding exposes its accepted owner, and step calls take prompt, policy and strategy id from that owner. `acceptsAttemptStrategy` keeps exact-id matching.
Reason: Accepting the canonical id in each wrapper would have stopped the crash but still sent the canonical prompt, and every new delegating wrapper would need the same override. Reading the owner from the binding makes the selected strategy the only source of call identity, so a wrapper can delegate execution without losing its id or prompt.

## [2026-10-08] Standalone review admits prose reports
Context: SKILL-402 rejected any standalone review report with a line outside the `[F-NNN]` register grammar, a misplaced or extra NO_FINDINGS, or a verdict that was not the last line, and blocked on parser diagnostics. A complete prose review with `verdict: changes_requested` exited 1, so the operator lost a valid report-only review to formatting.
Decision: Reject only blank or truncated output, a missing, conflicting or unknown verdict, and incomplete delegated coverage. When an inline report carries prose, present the report as written (verdict lines removed) instead of the merged register. A Blocker or Major finding, a finding candidate the parser rejected, or prose that admits an incomplete review ("review incomplete", "could not inspect/read/open") or labels a finding high, major, blocker or critical presents the verdict as changes_requested instead of rejecting the report. The prose match is a heuristic biased toward changes_requested; it ignores "could not find" and "no high-severity". Parser diagnostics annotate a block but never cause one.
Reason: The standalone review is report-only; the operator reads the text and no repair consumes the register. Showing the report as written keeps warnings and unparsed findings visible, and the verdict upgrade still prevents a false approval, which was the point of SKILL-402's strictness. Full-run code_review admission is unchanged.

## [2026-10-05] Return run-invariants rejection across the port
Context: SKILL-401 subtask 3 removes defect-exception handling from phase intake and goal planning, which need different responses to an unreadable or unauthorized spec.
Decision: The run-invariants port returns Read or Rejected with the existing reason. The filesystem adapter owns refusal; intake keeps its fallback and goal planning retains its stop policy.
Reason: Refused input is an expected outcome. Returning the reason lets consumers preserve their behavior without treating unrelated defects as unreadable specs. Port-owned declarations keep filesystem behavior in the adapter.

## [2026-10-05] Share rejection validation with edge-only throwing APIs
Context: SKILL-401 requires unchanged user messages, persisted bytes and CLI/MCP exception classification while removing internal IllegalArgumentException and IllegalStateException handling.
Decision: Decode expected invalid input through nullable lookups, model violation helpers and reason-carrying parse results. Retain throwing APIs for edge-only callers over the same validation and keep traversal derivation generic.
Reason: Catching defect exceptions inside the run loop can conceal code bugs. Moving the catch into a parser wrapper preserves that problem; direct rejection also supplies the existing degradation text without manufacturing an exception. Changing edge classification can add MCP telemetry rows and change persisted bytes.

## [2026-10-04] Default to the next local issue key for new requirements

Context: The dispatcher required a connected tracker even when the operator
supplied requirements and requested the next local key.

Decision: The dispatcher derives the next project key from `.feature-specs/`
bundle identities, including ignored, untracked, completed, and archived work.
It prepends that key to the requirements before launching the runtime. Explicit
keys still override allocation. Supplied requirements and locally allocated
keys require no tracker lookup. The CLI continues to require resolved intake.

This supersedes the tracker-only dispatcher requirement recorded on 2026-10-03.
The runtime still owns spec and workflow creation. Local allocation creates no
tracker issue and reserves no identity through a placeholder spec or database
write. Prefix ambiguity asks for a local prefix.

## [2026-10-03] Require a tracker issue key for new goal intake

Context: Goal intake minted `LOCAL-<hash>` and defaulted the spec folder to `KEY-intake` when the operator supplied raw requirements or a key without a description.

Decision: Refuse new work that has no tracker issue key or link, and refuse a new spec folder that has no short description from the URL slug, remaining title, or existing bundle name. Resume of an existing spec or persisted key still works without inventing either value.

Reason: Spec identity is `KEY-short-description`. A hashed local key and an `intake` fallback hide the missing tracker reference instead of asking for it.

Alternatives considered: Keeping LOCAL minting for CLI-only raw text still creates untracked folders and a second identity scheme. Fetching the tracker title during prepare would hide a missing description on key-only CLI launches.

## [2026-10-03] Require requirements text with the tracker key for new goal intake

Context: A bare tracker URL with a slug started a new goal whose spec held only the link, and a bare key printed launch output before preparation refused it.

Decision: New work needs both a tracker key or link and requirements text after it; a URL slug names the folder but is not requirements. `GoalRunner.admitIntake` checks this before the CLI prints launch output, unless a persisted goal or existing spec matches the key. A gap returns `GoalIntakeAdmission.NeedsInput`, not an exception, because missing input is an expected outcome; the CLI phrases it as a request ("To start new work on APP-123, add the requirements…"), writes no state, and exits 1. The dispatcher asks the operator for the missing key or requirements.

Reason: The tracker reference names the work and the requirements define it. Refusing before launch output keeps a missing part a question for the operator rather than something that looks like a started run.

Alternatives considered: Accepting the slug as requirements leaves planning with nothing to plan from. Checking only inside `prepare` keeps the misleading launch banner.

## [2026-10-03] Canonicalize governed spec paths before planning-import admission

Context: A new goal child's implement admission refused `unsafe_import` with `source_version=unknown` even though planning had just written 0.7 payloads. Launch stores a repository-relative identity path; decomposition can still carry an absolute `spec_path` for the same file.

Decision: Treat those strings as the same governed spec by canonicalizing against `repository_identity` in `FeatureTaskExecutionIdentityPolicy`, and use that comparison in planning topology and child-import admission.

Reason: String equality treated a fresh same-runtime import as a foreign source. The diagnostic then had no phase-output version facts, so it logged `unknown`. Converting the bytes was never the failure.

Alternatives considered: Forcing planners to write only relative spec paths still leaves already-persisted absolute manifests, and child identity is required to stay repository-relative.

## [2026-10-03] Preserve slotbaseline resource paths during test relocation
Context: SKILL-390 subtask 3 moves slotbaseline capture tests into featuretask.runner while requiring resource and persisted-byte preservation.
Decision: Keep the featuretask/slotbaseline resource tree, module-root resolution and absolute audit-resource lookups unchanged while relocating capture tests and repairing their setup.
Reason: Existing resource lookups are independent of the Kotlin package. The relocation does not require moving goldens or regenerating captures, and existing byte-comparison coverage must retain its original evidence.

## [2026-10-03] Keep composition access in test source sets
Context: SKILL-390's private collaborator ownership leaves existing engine, CLI and core tests with stale construction and strategy-access calls.
Decision: Repair engine test factories, use the CLI's status-projection strategy seam, and give core registry tests an internal generated test component with kspTest wiring.
Reason: Restoring production collaborator accessors would undo the ownership repair. Existing behavioral and registry assertions cover these changes, so the plan calls for setup repairs without constructor-shape or setup-mirroring tests.

## [2026-10-03] Keep gate rewiring behind accepted-step bindings
Context: SKILL-390 subtask 2 removes the phase-gate locator across durable, in-memory and goal-planning execution while preserving strategy authority.
Decision: Give runtime consumers the specific typed collaborator they use, keep gate cycles and finalization with their existing owners, and retain accepted-step bindings for strategies.
Reason: Unpacking the locator must not give ordinary strategies Git writers or unrelated mutation authority. Renaming a bag or forwarding its host would preserve indirect access; replacing the run-loop framework would exceed this change's scope.
Alternatives considered: Replacement dependency factories, broad context conversions and per-run DI subcomponents would retain indirect ownership or add another execution framework.

## [2026-10-03] Extend the existing inject guard with an empty engine baseline
Context: The injected-constructor property guard covered application and CLI but missed exposed engine collaborators.
Decision: Add the engine scan beside the existing methods, use the inventory-owned main-source root and an empty baseline, and retain the scanner's rejection fixture.
Reason: The same scanner can reject engine property exposure without another architecture-test class or an exemption. Forwarding getters and receiver locators still require source review because this guard does not detect them.

## [2026-10-02] Private behavior owners with call-scoped pending state
Context: SKILL-390 found goalrunner dependency bags and receiver helpers that read exposed collaborators. Unpacking the per-run assembler alone would exceed the constructor limit.
Decision: Inject private behavior owners in existing packages, move finalization and projection operations into their classes, and pass one run-owned pending state through execution calls.
Reason: Each owner takes the dependencies its operations read. Moving dependencies into another bag would preserve the locator problem; storing pending state in injected owners would lose its per-run lifetime.
Alternatives considered: Unpacking all dependencies into the planning sweep or adding a replacement collaborator factory would retain oversized or indirect ownership.

## [2026-10-02] Canonicalize issue keys without adding validation
Context: Engine issue-key sites repeated trimming and uppercasing, while domain normalization also validates the original input.
Decision: Use `FeatureTaskExecutionIdentityPolicy.canonicalIssueKey` for non-validating derivation and have validated normalization delegate after its existing checks.
Reason: Replacing derivation with validated normalization would reject inputs that existing callers accepted and could change nullable behavior. The contracts-level normalizer has a different rule and remains separate.

## [2026-10-02] Inject timing and refresh tick progress after rollback
Context: Planning duration and tick-progress memoization read ambient monotonic time. SKILL-390 requires both to use the existing injected Clock.
Decision: Measure planning launch between two reads of that Clock and refresh the 200 ms tick cache when its time moves backwards.
Reason: A start read after launch or a different clock records zero for the planned 137 ms rejection-evidence regression. Rollback must not keep a cached result indefinitely; cached absence still follows the same memo interval.

## [2026-10-02] Featuretask owns branch policy and child-repair vocabulary
Context: Featuretask imported goalrunner for protected-branch policy and the persisted child-repair evidence key, creating the remaining engine package cycle.
Decision: Put both declarations in existing featuretask owner files, remove unused reverse imports, and empty the engine cycle baseline.
Reason: Goalrunner already depends on featuretask execution. Keeping shared execution policy in goalrunner preserves the reverse edge; moving ownership breaks it without changing branch matching or persisted artifact bytes.

## [2026-10-02] Remove the inert producer-side visibility census
Context: The engine visibility rule scanned only explicit public declarations, while its fixtures exercised default-public scanning that production never enabled.
Decision: Delete that rule, its two fixtures and its helper. Retain consumer-side inbound API pins and the carrier class's live run-loop guards.
Reason: The existing rule could not catch default-public declarations. Enabling that scan conflicts with generated runtime-core injection code that must name engine classes and constructor types; the consumer-side guard already enforces inbound imports.
Alternatives considered: Flipping the producer scan to include default-public declarations was rejected in the SKILL-390 investigation. This replaces SKILL-378 AC-11's producer-side conclusion.

## [2026-09-29] Admission precedes parent mutation and preserves recovery evidence, SKILL-384 subtask 2

Parent lease acquisition rechecks the current child link and its execution descriptor inside the lease transaction. A stale owner, changed child link, or incompatible descriptor leaves parent controls and child records unchanged. Descriptor admission compares the complete canonical plan. Ports carry validated canonical bytes; maps stay in serialization code.

Resume matches installed pack declarations against the recorded policy digest, even when the checkout is clean or its changed paths now route elsewhere. It rejects changed commands, wrappers, and execution settings without replacing the recorded plan.

Execution admission belongs to lifecycle code. The accepted plan and its effective inputs live under the feature-task models, and strategy dispatch consumes that plan. Recognizing a historical step does not authorize execution.

Runtime-generated build and validation receipts enter the producer-evidence store before completion. Regeneration requires that retained payload and a proven safe boundary. It preserves attempts, attribution, checkpoints, and finalization history. Process failures stop the current invocation; earlier invocations do not exhaust a later invocation's process-failure allowance.

Verification: 93 focused engine tests, 853 domain tests, 97 contract tests, 375 SQLite tests, and 272 architecture tests passed. The aggregate Kotlin compilation also passed. The repository-wide check still has test and lint failures outside this focused verification; it has not passed.

## [2026-09-25] Durable sequence allocation, loop-count caches, and F-006 silent reads (SKILL-378 subtask 3)

Context: Engine classes previously held their own sequence counters and several durable reads swallowed decode failures, so corrupt rows degraded silently.

Decision: (1) Progress, attempt-ledger, and observability sequences are allocated as `max + 1` inside the transaction that writes the entry. Progress and the attempt ledger take the issue-wide max in `WorkflowGoalRunnerProgressRecording.appendSequencedHistoryArtifact`; observability takes the per-workflow history max inside `GoalObservabilityArtifacts.patchForEvent`, which covers both the goal-runner writer and child progress-derived entries. `DEFAULT_GOAL_OBSERVABILITY_SEQUENCE_START` (10_000) and `DEFAULT_GOAL_EVENT_SEQUENCE_START` (20_000) are gone; existing histories continue from their max, and child observability entries now carry allocated numbers. That is the only stored-number change. (2) `GoalRunnerLedgerRecorder.cumulativeBackwardEdgeCounts` is a per-(subtask, loop) loop-count cache seeded from `ledgerSequenceWatermarks`, not a sequence allocator, and stays in memory. (3) Malformed durable review state (`UnaddressedFindingsLedgerService.repairLedgersByWorkflow`) and malformed shared-preplan payloads (`preplanProseValue`, `preplanProsePrompt`) now fail typed instead of vanishing; child execution liveness and the planning rejection-diagnostic write stay best-effort but record one bounded warning naming the seam, the expected value, and the used value. Cancellation rethrows everywhere; `InterruptedException` restores the interrupt flag first. (4) The fifth silent read, the `runCatching` over `goalObservabilityLatestEventFromArtifacts` in `WorkflowGoalRunnerProgressRecording.progress`, is left for the goal-runner follow-up. (5) Goal-runner reads resolve through the read-only `FeatureTaskRuntimePhaseQuery`; only writers keep the `FeatureTaskRuntimePhaseRecorder` facade.

Reason: One allocator per durable stream removes the duplicate-number class of bug that per-instance counters allow across concurrent recorders, and a typed failure beats a silently missing workflow in goal repair and CLI goal output.

Evidence: `GoalRunnerDurableSequenceAllocationTest`, `GoalObservabilityModelsTest`, `UnaddressedFindingsLedgerServiceTest`, `GoalPlanningRefreshLivenessTest`, `GoalPlanningPreplanProseReadTest`, `GoalPlanningRejectionRecorderTest`.

Revisit when: Cross-process writers stop relying on SQLite writer serialization, or the remaining best-effort emitters need a durable dead-letter instead of a dropped event.

## [2026-10-16] Goal resume clears known wedges before it runs

Context: A goal runner killed mid goal-finish left a stale execution lease, a `runner_interrupted` pause, and child wedges such as an unreachable remediation base. Each resume then stopped until the operator ran `skill-bill goal repair <KEY> --apply` by hand.

Decision: `GoalRunnerRepairCoordinator.repairBeforeResume` runs the same diagnosis and apply as `goal repair --apply` and records one `seam=goal-resume.repair` diagnostic per applied repair. Two callers use it: `GoalRunnerRunPreparation.prepareStart`, before `skill-bill goal <KEY>` takes the execution lease on an existing goal, and `GoalRunnerStatusControlVerbs.resume`, which serves `goal resume` and the desktop Resume. The goal command prints each cleared wedge through a `GoalRunnerRunEvent.ResumeRepaired` event; `goal resume` lists it in its text and payload. An `OPERATOR_REQUIRED` result stops the goal run with a blocked report and leaves the pause in place on `goal resume` (new status `operator_required`, exit 2). A live or unexpired lease makes repair refuse as before, so a running runner keeps its state. New goals and goals already complete skip the repair. `GoalRunnerRunEvent` joins the pinned engine inbound API because the CLI presenter now reads the event type that the pinned `GoalRunnerEventSink` already carries.

Reason: The repairs only clear state the runtime can prove stale, so applying them on resume removes a manual step without widening what repair may touch. Completed commit SHAs, review history, audit repair state, and planning checkpoints stay untouched.

Evidence: `GoalRunnerResumeRepairTest`, `CliGoalRepairRuntimeTest` (`goal resume clears stale runner state before it runs and prints each cleared wedge`).

Revisit when: A wedge class becomes repairable for a completed child in a way that should not block a resume, or repair learns to judge an unexpired lease whose process is gone.

## [2026-10-16] A run target's identity ignores the heartbeat timestamp

Context: The desktop Cancel and Resume build a `WorkRunTarget` from the last rendered work item, and `matchingRun` required its `updatedAt` to equal the current snapshot. A live goal's runner heartbeat moves `updatedAt` about every 10 seconds while `statusRevision` and the execution id stay the same, so a Cancel sent from a view older than the last heartbeat was refused as `CHANGED_TARGET` and the runner kept going.

Decision: `matchingRun` matches a run by id, repository identity, repository root, execution id and status revision, without `updatedAt`. A new execution or a status transition still changes the target; a heartbeat does not.

Reason: `updatedAt` is liveness, not identity. Status revision and execution id already fence real changes, including a restart after cancel.

Evidence: `WorkRunControlsTest` (`cancel accepts a target the runner heartbeat made older but refuses one for another execution`; the run-again test's options call with an older target). Replayed against a live goal on 2026-10-16: a target read just before Cancel was accepted, while `updatedAt` advanced every 10 seconds.

Revisit when: Goal Pause and Stop (`WorkControlService.validate`) get the same treatment. They also compare `updatedAt` and the work store revision, which the heartbeat bumps too.
