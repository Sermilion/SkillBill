# goalrunner boundary decisions

## [2026-10-27] Write goal boundary memory after final repairs
Context: SKILL-446 child drafts precede goal QA and validation repairs, so child-time file writes cannot describe the final published change.
Decision: Run the existing boundary writer once in goal finalization after existing final checks and before commit and PR. Supply manifest-ordered drafts, explicit skips, stable identities and final repair context; honor recorded legacy dispositions.
Reason: The ordinary writer already owns boundary selection, overlap removal and supersession policy. Reusing it includes final repairs without adding another quality cycle or republishing legacy child history.
Alternatives considered: Reconstructing narrative from transcripts or Git ancestry and introducing a per-entry edit protocol are outside the spec's producer-owned draft design.

## [2026-10-27] Persist final repair prose before acknowledging completion
Context: A goal QA or validation run can accept a repair and then warn, block or stop before its in-memory result returns.
Decision: Persist identified accepted repair prose under the parent at settlement, reconcile returned outputs without duplicate occurrences, and retain warnings separately. Required save failures stop publication even when ordinary QA failures warn and continue.
Reason: Capturing only returned phase results loses repairs across interruption. The bounded attempt ledger and execution archives cannot replace producer-owned durable prose for the final writer.

## [2026-10-27] Reuse writer completion against its post-write contents
Context: The history writer changes repository files, and finalization can stop after those edits or after accepted writer completion.
Decision: Save accepted output with ordered-input and pre-writer content fingerprints plus post-writer content identity. Reuse only when inputs and current post-writer contents match; otherwise invoke the writer against current files and stable references.
Reason: Comparing only pre-writer contents invalidates the writer's own edits on every retry. Treating an interrupted edit or synthetic head receipt as completion could publish without accepted settlement. Current-file inspection follows existing duplicate-avoidance policy without promising semantic deduplication.

## [2026-10-27] Save child history drafts before completion
Context: Goal children must leave history for final publication, but scratch cleanup and the bounded attempt ledger can remove child state before finalization.
Decision: Store a validated draft or skip in a separate parent-owned durable artifact before authoritative phase settlement, and require it before child completion or scratch deletion. Keep recorded older revisions on their original history strategies.
Reason: A best-effort ledger write cannot guarantee the draft survives completion or trimming. Idempotent replay recovers a save interrupted before settlement, while conflicting payloads must stop rather than replace accepted evidence. Older children may already have written or skipped history, so fabricating drafts would change their recorded disposition.

## [2026-10-26] Preserve child history evidence before deleting workflow rows
Context: Hard reset and scoped recovery delete child workflow rows. A commit and a parent draft alone cannot prove the deleted child's definition, continuation, or legacy history disposition.
Decision: The parent workflow owns versioned `goal_child_history_evidence` records outside the bounded attempt ledger. Each record retains the actual execution plan, continuation, history phase record and step disposition, and original acceptance association. Reset saves evidence and clears progress in one SQLite transaction. Restoration validates the preserved definition and disposition, then writes the acceptance, restored reference, and completed projection in one transaction. The restored reference remains separate from the live workflow ID. New admission and scoped deletion retire restoration eligibility and clear the selected acceptance while retaining historical evidence. Already-deleted children without evidence remain refused.
Reason: The runtime must preserve actual legacy writes and skips without manufacturing drafts, and must not promote a new child using an older child's acceptance. Additive artifact families need no SQL table migration; absent evidence on older parent records grants no restoration authority.
Evidence: `GoalChildHistoryRecoveryTest` covers reopen, rollback, legacy retention, repeated restoration, retirement, and missing or malformed evidence.
## [2026-10-25] Adopt an unrecorded assessment-fix commit by parent and paths, not tree identity
Context: A crash between a round's fix commit and its record write would otherwise make resume commit the same fix twice.
Decision: Adopt an existing commit only when its single parent equals the round head sha and its changed paths equal the recorded changed files.
Reason: This recovers the crash window without recording tree identity, so the assessment-record schema needs no change.
Alternatives considered: Recording the tree hash was rejected because it would force a record contract bump.

