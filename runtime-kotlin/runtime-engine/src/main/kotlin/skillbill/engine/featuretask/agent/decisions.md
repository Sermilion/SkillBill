## [2026-10-25] Interaction-trace re-ask marker stays in memory
Context: A malformed interaction-trace audit gets one re-ask before the trace degrades to unknown, and the runtime needs to remember that the re-ask was spent.
Decision: Hold the marker on the run state (traceReaskScope) rather than a persisted pending-clarification marker.
Reason: The marker only bounds one in-flight attempt, so a persisted field would need a new schema field and migration for little value. The cost is one extra re-ask after a process restart.
Revisit when: restarts during a trace re-ask are common enough that the repeated re-ask shows up in cost or latency.

## [2026-10-25] Repair-state and audit-result 0.1 records fail loudly after the 0.2 bump
Context: Interaction-trace mode added required fields, so the schemas moved from 0.1 to 0.2.
Decision: Add no 0.1 recovery path; decoding a 0.1 record fails on the version mismatch.
Reason: A silent upgrade would invent an empty interaction trace and hide that the run was never traced. Failing loudly keeps the mode's coherence guarantees honest.
Alternatives considered: a version-upgrade shim that defaults the trace to empty, rejected because it would pass untraced runs as traced.

## [2026-10-25] Operator-authored parent specs are exempt from the Transitional Regressions requirement
Context: Authored-bundle verification now requires `## Transitional Regressions` on a bundle parent with two or more subtasks, but an operator can supply the parent spec themselves.
Decision: Require the section only when the parent is rewritable (no protectedSpecSha256); a protected parent is accepted and yields a best-effort diagnostics warning.
Reason: The planner cannot rewrite or block on a spec the operator owns, so rejecting it would strand the plan phase with no repair path. Reusing the invalid-preparation-request block keeps the rule inside the existing in-phase budget.
Alternatives considered: rejecting protected parents too, rejected because the agent cannot repair a file it may not edit.

## [2026-10-25] Validation owns rebuilds and build failure repairs

GV-2 validation repaired source, then blocked because its release package was stale
and the briefing reserved builds for a separate phase. Validation now receives build
permission and owns build failures, packaging prerequisites and rebuilds after repairs
within its existing session. Monitor repair receives the same permission because it
runs the same local checks. Authoring and audit retain their build restrictions.
The compile-only gate stays separate, completed phases stay complete, and artifact
identity checks still have to pass before validation can finish.

## [2026-10-24] An unverified QA pass keeps its verdict but does not close findings
Context: A QA agent can state passed with no assertions, no isolation evidence, missing target coverage or a mocked web backend.
Decision: Keep the agent's stated verdict and attach warnings; QaReport.retestsFindings, not the verdict, decides whether findings are retested and closed.
Reason: The stated verdict is the agent's contract and rewriting it would break the prose report rule. Findings staying NOT_FIXED stops weak evidence from closing real defects without a new verdict class.
Alternatives considered: downgrading passed to incomplete, rejected because it rewrites the stated verdict and shifts settlement and telemetry vocabulary.

## [2026-10-24] Backend and web QA isolation rules live in the shared briefing
Context: Backend and web QA needs environment isolation, setup budgets and mock attribution, but the runtime cannot inspect an agent's effective configuration.
Decision: State the rules once in the runtime-owned QA supplement so every pack inherits them. Keep QA verdict, strategy, repair and settlement code free of target-kind branches.
Reason: The enforceable runtime side is the contract text, the report evidence parsed in a later subtask and resource teardown. Per-kind code paths would duplicate rules and could not verify the agent's environment work anyway.
Revisit when: the runtime can observe the agent's effective environment configuration directly.

## [2026-10-23] Bind acceptance verification to source contents

Context: GV-1 recorded three satisfied AC-001 audits as failed repairs. The
repair recorder compared repository fingerprints that included mutable
`.skill-bill` projections, run evidence, HEAD and index state. Runtime writes
and checkpoint commits changed that fingerprint without changing source.

Decision: Acceptance discovery and repair verification digest current tracked
and untracked file contents through a scoped Git port. The engine excludes
runtime-private files and run evidence using the existing path policies.
Repository configuration and phase instructions remain in scope. The digest
includes executable modes and symlink targets, and remains stable across
staging, commits and committed deletions. Other fingerprint consumers retain
their existing semantics.

Reason: Independent verification must accept unchanged source after runtime
bookkeeping while still rejecting edits after repair completion. Real Git and
SQLite integration tests reproduce the false failure and check source,
configuration and phase-instruction edits. Prior outcomes remain in history;
the existing operator resume path grants a new budget to the blocked run.

## [2026-10-22] Project phase instructions skip with a warning and never touch the output contract
Context: SKILL-434 lets a repo add `.skill-bill/phases/<phase>.md` text to a phase prompt, and that file is untrusted, user-edited input.
Decision: The resolver never throws; an unreadable, oversized, malformed or unknown-phase file is skipped with a warning and the run continues. The prompt section says the text cannot change the output or settlement contract, and the launch briefing records only path and sha256.
Reason: A bad repo file must not block or derail a phase, and settlement stays owned by the runtime. The hash makes each launch auditable without storing the content.

