# goalrunner boundary decisions

## [2026-10-04] Return preparation conflicts as data
Context: SKILL-399 subtask 4 replaces preparation exceptions whose readers used workflow identity, subtask identity and recovery reason to steer execution.
Decision: Put data-only conflict and result declarations in runtime-ports. Readers branch on returned conflicts; engine-owned toFailure converts them where existing callers must stop by throwing.
Reason: A conflicted stored plan is an expected outcome under A7. Returning its details preserves subtask-specific recovery without reading caught-exception properties, while limiting API changes to methods that produce or propagate conflicts.

## [2026-10-04] Detect preparation conflicts before transaction writes
Context: SQLite transactions commit returned values, including Conflicted. Preparation and child-persistence operations can otherwise mutate state before detecting a conflict.
Decision: Run conflict checks and read-only child hydration before parent, child, cascade or checkpoint writes. Keep SQL faults throwing through the transaction and write manifest projections only for saved children.
Reason: Returning a conflict after a write would commit partial preparation state. Checking first leaves durable state unchanged on refusal; throwing on a mid-write SQL fault preserves rollback.

## [2026-10-04] Preserve hard-reset classification during code migration
Context: Recovery previously inspected typed schema-error properties for contract mismatches. JSON-schema constant rejections and phase-output legacy errors still reach this classifier.
Decision: Explicit stored-contract mismatch producers emit GOAL_PLANNING_PREPARATION_CONTRACT_INCOMPATIBLE. Walk the cause chain for that code and retain the existing message classifier for schema, conflict and phase-output failures.
Reason: The code identifies explicit incompatibility without exception-specific properties. Retaining the bounded message fallback preserves existing hard-reset cases while later subtasks replace the remaining phase-output exception class.