## [2026-10-25] Encode a refuted carried finding as fixed while verification stays confirmed
Context: A later round re-verifies carried open findings, and the verifier can refute one that the fix resolved.
Decision: Move the finding to state fixed and leave its verification as confirmed.
Reason: The record shape ties state refuted to refuted verification, so fixed is the only valid encoding of a finding that was real and is now gone.
Revisit when: The record contract gains a distinct resolved-by-reverification state.

## [2026-10-25] Run the assessment fix through its own skeleton definition
Context: A goal-scoped phase launch always starts at the slot's entry step, and the read-only assessment skeleton cannot mutate files.
Decision: Add a GOAL_ASSESSMENT_FIX skeleton definition with a mutating, file-mutating strategy and run it through the same phase runner.
Reason: Reusing the read-only definition would put the fix on the wrong entry step and tool policy; the runtime, not the agent, sets changed files and status.
Alternatives considered: A phase-specific launch path was rejected in favor of one compositional phase runner.

## [2026-10-24] Purge resets a project-store bundle by rewriting only its manifest
Context: Spec bundles now live in the project store, and purge reset only handled in-repo bundles, so a store manifest kept launched subtask state after purge.
Decision: Add a store branch that atomically rewrites just the manifest to the unlaunched YAML. It never deletes, reads HEAD, or enumerates subtask spec paths.
Reason: Store spec files are the only copy of the plan with no git history to restore from, so reset must leave them byte-identical. Survivors report a store manifest whose text still differs, so a dropped write is not reported as success.
Alternatives considered: Reusing the in-repo tracked reset was rejected because it restores spec files from HEAD, which does not apply to the store.

## [2026-10-19] Reconcile stale segments only on an inserted start with ownership evidence
Context: A crashed invocation can retain an open telemetry segment, but another parent may still own a live execution of the same issue.
Decision: Only a genuinely inserted start authorizes transactional supersession. Same-parent admission permits older-segment cleanup; an expired other-parent lease requires supervisor-confirmed death. Missing-lease and parentless rows require no protected conflict.
Reason: Lease expiry alone cannot prove process death. Reusing the execution-ownership supervisor avoids a second liveness authority in SQLite, while the existing lifecycle transaction keeps segment terminals, logical progress and outbox effects atomic and replay-safe.
Alternatives considered: Expiry-only cleanup could abandon a live invocation. Uncertain or contradictory ownership instead preserves rows and emits diagnostics; aggregate abandonment additionally requires the transitioned latest segment and no protected open segment.

## [2026-10-19] Close failed invocation telemetry without replacing the execution failure
Context: Planning, launch or telemetry can throw after the invocation start persists, leaving it reported as running.
Decision: Attempt direct blocked segment closure with goal_run_failed while rethrowing the original failure. Preserve interruption and cancellation, attach closure errors as suppressed evidence, and skip closure before persisted start or after terminal settlement.
Reason: Closure must work independently of manifest-control updates and subtask telemetry. Recording invocation failure must not invent subtask success or logical-goal completion, and cleanup failure must not hide the cause that stopped execution.
Revisit when: Immediate hard-kill repair becomes a requirement; catchable failure closure cannot run after an uncatchable process exit.
## [2026-10-19] Pin the goal QA selection on the parent control state
Context: Children no longer carry a QA selection, so the goal QA gate needs one owner for the selection across resumes, including goals that started before the change.
Decision: The parent's control state records the effective selection on first run (request, else AUTO). An in-flight goal with none inherits the first child's recorded plan selection. A resume asking for a different value is refused with a QA-specific blocked stop.
Reason: Without the pin a resume could silently flip an in-flight goal from off to AUTO, or QA under a different selection than the children were planned with. Refusing before any persistence keeps a rejected resume free of side effects.
Alternatives considered: A dedicated persistQaSelection manifest write, dropped because the transactional updateControlState already gives first-write-wins and the extra method tripped detekt TooManyFunctions.