## [2026-10-22] Merge-conflict repair leaves the merge for the runtime to commit

Context: Goal monitor repair told the agent to merge the base branch and also
not to commit. SKILL-438's agent merged, then ran `git reset --mixed` to undo
the merge commit. The runtime committed the merged tree with one parent, so the
pull request still conflicted, and merge-conflict repairs are uncharged, so the
loop had no bound.

Decision: The conflict brief has the agent fetch the base, run
`git merge --no-commit --no-ff origin/<base>`, resolve and stage conflicts, and
leave the merge in progress. The runtime's ordinary commit then records both
parents. Before publishing a conflict repair, the runtime blocks unless HEAD or
`MERGE_HEAD` contains `origin/<base>`.

Reason: The runtime owns commits, so the agent cannot finish a merge itself.
An unfinished merge lets the runtime's commit keep the base as a parent. The
ancestry check turns a flattened merge into a block instead of an uncharged
repair loop.

## [2026-10-22] Reject fabricated evidence in acceptance repair plans

Context: SKILL-438 repeatedly synthesized legacy QA finding text after source
audits rejected it for failing to preserve the original section verbatim.

Decision: Planning and repair use the existing criterion-local failure context
and explicitly explain how their next approach resolves earlier rejections.
They name unavailable historical data rather than fabricating a replacement
for an original artifact required by the criterion. Current source remains the
authority, and independent audit still decides acceptance.

Reason: Retaining failure history helps only when the proposed repair addresses
why the previous approach was rejected.

## [2026-10-22] Retain rejected repair evidence in later repair prompts

Context: Durable accounting retained failed repairs, but planning and repair prompts included only the latest gap. A later rejection displaced earlier counterexamples, so agents could propose local fixes without checking whether the paths still agreed after replay or state advancement.

Decision: Derive the selected criterion's latest three unsuccessful outcomes from existing accounting and pass them to planning and repair. State the included and total counts, retain outcomes across operator resume, and treat the reports as counterexamples to inspect against current source. Require planning to identify the invariant and its owner and trace applicable execution sequences. Require repair to perform a separate read-only source closure pass before reporting completion.

Reason: This repairs the context handoff without new persisted state or output fields. Independent audit remains the acceptance authority. Budgets, progress admission, phase routing, and execution semantics are unchanged, so the wording and prompt delivery change does not require a semantic revision bump.

## [2026-10-22] Block repeated verdict-less audits

Context: An audit completion without a verdict is a clarification request, which
re-runs audit. Nothing counted those requests, so an audit agent that could not
reach the settlement tools relaunched 100 times on one subtask.

Decision: `AcceptanceAuditRound` blocks the step with a `runtime:` reason when a
verdict-less audit follows a pending audit clarification. Repair-admission
clarifications do not count. The check needs no new repair-state field.

## [2026-10-21] Continue partial acceptance repairs without spending failure budget

Context: Direct `progress` repair reports took the same incomplete-work path as
`no_progress`, while read-only admission already continued partial work without
charging a failure. Three partial reports could block an unfinished repair.

Decision: Direct `progress` uses the existing reserved-repair continuation. It
retains the reservation, saved plan, prior failures and source evidence, and marks
the implementation attempt incomplete. Continuations fence the expected revision
so identical reports in later sessions remain distinct and acknowledged replay
stays idempotent. Only independent source verification can accept the criterion. Explicit `no_progress`, failed verification and admitted
failures retain the three-outcome cap. This supersedes guidance that charged
partial progress as an unsuccessful outcome.

Reason: Partial implementation can require several sessions before verification.
A session ending with work remaining does not establish a failed repair.

## [2026-10-21] Settle runtime-owned reviews in the phase transaction

Context: Durable review completion persisted phase records without the settlement trail used by other completed feature-task phases.
Decision: Reuse FeatureTaskPhaseSettlementService with the active unit-of-work repository after durable phase persistence, for ordinary and goal reviews. Keep review runtime-owned and leave agentSettledPhaseIds unchanged.
Reason: The existing settlement owner and table already support review. Sharing the transaction keeps phase persistence and settlement atomic without requiring an agent MCP call or introducing another settlement type or table.

## [2026-10-21] Reuse the review telemetry store for durable passes
Context: Durable feature-task review inherited a no-op recorder, so completed passes could create goal ledger references without review telemetry parents.
Decision: Import non-empty merged findings through the existing ImportedReview persistence path, then persist pass claims. Empty passes use the existing zero-finding reservation. Keep recording before goal review completion.
Reason: Review stats already read review_runs and findings. Reusing those owners makes durable passes count without a third findings store or a change to standalone review recording. Delegated pass claims remain safe to repeat through their existing idempotent path.

## [2026-10-21] Reject new review orphans without a ledger foreign key
Context: Existing operator databases may retain the 30 historical unaddressed_findings orphans identified in the SKILL-433 spec. Their repair belongs to subtask 2.
Decision: Check every non-null review_run_id at ledger replacement before deleting prior pass rows. Stop with REVIEW_RUN_PARENT_MISSING if any review_runs parent is absent, and leave the database schema unchanged.
Reason: The writer check covers in-workflow callers and prevents new orphans while preserving existing databases. Adding a foreign key would require resolving historical rows as part of this subtask.

