# SKILL-428 subtask 3: Count CI-confirmed failed repairs

## Scope

Correct the existing monitor repair budget and explain the actual repair history in prose. A repair-loop entry is not a failed CI repair. Merge conflicts are resolved without consuming a CI repair attempt.

## Acceptance Criteria

1. S3-1. The budget advances only when the agent made a source or merge change, reported repair completion, the change was committed and pushed, and CI actually failed on that published repair commit. Initial failing CI is the trigger for a repair, not a failed repair attempt.
2. S3-2. One published repair commit spends at most one attempt. Several failing jobs, repeated polls, re-observation after resume and a different failure message on that same commit do not spend more. A genuine CI failure counts regardless of whether its cause is source, tests or infrastructure. A different failure cause on a later published repair does not reset the accumulated budget.
3. S3-3. Merge conflicts, including conflicts caused by main advancing while repair CI is pending, are resolved without spending or exhausting the CI budget. They are not recorded as failed CI on an unjudged commit. If the resulting merge repair is published and genuinely fails CI, that failure counts once under S3-1.
4. S3-4. Local setup retries, incomplete or cancelled repairs, no-change claims, failed publication, absent/queued/running checks and unassociated or stale check results do not spend a failed CI attempt. They retain their existing lifecycle, failure and recovery behavior. Do not add new block reasons or convert unknown data into success.
5. S3-5. A third completed and published repair is observed until its actual CI result. The existing cap stops another CI repair only after three qualifying repair commits have failed CI. Merge-only work does not become a fourth CI repair or reset that counter. Existing cancellation, timeout and resource limits still apply.
6. S3-6. Caller and terminal reports distinguish launched work, pushed changes, pending CI and CI-confirmed failures in prose. A cap report identifies the failed repair commits and useful check links from existing evidence. It does not append an unrelated merge conflict as if that proved the latest CI repair failed.
7. S3-7. Standalone monitor, goal monitor and phase-pr use the same corrected meaning. Existing targeting, worktree ownership, commit/push authority, terminal PR behavior and required local/CI checks remain intact. No new schema, schema validation, output protocol, receipt, migration, mandatory record field or block reason is introduced.

## Implementation guidance

The current monitor cycle maps both PullRequestCiOutcome.Failed and Conflicted into CI_FAILED. The monitor_fix backward edge spends its budget when entering repair. That mechanism counts launches and merge work, while the requested budget counts completed published changes that CI rejected.

Change the existing monitor/loop accounting owner so it distinguishes these facts using current observations, commit results and existing run records. Preserve one owner for state transitions and keep adapters responsible for Git/GitHub effects. Do not infer a CI failure from a conflict, agent prose, lack of a heartbeat, a pending job or the historical failure that prompted the first repair.

Use the existing failed-check observation to settle the published repair once; a first observed real failure is enough even if other checks are pending. Associate it with the repair commit that CI evaluated. A passed repair ends monitoring under current policy. A pending repair stays pending. Do not substitute branch names or check names for commit association.

Keep historical and resumed records truthful. Never fabricate failure evidence or reset known consumed attempts to zero to get past a cap. Use existing records and recovery semantics rather than adding a new durable format or refusal category. Review the current state reconstruction and route tests alongside the live path.

Do not solve this by raising the limit, restarting it for every error, disabling the cap, resetting on every merge or adding an unbounded repair loop. The limit remains three CI-confirmed failed repair cycles. Conflict resolution retains existing ownership, cancellation and timeout behavior.

Update existing authored monitor guidance to describe this meaning. Prefer shared briefings and the existing accounting owner over changes to protected strategy sources. The new meaning deliberately supersedes the prior instruction to preserve attempt consumption, while retaining SKILL-427's isolation and publication boundaries.

## Verification

Add few behavioral regressions at existing monitor/loop boundaries.

- Reproduce the real incident: publish a third repair, leave its CI running, advance main to create a conflict, resolve that conflict and continue observation. The pending repair is not reported as a third failed CI repair, and conflict resolution consumes no attempt.
- Run three changed, completed, published repair commits through genuine failed CI. Include repeated polls and several failed jobs on one commit, plus a different cause on the next. Count each commit once and stop the next CI repair only after the third qualifying failure.
- Exercise unchanged publication ownership and resume/cancellation through existing route fixtures where the accounting changes touch them. An initial failure, an incomplete local repair and a failed push do not count as CI-confirmed repair failures.

Assert observable accounting, publication eligibility and caller explanations. Do not snapshot prose, add schema/validator tests, mutate live PRs or create a general receipt framework. Preserve required validation, including its existing local failure paths.

## Architecture and validation

Apply the parent architecture checklist, especially state ownership, typed internal bookkeeping, cancellation, module direction and observable degradation. No new protocol is authorized by these rules. Existing guards and rejection fixtures remain unchanged or tighter.

Only later authorized validation runs the required project checks and these regressions. Source/support changes receive the required installation refresh in its owning phase. This spec correction does not implement or run the feature.

## Next path

This is the last implementation boundary in the bundle. A later authorized skill-bill SKILL-428 run must use this revised prose-first design and accounting definition, not the earlier schema-based saved plan.