## [2026-10-19] Skip goal QA for legacy per-child QA and for a settled tree
Context: Children still compose QA until SKILL-431 subtask 2, and a blocked or resumed goal re-enters finalization and would run goal QA again.
Decision: The gate skips when every subtask workflow ran a QA-composed plan with a recorded outcome, or when the parent outcome is passed or skipped on the current tree identity. The legacy skip writes no parent outcome.
Reason: Without both rules a goal runs QA twice, once per child and once at the goal, or repeats QA on a tree it already settled. Writing a parent outcome for the legacy skip would claim a goal-level run that never happened.
Revisit when: Subtask 2 removes QA from goal children and no pre-SKILL-431 goals remain.

## [2026-10-19] Run goal QA before commit-all at finalization
Context: SKILL-431 moves QA from each goal child to one run per goal before the PR.
Decision: finalizePublication calls the goal QA gate after the no-change and already-published checks and before commitAllRemainingWorktree. A blocked gate stops the goal BLOCKED before any commit, push or PR write. The gate also runs when an unmerged PR already exists.
Reason: QA after commit_push would publish an unqualified tree, and qa_fix edits must land in the same commit-all. Running on resume after a PR failure keeps the PR tree qualified; the settled-tree skip stops the repeat.
Alternatives considered: Special-casing single-subtask goals, rejected because one QA path per goal is simpler to reason about.

## [2026-10-17] Keep checkpoint prune off the status read path
Context: Goal status and watch defaulted pruneCheckpointRefs to true, and reconcileStatusManifest deleted eligible checkpoint refs during ordinary projection.
Decision: Status projection requires pruneCheckpointRefs false and no longer calls prune. Default the request flag to false; CLI status and ide-status pass false. Authorized prune stays on finalization, reset, and purge.
Reason: A CLI-only flag left every other status reader able to mutate recovery refs. Read-only status cannot own deletion; repeated reads must preserve refs, rows, and revisions.
Alternatives considered: Patching only the CLI flag, rejected because other readers still reached the assembler prune.

## [2026-10-17] Stop reports incomplete termination unless process exit is confirmed
Context: Goal stop paused first, then mapped inspect or terminate failure to STOPPED, so operators saw success while the owned process was still live or unverified.
Decision: After reserveStop writes the operator pause, only confirmed process exit yields STOPPED. Inspect or terminate failure, or a still-live owner, returns TERMINATION_INCOMPLETE with termination_failed or owner_still_live. The pause JSON stays.
Reason: Pause is the operator request; STOPPED is an observed exit. Fabricating STOPPED hides a live worker and makes a later takeover look like a fresh start.
Alternatives considered: Reporting STOPPED whenever pause persisted, rejected because pause does not prove the process died.

## [2026-10-17] Validate every goal child and fence resumes on the recorded selection
Context: Goal children before the last one ran compile-only build and recorded no validation result. On SKILL-422 the deferred check and detekt debt landed on the last child's validate session.
Decision: The resolver returns VALIDATE for every child. Launch prepare, run preparation and the launch reconciler use a child's recorded quality_gate_selection when present and resolve() only when absent.
Reason: The resolver change alone would make recorded BUILD children fail execution-plan admission, because BUILD and VALIDATE descriptors are incompatible. Honoring the recorded value lets in-flight children finish as they started.
Alternatives considered: Migrating existing child rows to validate was rejected; the spec rules out rewriting rows or retroactive revalidation.

## [2026-10-14] Plan first only when no goal and no bundle exist
Context: A full run must go through the plan skeleton, but stored goals and hand-written bundles must keep working.
Decision: Plan first only when loadDurableByIssueKey finds no stored goal and intakePreparation.prepare finds no bundle. Otherwise go straight to goalRunner.run.
Reason: A stored goal resumes with no planning. A bundle on disk is imported as before, even when an incomplete plan workflow exists, so plan resume applies only without a manifest. Running hand-written bundles through plan_subtasks belongs to other parent work.
Alternatives considered: Having the CLI choose between open and resume was rejected because StandalonePlanRun.run already does both.

