## [2026-10-02] Return rejected required phase writes as values (SKILL-398)

Context: A rejected required start or briefing write is an expected outcome, but it travelled as the exception RequiredPhaseWriteRejected through the recorders, the run-loop bindings and goal planning, and was caught at the attempt boundary. The failure model reserves exceptions for defects.
Decision: The recorders return a sealed RequiredPhaseWrite, either Acknowledged or Rejected carrying write kind, workflow, phase and attempt. Each attempt-boundary site handles it with an exhaustive when and the shared blockRequiredWriteRejection handler. Launch preparation and goal planning carry a rejection as a value (LaunchRequiredWriteRejected, GoalPlanningPhaseProduction.RequiredWriteRejected) so nothing runs after it. Unchanged: no execution after a rejected write, write kind, phase and attempt attribution, terminal recording, secondary-failure diagnostics and cooperative cancellation propagation. Goal planning's attempt and shared-preplan production now also rethrow InterruptedException instead of converting it to a stop.
Reason: A returned value makes the unhandled case a compile error and keeps exceptions for defects, with identical behavior at every boundary.
Supersedes: The raise-and-catch mechanism of [2026-09-28] Acknowledge required phase writes before execution.
Alternatives considered: Keeping the exception, rejected under the failure model; a Boolean result, rejected because it loses the attribution the terminal record needs.

## [2026-09-30] A phase's value content section overrides the generic prose value

Context: The shared settlement directive asked every phase for one prose value carrying everything the next phase needs, while audit's value content section requires exactly `[]` when satisfied. SKILL-386 subtask 1's audit settled `[]` followed by a satisfied summary, and the single-session audit blocked as invalid output.
Decision: The settlement directive and fallback contract defer to a value content section, including any exact literal it names. Audit states that a satisfied value is exactly `[]` with no surrounding text and that the rationale goes in summary. The runtime keeps its exact `[]` interpretation.
Reason: The conflict was in the prompt. Accepting trailing prose after `[]` would let text after the literal hide an open criterion.
Alternatives considered: Leniently reading a leading `[]` line as satisfied when verdict is satisfied, rejected as weakening the audit guard.

## [2026-09-30] Admission routes the gate pack by tracked files when the diff has no concrete owner

Context: Goal-child admission freezes the gate pack from working-tree paths before implementation. A clean tree refused creation and a spec-only diff admitted the gateless review fallback, so SKILL-386 subtask 1 blocked at build with Kotlin changes committed.
Decision: Admission keeps changed-path routing when a concrete pack owns the diff, including ties. When only the fallback owns it or nothing routes, it routes the repository's tracked files and keeps the fallback result if they have no concrete owner. Build-time resolution without an admitted plan still routes the gate's own changed paths.
Reason: The frozen pack must name the platform the gate will build. Tracked files are available and deterministic before any code exists; the recorded digest still pins that pack for resume.
Alternatives considered: Resolving the pack at the first gate run would reopen the frozen policy digest; routing tracked files unconditionally would override a standalone run's concrete diff in mixed repositories.

## [2026-09-30] Authoring sessions run safe scoped project checks; audit loses its compile exception (SKILL-385)

Context: Authoring prompts banned every command, so formatter and static-analysis findings surfaced only at validate, while audit alone could compile.
Decision: One strategy-supplied project-authoring guidance owner reaches implement, simplify, audit_implement_fix, implement_fix and inline review's direct launch. It admits safe file-scoped formatter and standalone analysis commands after scope and safety proof, and defers with a concrete reason otherwise. Build keeps compile proof, validate keeps tests and full checks, and audit runs no commands. Evidence stays in existing output fields.
Reason: Project rules are discovered by the agent from the repository, so the runtime stays platform-neutral and adds no executor, schema or telemetry.
Alternatives considered: A runtime discovery executor or a new evidence field would add authority and contract surface without narrowing what the agent must prove.
Supersedes: [2026-09-29] Audit may check application compilation after repairs.

## [2026-09-29] Audit may check application compilation after repairs

Superseded by [2026-09-30] Authoring sessions run safe scoped project checks; audit loses its compile exception (SKILL-385).

Audit remains responsible for production criteria and sends repairs to `audit_implement_fix`. After repairs it may run the dominant pack's compile-only `validation_gate.build_command`. Compilation failures return to the repair step as production gaps. Audit does not run or compile test targets, lint, or full validation, and a passing compile check does not satisfy test requirements.

The audit directive regression and prompt snapshots cover this exception. The 25 focused audit and snapshot tests passed before reinstalling base.