## [2026-10-19] Reserve atomic-write suffix space in generated spec names
Context: Long feature and subtask titles exceed filesystem component limits, and atomic writes append a temporary suffix to the generated filename.
Decision: Use one 230-byte UTF-8 component budget, retaining the full issue prefix and fixed suffix. Hash oversized normalized slugs with 16 SHA-256 hex characters; use the final names for planned paths, writes, manifest references and links.
Reason: The implementation tightens the planned 240-byte cap so a dot, up to 20 decimal digits and .tmp also fit within 255 bytes. Hashing the complete normalized slug distinguishes long names sharing a prefix without shortening display titles or changing explicit parent paths.
Alternatives considered: Plain truncation risks collisions. Truncating issue identity breaks bundle identity, so insufficient fixed-identity budgets use the existing typed preparation error.

## [2026-10-17] Map settlement store conflict and refusal to an ERROR acknowledgment
Context: A second settlement at the same workflow, phase, and attempt with a different envelope, or an admission refusal, could look like a completed persist at the service edge.
Decision: Persist treats Accepted and IdenticalReplay as OK. Conflicting maps to PHASE_SETTLEMENT_CONFLICT; Refused maps to the refusal reason name. The late envelope is withheld. The SQLite repository returns the store result and does not throw.
Reason: Expected settlement outcomes are values. Throwing inside the sqlite repository hid Conflicting as a defect and still left the first row in place without a typed acknowledgment.

## [2026-10-17] Focused audit reports a defect class, not only the latest gap
Context: Per-criterion audit still looped when a focused pass named one production path, repair fixed that path, and the next audit found a sibling of the same forbidden pattern (SQL LIMIT before parse isolation on a cousin store method).
Decision: Keep one-criterion serial repair and the existing strategy revision. Instruct audit, `audit_plan_fix`, and `audit_implement_fix` that a named gap is an example of a remaining defect class. Audit reports every remaining sibling in that criterion's family. Plan and repair cover that set in one round. Do not open another criterion.
Reason: The runtime already allowed several independent gaps under one criterion. Agents treated the latest-gap briefing as the work item. Prompt text is enough; a semantic revision bump would remap in-flight runs for a wording change.

## [2026-10-17] Goal children stamp validate, not last-child-only build
Context: `GoalRunnerQualityGateSelectionResolver` stamped BUILD for every non-final child and VALIDATE only for the last non-skipped subtask. BUILD is compile-only, so completed earlier subtasks had no boolean validation result and the last child inherited accumulated check/detekt debt.
Decision: `resolve()` returns VALIDATE for every new goal child, so goal children run `review -> validate -> write_history`. A resumed child keeps the selection recorded in its goal continuation. BUILD remains an explicit CLI/env selection and PackBuildStrategy is unchanged. SKILL-415's unrouted BUILD-to-agent-validate fallback stays for explicit BUILD requests.
Reason: Each subtask must close its own full project gate. Deferring validate to the last child hid formatting and static-analysis failures until the end of the goal.
Supersedes: Goal continuation stamps build for every non-skipped child before the last non-skipped entry (SKILL-204).
Revisit when: An operator needs a compile-only intermediate child again.

## [2026-10-19] Monitor repair fixes the pull request, not only the repository
Context: A goal PR failed Danger's title rule. The repair could not fix that through source, so the unchanged publication blocked the goal ("did not publish a source or merge change") even though the agent could edit the pull request.
Decision: Monitor repair may fix a check that rejects the pull request itself with `gh pr edit`. The runtime digests the PR title, body and labels from the identity lookup, records the digest in the monitor output that starts a repair and in the repair publication, and qualifies a tree-unchanged repair as published when the digests differ. Its budget key is `<head>+pull-request-edit:<repair attempt>`, so every edit counts once against the same cap, including an edit that restores an earlier failed title. After such a repair the watcher counts failures only once checks settle and one started after the repair began, or after a ten-minute grace, because commit statuses refresh in place on the same head and `gh pr checks` gives them no timestamp. A repair that changes neither tree, merge ancestry nor PR fields still blocks. The standalone monitor applies the same rule: its pending repair keeps the PR digest and start time, a tree-unchanged repair with changed PR fields is acknowledged as an edit receipt on the same head without a push (key `<head>+pull-request-edit:<receipt number>`), and its watcher uses the same refresh rule.
Reason: Monitoring exists to get every PR check passing, and PR-metadata rules are checks. Runtime-observed digests keep qualification evidence-based instead of trusting narration, and distinct keys prevent uncharged repair loops on an unchanged head.
Supersedes: The source-or-merge-only qualification in "Count failed repair commits independently of repair launches" (2026-10-16).

## [2026-10-16] Recover failed repairs from recorded execution semantics
Context: Resume must retain failed repairs after current phase records are replaced. Legacy monitor verdicts conflated CI failure and merge conflict, and report prose cannot establish which occurred.
Decision: Admit historical CI_FAILED meaning only through the immutable stored monitor selection, dispatch owners and corrected traversal. Join matched START/COMPLETE and generation-zero producer identities, accepted repair completion, pushed baseline/publication outputs and Git ancestry/content evidence. Derive failed SHAs from publication records and restore the distinct failed-commit set.
Reason: A current or synthesized plan cannot prove a historical producer's semantics. Existing accepted evidence preserves prior attribution when the latest repair manifest changes; parsing narration would invent a second protocol. Missing or contradictory evidence retains diagnostics and existing recovery behavior without resetting known failures or adding durable fields.
Alternatives considered: Verdict-only legacy inference and prose-dependent attribution were rejected because neither proves commit-associated failed repair semantics.