## [2026-10-10] Rewrite status extras together on live goal finalization
Context: GoalRunnerStatusProjectionRuntimeInputs keeps currentStepOverride and latestLivenessSignal as separate extras. The assembler already copied currentStepId from the goal_finalization declared event and left latestLivenessSignal on the parked parent row.
Decision: When the parent lease is LIVE and the latest declared event is goal_finalization, copy both currentStepId and latestLivenessSignal from the same parent progress rewrite, using resolvedLivenessSignal(liveStepId).
Reason: Updating only the step produced 0AC-46, current_step=monitor with latest_liveness_signal=workflow_status=PAUSED; step=plan. A second progress implementation was rejected because OutcomeStore.progress already forwards to progressRecording.progress.
Alternatives considered: Walking the parked parent through implement, rejected because children still own that phase.

## [2026-10-10] Defer idle child progress only during live goal finalization
Context: currentSubtask.workflowId can still name a completed child during monitor, so child commit_push would override the live goal step. Skipping every idle child then hid implement/review and CLI goal status fell back to preplan.
Decision: Use live child progress whenever the child is not idle or completed. Keep idle or completed child progress unless the parent is LIVE on a goal_finalization declared event; only then rewrite from the parent.
Reason: Child implement/review remains the live signal until goal_finalization starts. Unconditional idle skip hid running children; unconditional child-id presence hid monitor after children finished.
Revisit when: Subtask 2 reads these extras through goalRunnerStatusService.status for ide-status.

## [2026-10-10] Keep operator pause on controlState during liveness rewrite
Context: Decomposition parks the parent as WorkflowStatus.PAUSED at plan. Operator pause is a different control-state flag.
Decision: extras.paused, pauseRequested, pauseReason, and pausedAt stay sourced from controlState. Liveness rewrite must not treat parking as an operator pause.
Reason: SKILL-362 still requires a truthful idle-versus-pause distinction. Clearing pause because the parent row is PAUSED would hide a real operator stop.

## [2026-10-09] Stop reruns of a finalized goal
Context: The manifest reports complete as soon as every subtask is terminal, before the commit, push and PR. Rerunning a finished goal therefore finalized it again against whatever worktree and branch were current.
Decision: After a Completed or CompletedNoChange report, the runner records goal_completed_at and the PR URL in the goal control state. A later run returns AlreadyComplete before migration, planning or finalization while that marker exists and every subtask is still terminal.
Reason: Only successful finalization proves that the goal finished. Hard reset clears control state, and a scoped replan reopens a subtask, so both start real work again without a separate unmark step.

## [2026-10-09] Commit leftover paths at goal finalization in every execution model
Context: Same-branch goals stopped at finalization when the worktree had changes outside a subtask commit, which left the operator with no way through.
Decision: Same-branch finalization uses the same commit-all, push and clean-worktree verification as stacked branches. The protected-branch and feature-branch checkout checks still apply.

## [2026-10-08] Apply no-change decisions on resume, not at record time
Context: An operator decides a no-change pause through a separate command while the goal is stopped, and the process can crash between recording the decision and acting on it.
Decision: The decision service validates the request and writes the decision to the no_change_pause artifact only. The goal runner applies it at the selected-subtask step on the next resume, and stops again with awaiting_no_change_decision while the decision is still null.
Reason: A crash after recording leaves a decided artifact that the next resume applies. Applying is idempotent and has a single call site, so the runner never decides on its own.
Revisit when: retry_fix and abandon_subtask resume are implemented, or the operator-decision command starts driving the goal forward itself.

## [2026-10-08] Route no-change decisions ahead of review-remediation rejection
Context: Operator decisions share the GoalSubtaskOperatorDecision wire values with review remediation, which rejects them for a subtask with a child workflow.
Decision: The decision service checks for an undecided no_change_pause on the subtask's child first. Only then does it apply the no-change rules; every other subtask keeps the existing rejection unchanged.
Reason: Reuses the existing wire values without changing the review-state schema or reopening review remediation.
Alternatives considered: new no-change wire values, rejected because they would change the review-state schema.