# featuretask runtime boundary decisions

## [2026-09-30] Bind strategy authority to the accepted step
Context: SKILL-384 subtask 3 found that broad step state, attempt environments and helper paths let strategies reach unrelated mutable state or bypass launch prerequisites.
Decision: Issue private bindings for the accepted run, selected strategy and policy. Strategies and hooks declare their role; bindings expose detached observations and only that role's operations, then close after dispatch. Keep runners, gate cycles and finalization machinery behind their runtime owners.
Reason: Renaming a context or adding getters preserves its authority. Bound operations enforce plan membership and required persistence while keeping the shared loop and existing review, fan-out and gate owners.
Alternatives considered: Another workflow framework or a role interface per helper would add forwarding layers without proving narrower authority.

## [2026-09-30] Keep coupled transitions with one run and session owner
Context: Progress, session, retries, completion and review invalidation must agree during live execution and durable reconstruction.
Decision: Store one FeatureTaskRuntimeRunTransitionOwner per run, paired with one session. Use named transitions; acknowledge durable completion and review tombstones before matching in-memory changes, and required starts before attempt accounting.
Reason: Independent field writers can leave partial transitions or spend retry budget before persistence succeeds. One owner preserves the existing durable and ephemeral policies, checkpoint ownership and audit briefing exception without a new durable format or semantic reset.

## [2026-09-30] Pair transitive capability checks with runtime admission
Context: Strategy authority can leak through helper constructors, extensions, aliases, factories or mutable observation copies even when prohibited type names disappear from entry points.
Decision: Use a Kotlin PSI declaration graph across engine source and a typed primitive-writer inventory. Keep runtime machinery traversable, reject unresolved governed edges, and register synthetic allowed and violating cases with the architecture rule. Retain runtime binding checks and observable behavioral coverage.
Reason: Direct type-name checks miss transitive authority. Source-level reachability checks cannot prove active-step identity or transition outcomes, so accepted-plan admission and behavioral evidence remain separate requirements.

## [2026-09-28] Bind standalone validation to its own command family
Context: Standalone validation could dispatch build commands while reporting validation. Goal-child build and agent-driven workflow validation have different execution contracts.
Decision: Bind SkeletonDefinition.VALIDATION to PackValidationStrategy, PHASE_VALIDATE, and validation evidence. Resolve the dominant pack's full-validation command pair with wrapper overrides; keep goal-child build on its build command pair and build receipt.
Reason: Dispatch, telemetry, and receipts must describe the same gate. Strategy binding preserves the shared run loop and existing agent-validation alternatives without definition-specific dispatch in PhaseRunEntry.

## [2026-09-28] Require command evidence while allowing cached zero-work success
Context: Empty measurements could become successful gate evidence, while a real cached command may execute no work units or new checks.
Decision: Require effective command identity, zero exit status, and checkpoint evidence for a successful terminal applicable command. Retain discovery and repair verification separately, reconcile counts and checks, and block missing required gate declarations.
Reason: Command execution and executed work are different facts. Zero work can be valid evidence; zero command records cannot prove success, and earlier success cannot hide failed verification.

## [2026-09-28] Acknowledge required phase writes before execution
Mechanism superseded by [2026-10-02] Return rejected required phase writes as values (SKILL-398); the guarantees below stand.
Context: Required start and briefing writes returned false while child launches or runtime side effects could continue.
Decision: Raise RequiredPhaseWriteRejected at the persistence owner and handle it at the shared attempt boundary before execution. Preserve write kind, phase, and attempt through terminal recording and secondary failures. Keep cancellation propagation, ephemeral in-memory acknowledgements, and audit's in-memory briefing exception.
Reason: Execution needs an acknowledged prerequisite record. Treating rejected persistence as child failure loses attribution; moving transaction or lease ownership is unnecessary to enforce the prerequisite.

## [2026-09-28] Block uncertain receipt recovery without replaying finalization
Context: Unsupported or empty successful receipts cannot establish current gate semantics, and historical downstream effects may already include commit or push.
Decision: Preserve original evidence and finalization records when gate evidence fails validation. Block uncertain recovery, keep completed runs terminal, and do not regenerate a producer or replay commit/push to repair its receipt.
Reason: Safe regeneration requires compatible execution identity and proof that downstream effects have not occurred. Subtask 2 supplies that identity prerequisite; inventing missing evidence or deleting completion records could repeat effects.
Revisit when: Compatible execution identity and absence of downstream effects can be proven at an existing safe gate boundary.