## [2026-10-16] Count failed repair commits independently of repair launches
Context: Counting repair entries or merge conflicts could exhaust the budget while the third published repair still had pending CI.
Decision: Use PROGRESS for initial failures and conflicts. Qualify a completed repair through changed attributed source paths or newly integrated merge ancestry, pushed publication and failed CI on the matching head. Count each published SHA once, observe the third result before refusing another CI repair, and keep coupled mutations with FeatureTaskRuntimeRunTransitionOwner across all monitor routes.
Reason: A launch, metadata-only amend, local pass or merge conflict does not prove that CI rejected a changed repair. Distinct published commits preserve the accumulated budget across repeated observations and different failure causes. Unchanged publication uses the existing blocked settlement to prevent an uncharged repair loop.## [2026-10-16] Spend monitor budget from published failed repair evidence
Context: A commit metadata amend could change SHA without changing repair content, and restored monitor loop ordinals included launches that CI had never rejected.
Decision: Require the completed repair manifest to intersect source paths changed between the previous and pushed commit, or prove newly integrated merge ancestry from commit parents. Count distinct published SHAs from completed monitor results retained through COMPLETE ledger entries and producer outputs. Missing retained monitor results block cap-sensitive continuation.
Reason: Commit IDs and backward-edge ordinals describe different facts from source changes and failed published repairs. Runtime-owned monitor and commit outputs use the existing producer-output store so replacement of the current phase record does not discard their reports.
Superseded by: Recover failed repairs from recorded execution semantics (2026-10-16)
## [2026-10-16] Reuse setup without reusing validation results
Context: SKILL-428 observed repeated container setup mistakes. Those observations did not establish that required full validation runs were redundant.
Decision: Retain permitted setup, caches and discovery context within the invocation, revisit changed requirements and run all required checks for each repair candidate. Preserve clean and cache-bypass requirements and invocation resource ownership.
Reason: A working setup avoids needless reconstruction, but an earlier candidate's pass cannot prove a changed candidate. Discovery context reminds the agent to inspect current requirements rather than trusting cached command results.

## [2026-10-16] Keep environment preparation in existing guidance
Context: SKILL-428 requires prose guidance without a new environment contract or blocking condition. Missing Git/Python, unwritable Java user.home and Linux display support were concrete SkillBill failures.
Decision: Extend the shared validation briefing for validation and monitor repair. Keep repository-specific setup examples in runtime command guidance, derive requirements from current instructions and CI, and explain setup retries through existing prose and recovery behavior.
Reason: Repository CI owns the exact tool and setup requirements. A shared fixed tool list would misapply those requirements to other projects, while a descriptor, discovery service or readiness receipt would add contract surface the operator excluded.

## [2026-10-16] Reuse existing monitor progress channels
Context: SKILL-428 addresses silent CI waits and repair periods. The operator explicitly required prose reporting without new schemas, structured progress protocols or completion receipts.
Decision: Forward CI observations and repair activity through existing events, callbacks, CLI/status sinks and goal heartbeat records. Agents report naturally; no consumer parses narration to settle validation or repair.
Reason: Current facilities already carry the needed facts. Another transport or prescribed report shape would add contract surface and turn visibility into an additional success gate.
Alternatives considered: The earlier structured-protocol draft was superseded by the operator's prose-first requirement in the parent spec.

## [2026-10-16] Treat monitor liveness as an observation
Context: Long commands, queued CI and unknown runner capacity can look like stalled work. A live process and passing local checks do not prove source changes or a successful remote CI result.
Decision: Refresh waiting activity through current facilities at the intended 30-second cadence, bound displayed check details and links, and report only observed facts. Leave absent detail unavailable without an additional refusal or administrative lookup.
Reason: Visibility must not invent progress, infer success from a changing pending-check sample, reset deadlines or spend repair attempts. Remote CI remains the authority for the PR's CI result.

## [2026-10-14] Serial acceptance commits use a scoped acquired worker
Context: SKILL-426's first subtask had domain records but no transaction owner coupling criterion outcomes to workflow evidence. A domain success could otherwise become visible before the database accepted it.
Decision: The worker coordinator issues a callback-scoped capability carrying its admitted execution and private lease identity. Serial writes check that capability, expected revision, current criterion, reservation and authored source inside one required write transaction. The same transaction stores serial state, phase records, ledger, implementation evidence and workflow advancement. Callers receive detached committed facts after the transaction returns. Filesystem projection follows commit; projection failure returns committed-with-projection-failure and recovery regenerates only that projection.
Reason: The workflow artifact row remains authoritative. Separate authoritative filesystem writes or publication from inside the transaction would make rollback and restart disagree.

