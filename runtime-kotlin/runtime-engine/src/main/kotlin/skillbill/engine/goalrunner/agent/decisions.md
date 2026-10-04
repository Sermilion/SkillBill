# goalrunner boundary decisions

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