## [2026-09-27] Scoped replan prunes the cleared child's checkpoint refs
Context: `goal replan` deleted the child workflow but left its `refs/skill-bill/checkpoints/<issue>/<subtask>/<n>` refs. The next child restarts its checkpoint sequence at 0, so its first remediation amend that reached an old number found a foreign occupant. It refused, because overwriting would drop the only reachability that commit had. SKILL-380 subtask 5 blocked at `verify_findings` this way.
Decision: scoped replan prunes the checkpoint refs of every subtask whose child workflow it cleared, the same way reset and scoped child recovery already do (`pruneResetSubtaskCheckpointRefs`).
Reason: a checkpoint ref belongs to the workflow whose identity ledger names it. Once replan deletes that workflow, nothing reads the ref except the next child's collision check.
Alternatives considered: start a new child's sequence after the highest ref already in git (rejected: the identity ledger derives its recorded ref name from its own sequence, so both would have to be threaded through two append paths, and it would keep refs that nothing owns).
Revisit when: a replanned subtask must keep its abandoned attempt's pre-amend snapshots.

## [2026-09-27] A goal child's ownership baseline is the goal-start baseline
Context: `goal replan` deletes the child workflow and clears the subtask's workflow id. The next child snapshotted every dirty path as its baseline, including the earlier attempts' uncommitted subtask work, so its checkpoints committed only part of the subtask. On SKILL-380 subtask 5 the commit referenced a class that existed only as an untracked leftover, and review then refused to amend.
Decision: A goal child's checkpoint and review-checkpoint baseline is its own recorded baseline intersected with the goal-start baseline. The goal-start baseline is the recorded baseline of the earliest surviving goal child. `goalScopedBaselinePaths` applies the intersection in both readers. The recorded baseline itself stays unchanged.
Reason: This completes "Active subtask owns every dirty path" (2026-09-11) for retried subtasks. Operator edits made before the goal started stay protected. Dirt that appeared while the goal ran is the goal's, and it belongs to the active subtask because earlier subtasks committed theirs. It also repairs children that already recorded a leftover-heavy baseline, because the intersection is applied when the baseline is read.
Alternatives considered: carry the discarded child's owned paths forward at replan time (rejected: it needs new durable goal-level state, and it cannot repair children whose baseline already absorbed the leftovers).
Revisit when: a goal branch must share its worktree with edits that start after the goal and must stay out of its commits, or completed goal children stop being retained.

## [2026-09-26] Quality gate is a slot with two strategies; validate settles with a verdict
Context: build and validate ran through a routing class that rewrote transitions per `quality_gate_selection`, the handoff carried the selection to drop the unselected gate's projections, and validate's shrink decision compared agent-reported `validation_passed` and remaining text.
Decision: A `SkeletonDefinition` (standalone, goal child) lists the slots; its declaration equals the phase workflow's. The `quality_gate` slot has two strategies, `PackBuildStrategy` and `AgentValidateStrategy`, in their own packages. Selection facts pick one per run: a BUILD goal child runs build, the final child and a standalone run run validate. Traversal, lookup, and projection omission read the unselected steps generically; nothing rewrites transitions. Gate progress goes through `PhaseRunState`. Validate settles with the uniform output: completed means every check passed; blocked carries the remaining failures and a verdict, `progress` continues the repair and `no_progress`, absent, or unknown blocks with `needs_user_action` (absent or unknown is also a diagnostic). Resume derives validate success from the step status. `biuld` is a CLI usage error; the legacy heal to validate stays with its adoption record.
Reason: Strategy slots keep shared runloop, phase, and runner code ignorant of gate kinds, and the agent that ran the checks is the one that knows whether its leftovers shrank.
Supersedes: Validate keeps repairing until true (2026-09-20)

## [2026-09-20] Validate keeps repairing until true
Context: Three honest `validation_passed: false` reports burned the output-gate cap while `./gradlew check` was still red. The agent knew the leftover detekt and Feed failures and stopped because the phase required a boolean handoff.
Decision: Do not emit until `validation_passed` is true. Keep repairing in the same session. False is not a successful handoff: continue only when the remaining-failure text shrank; block when leftovers stay the same. Wall-clock timeout still stops the subtask. Malformed JSON retries twice.
Reason: The operator chose a remaining-set stall over a false boolean as the stop. Raising the envelope cap would not finish a huge leftover pile, and treating false as schema failure hid real check work.
Supersedes: Validate discovers project checks and retries up to three times (2026-09-20)
Superseded by: Quality gate is a slot with two strategies; validate settles with a verdict (2026-09-26)