## [2026-10-14] Legacy adoption retains raw and admitted snapshots
Context: Historical audit revisions 1 and 2 can normalize during execution admission. Comparing raw artifacts against the normalized row would reject supported pristine runs or encourage dropping original evidence.
Decision: The acquired worker retains the raw snapshot and the admitted snapshot. Adoption inspects the former and fences against the latter, plus the active owner, source and checkpoint. Only the explicit nine composition/revision tuples with pristine discovery evidence admit a separate provenance receipt. The receipt retains original artifacts and prospective budget semantics. Current descriptor normalization stays intact. No historical serial attempts are invented, and this subtask does not activate audit revision 4.
Reason: Compatibility of a descriptor alone cannot prove pristine history. Retaining both snapshots allows exact historical inspection without restoring an obsolete descriptor into the live execution.

## [2026-10-12] Validation owns checks, commit_push only publishes
Context: SKILL-423 completed validation, then commit_push selected an Ubuntu CI dependency-install command and ran it on CachyOS. The operator required commit_push to commit and push without another gate.
Decision: Delete local CI-command discovery and execution, readiness settlement and committed-head rebinding, and the PR readiness gate. Validation retains project-check discovery and repair. Commit_push stages, commits, pushes and records its receipt. PR retains branch and template checks, and monitor observes remote CI. Preserve legacy readiness artifact decoding so saved workflows remain readable, but ignore those artifacts during publication.
Reason: A second gate duplicates validation and confuses CI runner setup with local project checks.
Supersedes: Commit_push readiness selects project CI checks only, and SKILL-364 commit_push and PR readiness requirements.

## [2026-10-10] A review-fallback-only catalog is unrouted for build and records agent-validate (SKILL-415)
Context: A generic-only install routed every goal child to the review fallback. The fallback has no `validation_gate`, so non-final BUILD children recorded pack-build with no command pair and creation threw before the first child existed (incident 2026-10-09).
Decision: When the dominant pack is the review fallback and has no gate, the resolver reports it as unrouted. Creation for a BUILD request then plans the VALIDATION family with a null pack slug and declaration, so the selection facts pick `agent-validate`. Launch, run entry and repair diagnosis use the planned or recorded selection, not the requested BUILD.
Reason: The fallback owns review only; it is not a build owner. If the plan stayed in the BUILD family and skipped the build-gate check, resume would refuse every new plan. Recording pack-build with a null declaration would run a gate with no commands.
Alternatives considered: Mapping BUILD to agent-validate in the `SkeletonStrategyBindings` ByFact map was rejected because recorded-selection matching would disagree with recorded VALIDATE plans. Treating every absent gate as unrouted was rejected because concrete kotlin/kmp packs without a gate must still refuse (SKILL-360). Giving generic a `validation_gate` was out of scope.
Revisit when: The review fallback gains a build gate, or goal-child gate selection moves out of `GoalRunnerQualityGateSelectionResolver`.

## [2026-10-10] Resume accepts BUILD against a recorded VALIDATE plan only when unrouted (SKILL-415)
Context: The goal runner still requests BUILD for non-final children, but plans created for an unrouted catalog record VALIDATE. A strict selection match would refuse to resume those plans.
Decision: `requireRequestedSettings` treats a BUILD request as compatible with a recorded VALIDATE plan only when the recorded pack slug and declaration are both null. Any other mismatch stays incompatible.
Reason: An exception limited to the unrouted shape keeps the selection resolver unchanged. A VALIDATE plan with a concrete declaration still cannot be resumed under a BUILD request. Recorded generic BUILD plans still hit the recorded build-gate refusal instead of being rerouted silently.
Alternatives considered: Widening `FeatureTaskRuntimeRunEntry.open` as well was rejected because it never calls this check. Accepting any BUILD-versus-VALIDATE mismatch was rejected because it would hide concrete-pack drift.

## [2026-10-10] Stale active monitor status cannot mask a later completed run
Context: Standalone monitor rows used a one-day lease and stayed `active` after the process died. IDE status treated any active/paused/blocked row in 24h as live work, so a zombie 11:02 monitor hid a later green `terminal` run. Operators saw Stale instead of Done after CI passed.
Decision: Live selection includes only heartbeat-fresh active/paused/blocked rows. When none are fresh, retained settled work competes, so a later terminal monitor with a higher run sequence wins. Monitor registrations do not time-box a lease (`9999-12-31`) and are excluded from lease-expiry pause. Registering a new monitor terminals prior active/paused monitor rows on the same branch as superseded.
Reason: Monitor is an open wait-until-green loop, not an exclusive leased worker. Freshness still shows a living watcher; a dead leased row must not outrank completed CI.
Alternatives considered: Keeping the 24h live cohort and only extending the lease was rejected because a two-second crash still occupies the bar for a day. Mutating the Crashlytics rows by hand was rejected because status reads must be correct for existing state.
Revisit when: Wall-clock caps still kill a watching monitor before CI settles.

## [2026-10-10] Cancelled CI checks complete monitor instead of starting repair
Context: Operators treat an all-cancelled check set as finished CI, the same as pass or skip. The watcher folded `CANCEL` into `FAIL`, so any cancelled check settled `ci_failed` and started `monitor_fix`, including when every check was cancelled.
Decision: Only `FAIL` sets `ci_failed` and starts repair, including while other checks are still pending. `CANCEL` is a settled non-failure, like `PASS` and `SKIPPING`. Monitor completes when no check is pending and none failed.
Reason: Cancelled jobs are not failures to fix. Waiting while a sibling is still pending keeps the open wait-until-green contract; an all-cancelled (or pass/skip/cancel) set can complete as done.
Alternatives considered: Treating a single cancelled check as immediate repair was rejected because it fights the remaining pending work. A distinct `Cancelled` outcome was rejected because the run already completes on `Passed`.
Revisit when: A cancelled check should mean a distinct operator action instead of done.