## [2026-10-08] Finalize no-change goals without a pull request
Context: A goal can end with every subtask skipped for commits, after an operator accepted a no-change pause.
Decision: When no subtask has a commit_sha and at least one is completed_no_change, the goal finishes as CompletedNoChange with the first pause reason and no commit, push, PR or acceptance evidence. Mixed goals finalize exactly as before.
Reason: Completed means a PR exists, so an empty PR would misreport the outcome. The pause artifact stays in the child-workflow store as the evidence.

## [2026-10-04] Return preparation conflicts as data
Context: SKILL-399 subtask 4 replaces preparation exceptions whose readers used workflow identity, subtask identity and recovery reason to steer execution.
Decision: Put data-only conflict and result declarations in runtime-ports. Readers branch on returned conflicts; engine-owned toFailure converts them where existing callers must stop by throwing.
Reason: A conflicted stored plan is an expected outcome under A7. Returning its details preserves subtask-specific recovery without reading caught-exception properties, while limiting API changes to methods that produce or propagate conflicts.

## [2026-10-04] Detect preparation conflicts before transaction writes
Context: SQLite transactions commit returned values, including Conflicted. Preparation and child-persistence operations can otherwise mutate state before detecting a conflict.
Decision: Run conflict checks and read-only child hydration before parent, child, cascade or checkpoint writes. Keep SQL faults throwing through the transaction and write manifest projections only for saved children.
Reason: Returning a conflict after a write would commit partial preparation state. Checking first leaves durable state unchanged on refusal; throwing on a mid-write SQL fault preserves rollback.

## [2026-10-04] Preserve migration before classifying recovery failures
Context: The base branch added transactional planning and phase-output migration while SKILL-399 replaced schema exception classes and preparation repository return types.
Decision: Keep migration admission before planning reads and child ownership acquisition. Adapt its reads and guarded catches to the coded failures and typed results. Preserve source checks, transaction ownership, rollback, and import history. Use scoped replan for stale planning conflicts. Unsupported or corrupt records retain their saved bytes and report the repair needed to resume.
Reason: A version mismatch with a supported conversion must recover through migration before classification can stop execution. Hard-reset guidance and exception-message classification would bypass that recovery. This preserves the base behavior under A7 and P5 without widening a catch or weakening a guard.


## [2026-10-11] Resume blocked planning after bundle authoring
Context: A plan can write its decomposition manifest and then fail the outside-bundle scope guard. The existing-manifest refusal previously prevented a key-only retry of that incomplete plan.
Decision: Resolve the repository-scoped plan continuation before refusing an existing manifest. A resumable plan re-enters its own plan step; completed-plan settlement and refusal of unrelated existing manifests retain their existing behavior.
Reason: An authored manifest does not prove that planning settled. The CLI regression creates this failure and proves that retry keeps one workflow, skips preplan and saves both implementation checkpoints.

## Ignore unrelated edits when settling a plan

On 2026-10-11, the operator removed the rule that blocked plan settlement when
other files changed during planning. Shared worktrees can receive concurrent edits.
The plan still verifies its authored bundle and rejects bundle paths that escape
its directory. Unrelated worktree changes do not determine bundle readiness.

## [2026-10-19] Purge names sibling-worktree goal state instead of deleting it
Context: Repository identity is the worktree path, so another worktree of the same repository keeps its own goal state for the issue.
Decision: Purge leaves that state alone and names the worktree that owns it.
Reason: The sibling worktree may hold an active goal run. Reporting it lets the operator purge from that worktree without this purge reaching across repository identities.