## [2026-09-20] Commit_push does not block on validate tree fingerprint
Context: After validate, the worktree fingerprint no longer matched the capture. commit_push treated that as stale identity and blocked instead of committing.
Decision: A validate-era tree fingerprint that no longer matches the worktree is not a commit_push block, including HEAD that moved by committing that tree. Stage every uncommitted and unstaged dirty path and commit. Base-ref identity still blocks.
Reason: The operator said the fingerprint mismatch is not a block reason and required the uncommitted, unstaged tree to land.

## [2026-09-20] Validate discovers project checks and retries up to three times
Context: Pack `validation_gate` argv, including cache-bypassing collect-all, told the agent which command to run. That is a platform-pack recipe, not the project's own checks. The operator rejected pack-defined validate commands.
Decision: The validate agent discovers commands from the repository (instructions, build and test config, scripts, CI). It does not run pack `validation_gate` argv or `bill-code-check`. It repairs and reruns those project checks up to three times in session, then returns boolean `produced_outputs.validation_passed`. False, missing, or malformed output relaunches the phase up to three times. Runtime does not rerun the checks. Commit_push readiness selects project CI checks only, not pack collect-all.
Reason: The project's AGENTS.md, Gradle, and CI are the source of truth. A pack flag such as `--no-build-cache` is not a project command. Three occupancies keep a repair window without substituting pack argv for discovery.
Supersedes: Validate reruns the pack gate up to three times (2026-09-20)
Superseded by: Validate keeps repairing until true (2026-09-20)

## [2026-09-20] Validate reruns the pack gate up to three times
Context: After validate reported a boolean success, commit_push blocked on readiness check `pack-collect-all` with no repair occupancy. The 2026-09-19 boolean-only phase trusted the agent and reran the phase once on false; it did not rerun the pack gate.
Decision: Validate is again runtime-owned: each occupancy invokes `bill-code-check` to fix the open set, then the runtime reruns the pack cache-bypassing collect-all gate. Repeat until the gate is green or three verify reruns are exhausted. `MAX_REPAIR_TURNS` is 3. A green runtime confirmation, not `validation_passed`, advances the phase. Paused launches and `needs_user_action` still stop immediately.
Reason: The operator required the validation phase to rerun gates up to three times to fix remaining findings before commit_push. A boolean envelope cannot stand in for pack-collect-all.
Supersedes: Validate returns a boolean project check result (2026-09-19)
Superseded by: Validate discovers project checks and retries up to three times (2026-09-20)

## [2026-09-19] Validate returns a boolean project check result
Context: Projects choose their own checks and environments. A runtime command receipt added detail without proving execution, and a second runtime check duplicated phase work.
Decision: The agent discovers and runs project checks, repairs failures, and returns boolean `produced_outputs.validation_passed` with details in `value`. True advances. False, missing, or malformed output gets one retry, then blocks. Process failures retain their separate bounded retry policy. Resume and goal status use the same boolean. Runtime never infers success from command strings or exit codes.
Reason: The user explicitly chose to trust the phase result. This is an orchestration signal, not execution attestation. Readiness still tracks its existing selected checks against repository identity after a successful phase.
Superseded by: Validate reruns the pack gate up to three times (2026-09-20)

## [2026-09-19] Validate relies on the phase check
Context: The validate agent invoked `bill-code-check`, which confirmed its repairs, then the runtime ran the same cache-bypassing gate again.
Decision: `bill-code-check` owns check execution, repairs, and confirmation. The runtime accepts the phase's command and exit-code evidence through the existing output gate and persists completion without another gate execution. Runtime execution counts remain zero for new phase-owned checks. Validate allows two output attempts, with one correction after a rejected result. The phase owns pack selection and the confirmation command, so runtime does not compare its receipt with a separately routed command. The goal stops when those attempts fail and requires an operator resume instead of adding its own validation retries.
Reason: The user chose to rely on the phase check and remove duplicate verification. Failed processes, blocked results, missing evidence, and failed confirmation evidence still prevent completion.
Superseded by: Validate returns a boolean project check result (2026-09-19)