## [2026-10-10] Monitor repair passes the project's local checks before it is pushed
Context: The `monitor_fix` prompt carried the validation-ownership block, which forbids running `./gradlew check` or any full suite, so repairs were pushed unchecked. A merge repair pushed a duplicate declaration that failed compilation in CI.
Decision: `monitor_fix` runs the validation gate. Its prompt carries the same agent validation directive as goal validate: discover the project's checks from repository instructions, build configuration, scripts, and CI, run them, and settle completed only when they pass. `commit_push` follows only a completed repair.
Reason: The checks are the project's own, so they apply on every install, with or without platform packs.
Alternatives considered: A runtime-run pack `full_gate_command`, rejected because packs are not the source of the project's checks and a generic-only install ran nothing.

## [2026-10-10] A merge conflict uses the existing monitor repair loop
Context: A conflicting pull request reports no checks, so the watcher treated it as CI that had not started and blocked after the start timeout. `monitor_fix` never ran.
Decision: An open pull request is conflicting when GitHub reports `mergeable` `CONFLICTING` or `mergeStateStatus` `DIRTY`. The watcher returns that outcome before reading checks. Monitor completes with verdict `ci_failed`, and `monitor_fix` merges the recorded base branch and resolves the conflicts. Unknown mergeability stays unsettled. The three-attempt cap is unchanged.
Reason: Conflict repair is the same commit-and-watch cycle as a failed check. A new verdict would duplicate the backward edge and the cap.
Alternatives considered: Waiting until checks fail, rejected because a conflict often publishes no checks. Treating `UNKNOWN` or `BEHIND` as a conflict, rejected because GitHub has not reported a conflict yet.
Superseded by: Count failed repair commits independently of repair launches (2026-10-16)

## [2026-10-16] Monitor repair budget counts CI-confirmed published commits
Context: The monitor loop treated a merge conflict as `ci_failed` and spent budget when it launched repair. That could exhaust the cap while the third repair commit still had pending CI.
Decision: Initial CI failure and merge conflict use the existing `progress` verdict to start uncharged repair. `ci_failed` is reserved for a failed CI result associated by head SHA with a completed repair that changed files and was pushed. The run loop counts that result once per completed repair cycle and observes the third qualifying result before blocking. Cap prose comes from the failed repair monitor outputs retained for that run.
Reason: Local runs and repeated check polling do not establish that CI rejected a published repair commit. This keeps the loop as the single accounting owner without adding durable fields or a second state store.
Supersedes: The 2026-10-10 decision that merge conflicts used the `ci_failed` verdict, and the 2026-10-08 decision that the capped edge counted repair entries.
Revisit when: The existing phase output and loop records can no longer associate a repair result with its published head commit.

## [2026-10-08] Monitor fix returns to commit_push through a backward edge
Context: The spec routed `monitor_fix` to `commit_push` as a loop-only successor. The transition declaration requires a loop-only successor to be a forward step, so the canonical transitions would throw on initialisation.
Decision: Keep a capped CI_FAILED backward edge from `monitor` to `monitor_fix` (three traversals, BLOCK on exhaustion). Return from `monitor_fix` to `commit_push` through an uncapped backward edge on ADVANCE with its own loop id. `monitor_fix` sits after `monitor` in forward order.
Reason: The existing backward-edge machinery already handles this shape. Leaving the return uncapped means the cap counts only failed CI rounds, so exactly three repair runs are possible.
Alternatives considered: The spec's forward loop-only successor, rejected because the declaration validator refuses it.
Revisit when: The transition validator accepts a loop-only successor that is not forward, or a second phase needs a monitor-owned repair path.
Superseded by: Count failed repair commits independently of repair launches (2026-10-16)

## [2026-10-08] Monitor watches CI through a port and runs without an agent
Context: The run loop must not import the monitor slot's phase constants, and the architecture guards enforce that. CI watching has to finish without a model session.
Decision: `monitor` observes CI through a `PhaseCiObservation` port in slot state and gets back a `PullRequestCiOutcome`. The run loop dispatches it through a `CI_MONITOR` execution binding kind. The GitHub lookup sits behind `PullRequestChecksLookup` in ports.
Reason: The run loop depends only on port types, and the monitor slot owns the GitHub-specific polling. This follows the runtime-settled pattern `commit_push` already uses.
Alternatives considered: Reading the phase id from the monitor slot inside the run loop, rejected by the architecture guards.

## [2026-10-08] A standalone run completes at monitor, not at pr
Context: With `monitor` after `pr`, a run whose last step was `monitor` stayed "running" after `pr` finished, so it could be re-entered.
Decision: The workflow becomes completed only when a monitor-slot step finishes, is not loop-only, and did not trigger a backward edge. A CI_FAILED monitor or a finished `monitor_fix` leaves the workflow running.
Reason: `monitor` owns the PR's final outcome, so `pr` finishing is no longer the end of the run.
Alternatives considered: Completing at `pr` as before, rejected because a PR with failing CI would read as finished.