## [2026-10-19] Refuse to conclude an unfinished git operation at finalization
Context: 0AC-65's goal monitor started `monitor_fix` after main moved. The agent began merging main into the feature worktree, and the run died before the merge was committed or validated. On resume, finalization commit-all staged the tree and concluded the merge, then the push was rejected because the PR branch had a commit pushed from elsewhere.
Decision: Before staging, finalization asks the git port for an in-progress merge, rebase, cherry-pick, or revert and stops with PULL_REQUEST_FAILED naming the operation and its `--abort` command. It does not finish or abort the operation itself.
Reason: Commit-all sweeps leftover files, but an unfinished operation is half-done repair work that no phase validated. Resume cannot re-enter `monitor_fix` directly because the goal monitor is an in-memory skeleton that reaches repair only from a fresh CI observation. Aborting would discard work the operator may want to keep, so the operator decides. After an abort, the next resume reaches the monitor, which sees the conflict and runs the repair again.

## [2026-10-20] Commit an unfinished git operation at finalization instead of stopping
Context: The 2026-10-19 guard stopped SKILL-432 finalization on a clean, already-resolved merge of main that an interrupted monitor repair left behind. The operator had to commit it by hand and resume. A stop here cost more than the risk it guarded against.
Decision: Finalization no longer stops on an in-progress merge, rebase, cherry-pick, or revert. It records a best-effort warning naming the operation, then commit-all stages and commits the remaining changes as usual, which concludes a merge. Supersedes "Refuse to conclude an unfinished git operation at finalization".
Reason: Runs should warn and continue rather than block. The pushed commit goes through the CI monitor like any other, and that monitor repairs a bad merge or a rejected push.


## [2026-10-20] Scope child outcome reconciliation to the owning goal
Context: 0AC-68 status applied an older completed child from another worktree to a live validation child because outcome discovery grouped by issue key and subtask ID across parent goals.
Decision: Outcome reads and reconciliation require the parent workflow ID. SQLite filters by that parent before pagination and workflow decoding. Stale-outcome cleanup and measured completion writes use the same scope. Completion preference among children of the same parent remains intact.
Reason: Issue keys and subtask IDs repeat across worktrees and restarted goals. The parent workflow identifies the goal that owns those children.
## [2026-10-22] Persist scoped replan as an accepted snapshot mutation

Context: Scoped replan discarded selected planning details through an ordinary update of the completed plan workflow. Both the engine and SQLite reject changes to terminal snapshots through that path, so an authorized replan failed before it could retire the stale child.

Decision: Mark the selected bundle update as an accepted snapshot mutation and persist it through the existing named save. Keep the plan workflow's terminal status, finished time, steps and sibling planning details unchanged. The existing scoped-replan coordinator continues to enforce target selection and idle ownership before this transaction.

Reason: Operator replan is an explicit mutation of retained planning, not an ordinary terminal replay. Reuse the existing operator mutation contract without changing terminal replay guards or giving ordinary saves permission to overwrite accepted history. A SQLite regression verifies both the authorized update and rejection of an ordinary terminal rewrite.

## [2026-10-22] The latest run for an issue key is the actual run

Context: SKILL-437 was first used for an inline-review plan whose bundle was later renamed to SKILL-439. Its unfinished plan workflow kept the key. A new, unrelated SKILL-437 bundle then launched, the lookup resumed the old plan, and the run failed at once because that plan's spec path no longer existed. The lookup also refused any key with more than one unfinished workflow as ambiguous.

Decision: Continuation lookup classifies only the latest workflow for a key and repository: terminal gives `TerminalOnly`, running gives `AlreadyRunning`, anything else gives `Resumable`. The `Ambiguous` result, the `ambiguous` preflight verdict and the `ambiguous` CLI lookup result are removed. A plan run resumes a found plan, or settles from a completed one, only when that plan governs the spec this launch resolves. Otherwise it opens a new plan workflow for the launched spec, which then becomes the latest run. Goal planning links a completed or reopened plan workflow only when its governed spec is the manifest's parent spec; plans imported by the goal's own children stay linked. Goal planning progress reads the plan workflow that started last, not the one updated last, so touching an old run cannot shadow the new one.

Reason: Keys are reused for unrelated specs. A run belongs to the spec it governs, and the newest run for a key is the one the operator means.