## [2026-09-19] SKILL-364 sibling readiness evidence contract
Context: Validate-owned `validation_evidence` records pack gate commands only; commit and PR progression needed a separate durable record for tree identity, base ref, workflow-selected checks, and per-check results without bumping the validation contract.
Decision: Add `readiness_evidence` as a sibling contract and workflow artifact (`FEATURE_TASK_RUNTIME_READINESS_EVIDENCE_ARTIFACT_KEY`). Validate still owns pack collect-all execution; commit_push owns readiness settlement and persistence.
Reason: Mixing PR/plugin readiness into validation evidence would break Issue 341 / 0AC-16 settlement and blur phase ownership.
Revisit when: a single envelope can subsume both without a contract bump.

## [2026-09-19] SKILL-364 write-tree identity versus checkpoint fingerprint
Context: `repositoryCheckpointFingerprint` hashes owned paths for validate receipts; commit readiness must ignore boundary `agent/history.md` and this run's `.skill-bill/run-evidence/<workflow-id>/` paths.
Decision: `source_tree_sha` is `git write-tree` on a copied index with those paths removed; `base_ref_sha` is `origin/<baseBranch>`. Checkpoint fingerprint semantics stay unchanged.
Reason: History and run-evidence are allowed post-validate output; folding them into source identity would force needless reruns or accept unvalidated source edits.
Revisit when: checkpoint fingerprint is retired or run-evidence addressing changes.

## [2026-09-19] SKILL-364 failed commit_push readiness rerun is needs_user_action
Context: A failed or missing selected check at commit_push must not reopen validate or add repair occupancy.
Decision: Readiness failures block commit_push with `needs_user_action`; resume retries readiness on the same dirty tree with `last_resumable_step=commit_push`.
Reason: Validate already spent its repair budget; commit_push is the PR-readiness boundary.
Revisit when: readiness gains its own bounded repair loop.

## [2026-09-18] commit_push does not launch an agent
Context: Agents emitted two phase-output envelopes on `commit_push`, and the schema gate blocked a non-retrying phase. The only agent field the runtime consumed was a commit subject; staging, commit, push, and `commit_sha` were already runtime-owned.
Decision: `commit_push` skips the agent launch. The runtime stages every dirty non-ignored path, commits with a subject from the issue key and subtask name, pushes, and persists `commit_sha`. Downstream readers still use `commit_push_result.commit_sha`.
Reason: A subject string is not worth a structured agent turn. Double JSON at a non-retrying gate stranded finished subtasks.
Revisit when: commit subjects need human-authored outcome text that the manifest subtask name cannot carry.

## [2026-10-01] Audit repair allows two non-shrinking rounds
Context: SKILL-388 blocked after one repair because audit reported AC-006 where it had reported AC-007. The repair fixed AC-007 and the fresh full audit found a different gap, yet the equal count read as a stall.
Decision: A remaining list that shrinks after repair always relaunches repair. An unchanged, replaced, or grown list relaunches repair at most twice per subtask workflow; the third such round blocks. Each allowed non-shrinking round appends an `audit_non_shrinking_round` continuation to the phase ledger, and the cap counts those entries, so it survives process restarts and an operator audit retry does not reset it.
Reason: Each audit reinspects every criterion, so one non-shrinking round can be fresh evidence rather than a stalled repair. The `audit_repair` edge has no per-edge cap, so the bounded budget keeps the loop finite once shrinking stops.
Revisit when: the `audit_repair` edge gains a declared per-edge cap, or in-memory phase runs start running audit (their records keep no ledger, so the cap would never fire).

## [2026-09-17] Audit repair cycles stay in one session and remaining text carries a reason
Context: Auditors inspected once, emitted remaining ACs with no why, and the runtime relaunched. Three runtime relaunches would recreate the remaining-criteria storm.
Decision: The audit briefing asks for up to three repair cycles inside the same agent session. Remaining-criteria text that is not `[]` includes a reason per leftover criterion. That briefing is runtime-owned and identical for every dominant platform pack; packs do not author remaining-criteria settlement. The runtime still treats any non-empty remaining text as unstructured prose: no schema on that list, one outer remaining-criteria retry, then block when the text is unchanged.
Reason: Cycle count and reasons are instructions to the agent. Enforcing them in the envelope would be a new contract. Relaunching the process is the bounded outer retry, not the inner loop.
Revisit when: remaining-criteria settlement grows a structured per-cycle field.

## [2026-09-17] Remaining-criteria audit retries repair beyond implement owned paths
Context: Implement can checkpoint a narrow `scoped_owned_paths` list. Audit then treated that list as a write allowlist, re-emitted the same remaining ACs, and the runtime relaunched with the old checkpoint forever.
Decision: Remaining-criteria text is a focus hint on a fresh audit session that may edit any files those criteria require. Audit extends the owned-path inventory and checkpoints those writes before the next round. The same remaining list as the prior session blocks.
Reason: The remaining-AC loop is repair, not a frozen re-read of implement. Identical leftover text with no progress is a stall, not another session.
Revisit when: remaining-criteria settlement is a topology edge with a declared per-edge cap.