## [2026-10-06] Feature specs are staged only when they are not gitignored

Context: commit_push stages every dirty non-ignored path, and a tracked path that later matches gitignore is still updated in the index. A repository that gitignores `.feature-specs/` therefore still commits those files once they have been tracked.
Decision: `stagePaths` and subtask finalisation omit feature-spec paths that `git check-ignore --no-index` reports. Untracked ignored paths of every kind stay omitted. A tracked ignored path outside `.feature-specs/` is still staged. When the only dirty paths are gitignored feature specs, finalisation records and pushes the current HEAD.
Reason: Feature specs are deliverable only when the repository tracks them. Gitignore is the repository's statement that those files stay local. Tracked files outside that tree keep the existing index update.
Supersedes: The feature-spec inclusion sentence of [2026-09-16] commit_push has no extra-files category.

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
Superseded by: Validation owns checks, commit_push only publishes (2026-10-12).

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
Superseded by: Validation owns checks, commit_push only publishes (2026-10-12).

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

## [2026-10-10] Owed repair findings continue the round

Context: SKILL-414 subtask 3 blocked on its first `implement_fix` attempt. The
repair report left out two carried findings, the coverage rejection fell through
to the invalid-output block, and no receipt was recorded. Both findings had been
refuted in prose, but the citation came one sentence after the rejection, so
the prose reader kept them as verified.

Decision: A repair report that omits carried findings returns
`PhaseStepOutputCheck.OweFindings`. The attempt loop continues the same round
once with the "Findings still owed" correction and blocks for an operator when
the retry accounts for none of them (`findingCoverageBlockReason`). The
`implement_fix` launch prompt lists the carried findings, which outrank the
verifier's wording. Prose dispositions read citation evidence from any line
that names the finding; refutation and keep cues stay scoped to the sentence
with the id.

Reason: An omission is incomplete coverage, not a malformed report, and one
continuation is enough to recover it. The loop stays bounded because a
non-shrinking retry blocks. Line-scoped citations accept the usual
"F-001: rejected. Foo.kt:12 shows ..." form, while sentence-scoped cues keep
later reasoning words such as "still" from vetoing a refutation.

## [2026-10-10] Goal resume checks out its branch before reading specs

Context: SKILL-414 was resumed while `main` was checked out. Spec drift recovery
compared `main`'s copies of the sub-specs with the stored plans, found drift that
did not exist on the goal branch, and replanned three subtasks into `main`'s
working tree. When the subtask launch then ran `git checkout --merge`, those
edits merged into the goal branch's specs, which left each spec with two
"Implementation Details" sections. That merged file caused another spurious
drift replan on the next resume.

Decision: `GoalRunner.run` calls `GoalRunnerRunPreparation.alignGoalBranch`
before planning migration, spec drift recovery, and the planning sweep. When
the next runnable subtask's branch already exists and is not checked out, it
checks that branch out. A failed check or checkout stops the run with a blocked
report and changes nothing. A goal branch that does not exist yet is left to
the subtask launch, which creates it from its base.

Reason: Spec hashes and plan writes refer to the goal branch's files. Reading or
writing them on another branch produces false drift and cross-branch merges.

## [2026-10-16] Completed workflow phases stay complete
Context: The final acceptance fingerprint check ran on every forward transition. Validation, history updates, and checkpoint commits repeatedly sent SKILL-428 back to audit. Resume also unconditionally re-entered serial acceptance work, including accepted audits.
Decision: Phase boundaries are forward-only for every phase. Remove the post-acceptance fingerprint reopening and its recorder API. Resume preserves accepted audits, and stale-evidence hooks cannot reopen a phase after a later phase has started. Cross-phase repair edges do not re-enter their target; a completed phase advances to its next forward step. Existing audit and review repair loops remain within their phase. Monitor repair commits and pushes before its completion is recorded, then returns directly to monitoring instead of reopening commit/push and PR.
Reason: Later phases own their repairs. An earlier phase's accepted result records work already finished and must not become a new obligation because the checkout changed. This supersedes the older history-to-quality-gate regeneration routes when they attempt to cross a phase boundary.

## [2026-10-19] Standalone monitor adopts a head it did not publish
Context: Monitoring 0AC-64 blocked with "The pull request head moved to c3df18f while monitoring expected e4951ad". `e4951ad` was the monitor's own repair commit and `c3df18f` was the PR owner's fast-forward on top of it. The watcher treated every unrecognized head as foreign and blocked, so the owner pushing to their own pull request ended monitoring.
Decision: While no repair is pending, the watcher adopts a new head. `StandaloneMonitorResources.adoptHead` fetches the head into the owned ref, requires it to still be that commit and the owned worktree to have no tracked changes, resets the owned branch with `git reset --keep`, and records it as published. The ledger appends it to its heads without a published-repair receipt, so the next push leases against it. A failure on an adopted commit routes to repair as initial progress; only a commit the monitor published counts toward the failed repair budget. A head that moves while a repair is pending still blocks at publication, because the repair was built on the older commit.
Reason: Pushing to your own pull request is normal and does not threaten the monitor's work when nothing is in flight. Blocking only where the monitor would overwrite or misattribute work keeps the safety guarantees without stopping on routine pushes.

