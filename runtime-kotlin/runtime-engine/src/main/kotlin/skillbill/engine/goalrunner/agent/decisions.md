# goalrunner boundary decisions

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