## [2026-09-16] commit_push has no extra-files category
Context: The commit_push briefing told agents never to list `.feature-specs/` in `changed_paths`, and finalisation dropped those paths from the staged set. Agents then emitted two envelopes to "fix" the list, which blocked a non-retrying phase. Spec moves such as `.feature-specs/done/` were left uncommitted.
Decision: The agent may list every dirty path. Finalisation stages every dirty non-ignored path, including `.feature-specs/`. Goal-level leftover spec dirt after the parent records `commit_sha` stays ignored at goal finalize so same-branch completion does not block on that post-commit write.
Reason: There is no extra-files category. Spec trees are worktree output of the subtask. The briefing that carved them out caused a double emit and stranded deliverable spec moves.
Revisit when: the parent stops writing the decomposition manifest after the subtask commit.

## [2026-09-15] SKILL-247 subtask 3 — run-loop helper dependency baseline and after-state
Context: F-005 documented 122 context extensions and duplicate PlanningBranch run-loop overloads; subtask 3 narrows PlanningBranch, Drive, ValidationGate, AttemptSettlement, Review, and PhaseAttempts seams without changing phase order.
Decision: Record before/after extension counts and retained broad inputs in `runtime-kotlin/ARCHITECTURE.md` under State Ownership; peel run-loop-only overloads; keep `runPhaseDriveLoop` and `invalidateReviewGenerationIfNeeded` as the only Drive context extensions; pass carried-forward review, gate settlement, pack routing, and checkpoint calculations through explicit arguments; retain context at validation agent-turn/fix-loop orchestration and review launch capture because those paths still coordinate the launch, activity, diagnostics, clock, transition, and session ports; remove public collaborator aliases on `FeatureTaskRuntimeRunLoop`.
Reason: Helpers must not gain authority through a renamed all-access receiver; the loop stays the sole owner of forward drive and session transitions.
Revisit when: a new helper needs a full context and no smaller port set can be named.
Superseded by: Bind strategy authority to the accepted step (2026-09-30)

## [2026-09-15] Run-evidence ownership derives from the active run, not the store prefix
Context: every path under `.skill-bill/` was runtime-private, so the whole `run-evidence` store was exempt from the owned-path inventory. This run's own artifacts never blocked, but a foreign workflow's artifact and a file forged under the same directory were swept out with them.
Decision: `isRuntimePrivatePath` no longer claims `.skill-bill/run-evidence`. The checkpoint scope resolves ownership from the active run's identity against the deterministic publication address `.skill-bill/run-evidence/<workflow-id>/<fingerprint>/`. Only this run's own address is runtime-owned; anything else under the store root stays an ordinary actionable path.
Reason: the exemption was load-bearing in one direction only. A prefix-free rule blocks the run's own evidence; a blanket prefix rule silently absorbs someone else's file. The address the store already writes carries the provenance both directions need.
Alternatives considered: a durable published-inventory port read at checkpoint time (rejected for now: the publication address is already deterministic from workflow id and checkpoint fingerprint, so a second durable authority would restate it).
Revisit when: the store stops addressing artifacts by workflow id, or a run must adopt evidence published under another workflow's address.

## [2026-09-15] A verification boundary needs an observed lane disposition, not just empty claims
Context: a review pass that produced no output at all reached `emptyClaimsVerificationShortCircuit` with no claims and a blank prose input, which recorded `VERIFICATION` as REACHED. A lane that never ran was indistinguishable from a lane that genuinely found nothing.
Decision: `ParallelReviewLaneStatus` carries the pass's `ReviewLaneReviewDisposition`. The verification boundary is recorded only when the lane succeeded, was not interrupted, and published output. Otherwise the stage returns a typed `ReviewVerificationNonSuccess` that preserves existing findings and durable verdicts and leaves the boundary unreached.
Reason: `mergeResult.formattedOutput` substitutes a placeholder for blank lane output, so the absence signal was already erased by the time verification read it. The disposition has to be carried from where it is observed.
Revisit when: a review mode can legitimately complete a pass without publishing any output.