## [2026-10-26] Count every unfinished audit repair implementation

Context: SKILL-446 launched 115 repair implementation attempts while AC-001 remained unresolved. Source-changing `progress` reports retained one reservation and bypassed the three-attempt budget.

Decision: Supersede the partial-progress continuation exception. Every unfinished repair implementation, including `progress` and `no_progress`, records one unsuccessful outcome through the existing serial repair owner. Source edits do not exempt an attempt. Remove the continuation mutation and domain transition. Read-only admission of unfinished work consumes that same attempt once. Preserve the existing independent verification, per-criterion budgets and explicit operator reset.

Evidence: Runner tests cover three source-changing unfinished repairs blocking before review, and a successful third repair after two failures. SQLite recorder tests cover direct settlement, read-only admission, replay and state reload without resetting or double-charging the budget.

## [2026-10-27] Repair continuation instructions match the attempt budget

Context: SKILL-446 exhausted three repair attempts after reports that reconciled the plan but left its production items unfinished. The resume prompt still said source-changing progress retained the reservation without spending a failure, contradicting the October 26 accounting change.

Decision: Keep the three-attempt limit. Resumed repair prompts describe the charged attempt and new reservation. Require continued source work while authorized items remain, and reserve a partial progress settlement for an actual execution limit. A formatter that cannot be scoped safely is deferred without stopping production edits.

Evidence: The rendered resume-prompt test checks the stopping rule, formatter deferral, charged attempt and absence of the obsolete budget exemption. Existing serial repair tests retain the accounting proof.

## [2026-10-27] Refuse voluntary unfinished audit repair settlement

Context: SKILL-446 spent all three renewed attempts on MCP completion calls with `progress`. Each report listed remaining authorized production work despite the stopping instructions. Those calls durably settled the attempt before the worker had to continue.

Decision: The MCP completion path calls the service's agent admission method, which returns `AUDIT_REPAIR_UNFINISHED_CONTINUE_IMPLEMENTING` for new `audit_implement_fix` completion requests with `progress` or `no_progress`, without writing a settlement. The worker continues the same attempt and can complete it after finishing the plan. This supersedes the earlier instruction to voluntarily settle partial work at an execution limit. Process interruption remains runtime-owned. Trusted runtime settlement and historical reads, independent audit verification and the existing failure accounting remain unchanged. Other phases retain their completion verdicts.

Evidence: The settlement service test refuses both partial verdicts, verifies no envelope was saved, then completes the same attempt. The existing serial repair tests retain the three-failure accounting proof.

## [2026-10-29] The runtime owns and deletes each agent run's temporary files

Context: Validation told agents to copy the checkout to "a temporary directory outside the workflow checkout" for test probes and then delete the copy. Agents chose their own locations, such as `~/.cache/<key>-validate-*` and `<state>/projects/*/validation`, and any timed-out, killed or forgetful session left a full repository copy behind. One machine accumulated 22 copies and filled its disk, which then stopped QA from booting an emulator.

Decision: The JVM agent process runner creates a fresh directory under `<state dir>/scratch/` for every agent run, points `TMPDIR`, `TMP` and `TEMP` at it, and deletes it when the run ends, whatever the outcome. Each directory records its owner pid. A later run sweeps directories whose owner process is gone, which covers a killed runtime. The validation prompt directs every temporary copy, file or directory to `$TMPDIR` and forbids `~/.cache`, the state directory and the checkout's parent. The root sits under the state directory rather than the system temp directory because `/tmp` is often a size-limited tmpfs and a checkout copy can reach many gigabytes.

Reason: Cleanup that depends on the agent remembering fails exactly when runs go wrong. Owning the directory makes deletion a runtime guarantee. Launcher tests prove a failing run leaves nothing behind and that the sweep keeps a live owner's directory.

## [2026-10-29] Audit counts only material acceptance gaps

Context: SKILL-447 subtask 20 kept AC-004 ("edge states never lose user data") open through repeated focused audits. Each round confirmed the previous fix and named a new case: a hand-written numeric `install_id`, an explicit null rewritten to the default it already meant, a duplicate JSON key the reader already ignored, `null` versus an empty list in a rolled-back field, and a conflict recovery that needs another writer to delete the open row's override at the same moment. Most changed nothing a user could observe. The defect-class fan-out rule told audit to report every sibling path, and nothing asked whether a path was reachable or mattered, so the absolute wording never converged.

Decision: Audit, interaction-trace rechecks and `audit_plan_fix` count only material gaps: a realistic action reaches the gap in normal use and it causes a visible failure, wrong behavior or loss of relied-on data. Absolute criterion wording is read as its practical intent. Contrived input, behavior-neutral representation changes, precisely timed concurrent-writer races and failures that end in a clear error without data loss are not gaps. They do not appear in the Open criteria line or keep a criterion open, and repair planning names them as left out without adding code. Defect-class fan-out still applies to material siblings.

Reason: Repair attempts are a fixed budget and each unreachable case costs a full plan, implement and audit round plus code to maintain. The fix is prompt wording, consistent with the defect-class decision; no semantic revision bump is needed.