## [2026-09-15] SKILL-236 missing-terminal and PR-failure observations: reproduction disposition
Context: subtask 3 owns recording which of the reported missing-terminal-outcome and PR-failure observations reproduce inside its scope.
Decision: Reproduced and repaired here — the verification stage boundary reached on an empty or failed review pass, and the evidence broker's unconditional `repeated_evidence_read` refusal. Governed by SKILL-235's landed decision and receipt reconciliation (commit 93370d75d) — terminal decision and repair-receipt finalisation; this subtask reuses it and adds no second finalisation authority. Unreproduced and claimed unrepaired — the historical PR-step failures, which no fixture in this scope reproduces.
Reason: claiming a repair for a failure class nobody reproduced would put a false clearance in the very telemetry this feature exists to make truthful.
Revisit when: a reproduction fixture for the PR-step failures lands, at which point the repair belongs with SKILL-235's finalisation authority rather than here.

## [2026-09-11] Active subtask owns every dirty path
Context: commit_push blocked needs_human when any dirty file was missing from the frozen ownership inventory, including required `agent/history.md` writes.
Decision: The active subtask owns every non-runtime-private dirty path. Checkpoints and commit_push stage them. Review identity does not treat extra dirt as foreign.
Reason: The inventory was a branch-setup snapshot, so later legal writes looked like someone else's work and stalled the goal. Operator intent is that whatever is dirty on this subtask is this subtask's.
Revisit when: a run must share a worktree with a second in-flight subtask that must keep its own uncommitted files out of this commit.

## [2026-09-10] write_history and commit_push never reopen earlier phases
Context: After the bounded review_fix round, implement_fix leaves owned dirty files. commit_push treated that as a stale review, wiped audit and later phases, and the drive loop bounced back to audit.
Decision: `write_history` and `commit_push` stay forward-only. Owned implement/implement_fix paths plus declared boundary-history may be finalised. Foreign dirty content blocks needs_human. Matching subtask trailer on HEAD keeps review identity across tree drift.
Reason: The shipped topology already allows only `audit_gap` and `review_fix`. Replaying audit/review from finalisation discarded a completed review-fix round and could not converge while the same owned files stayed dirty.
Alternatives considered: Keep re-entering audit for any non-history dirty path (rejected: infinite bounce after a legal implement_fix). Stage history-only and leave implement_fix files dirty (rejected: the bounded fix round would never land).
Revisit when: a later phase needs a declared backward edge, which would be a topology change rather than a finalisation side path.
Superseded by: Active subtask owns every dirty path (2026-09-11)

## [2026-09-28] Audit inspection and repairs use separate configured models

Context: Audit repaired unfinished implementation inside reasoning-model sessions. Each remaining-criteria retry repeated that combined task.

Decision: The acceptance-audit slot owns read-only `audit` and mutating `audit_implement_fix`. The execution matrix assigns them reasoning and implementation tiers. A persisted nonempty audit result enters `audit_repair`; repair returns to a full-list audit. Each repair pass uses one agent session, and a failed pass blocks for resume. The existing ledger owns loop position and attempt attribution. This supersedes the instruction to repair inside audit.

Reason: Inspection and implementation need different configured models and separate resumable attempts. The repair step has a distinct id because code review already owns `implement_fix` and its review-specific receipts. The repair step precedes audit in the internal topology so the existing backward-edge machinery can reopen both steps without adding another state owner. Forward traversal skips this loop-only repair step until audit reports gaps.

Revisit when: Audit requires structured per-criterion findings or a different repair budget.

## [2026-09-29] Audit repair requires decreasing criterion counts

Decision: Resolve remaining finding identities through the saved acceptance criterion catalog. Canonical briefing IDs and original spec labels identify the same entry. Count finding identities rather than references embedded in their explanations. After repair, allow another round only when the count decreases. Unidentified findings, conflicting aliases, and missing or unusable comparison evidence block through normal phase settlement. The existing accepted audit output and repair ledger remain the durable comparison authority.

Reason: SKILL-384 repeated capability gaps while changing descriptions and label forms. The previous parser counted standalone S3 labels as one text item and allowed equal counts with different IDs. A suffix completion check also accepted a refusal ending in the marker text. Require the marker as a complete final content line and retain the independent fresh audit after repair.

Superseded by: Audit repair allows two non-shrinking rounds, 2026-10-01. Audit reports findings while the runtime owns repair admission, 2026-10-02.

## [2026-09-29] Unfinished audit repairs block and resume with saved work

Context: SKILL-384 launched eight more repair agents after partial final responses. The incomplete-work branch ignored the step's single-session policy. It saved reports but the audit-repair prompt omitted them. A no-progress audit block also retained the older accepted report instead of the latest valid findings.

Decision: Persist partial repair output and block with needs_user_action when a single-session step ends unfinished. Render saved audit-repair reports on explicit resume. Persist the latest normalized audit output when progress blocks. An operator-authorized audit retry establishes one fresh baseline after criterion identity validation. The normal completion ledger consumes that authorization, so later automatic rounds still require decreasing counts.

Reason: An explicit retry must receive current findings and previous edits. Automatic fresh sessions without those reports repeat work before audit can measure progress. The existing phase records, attempt history, retry artifact, and ledger own this state; no new durable fields are needed.

## Audit repair continuation after a partial response

An audit repair response can describe applied edits and unfinished production work without an external blocker. The repair policy continues incomplete output and retryable failures in the same phase under the existing retry budgets. The runtime retains repair reports and applied edits for the next continuation session. A completed repair still requires the final completion marker before a new audit. The audit remains a single read-only pass, and an unchanged or larger remaining-criterion list still blocks. A needs_user_action or non_retryable_policy_conflict disposition still stops for operator action. This supersedes the one-session repair stop described above.

## [2026-09-30] Validation repairs all checks in one agent session

Context: SKILL-384 reduced validation failures but returned a partial progress report. The runtime classified that accepted report as a schema correction. The next prompt told the agent to salvage the capture without doing phase work, then interpreted its unchanged report as a stalled repair.

Decision: Validation keeps repairing in its original agent session until every required check passes. Remove the partial-report continuation and its schema-correction routing. Its policy does not relaunch after incomplete or malformed output. Concrete external blockers remain terminal, and their normalized output is retained for status and operator retry. Historical verdict words remain readable without launching another session.

Reason: A formatting recovery cannot measure repair progress. Tests, static analysis, formatting and outdated fixtures are work for the validation agent. The existing phase record, process limits and explicit operator retry own termination and recovery.

Revisit when: The agent launcher supports a governed continuation inside an existing live session.

## [2026-10-02] Audit reports findings while the runtime owns repair admission

Context: SKILL-398's audit blocked after AC-005 remained open following a partial repair. Its prompt required decreasing criterion counts even though the runtime already allowed two non-shrinking rounds. The agent's blocked output bypassed that runtime allowance.

Decision: A finished audit inspection reports completed status and the current production gaps, including repeated or larger remaining lists. The runtime alone applies progress comparison and durable retry limits. The audit agent reports blocked status only when missing or unreadable criteria or an external dependency prevents inspection. Previous reports' retry decisions do not override the current runtime policy.

A completed inspection with open criteria routes to repair. Review admission still requires the existing completion parser to establish that no production criteria remain. Audit stays read-only, repair completion requires its existing marker, and runtime failures retain their existing terminal handling.

Reason: A criterion can require repairs at several consumers before its count decreases. Agent instructions must not replace the runtime's bounded repair policy with a second stopping rule. The existing ledger and progress owner continue to enforce the limit without new durable state.

Evidence: Historical session blocks and local telemetry include SKILL-384's one-edge cap and growing criterion list, SKILL-389's satisfied rationale rejected as an open finding, SKILL-352's repeated remaining text, schema-invalid audit output, lost durable recovery authority, and checkpoint refusal before review. The change retains progress, parsing, recovery, checkpoint and process-failure enforcement. Existing audit regression tests exercise those boundaries, including captured SKILL-389 and SKILL-393 reports.

## 2026-10-03: Plan acceptance-criteria repairs before execution

Context: A repair session can receive several independent production gaps under
one criterion and spend its attempts on incidental changes while leaving the
required behavior missing.

Decision: The acceptance-audit slot now owns read-only `audit_plan_fix` between
inspection and implementation repair. Its reasoning session plans each reported
gap with production paths, ordered changes, dependencies, and closure evidence.
The runtime validates criterion coverage and item fields before persisting the
plan. `audit_implement_fix` executes that persisted plan. Ordinary phase outputs,
handoffs, and the existing audit repair ledger own persistence and resume. The
audit strategy revision changes to 2 so admission cannot silently reinterpret an
older execution descriptor. The historical interpreter retains the original audit and repair
rules and recognizes repair planning as a loop-only step.

Reason: Planning must finish before repair mutates source, and interruption must
retain the plan that justified the repair. This changes the repair loop, not its
retry budget, test exclusion, or full-list re-audit requirement. It follows A1,
A2, A6, and A7 by keeping audit behavior in its slot and reusing owned contracts
and expected rejection results.
