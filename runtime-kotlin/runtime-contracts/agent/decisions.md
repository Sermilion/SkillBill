## [2026-10-03] Keep input-driven manifest and staging failures coded
Context: SKILL-399 subtask 1 removes twenty exception classes while preserving messages and failure classification across manifest loading, review composition and skill staging.
Decision: Use ManifestFailureCode and SkillStagingFailureCode with SkillBillRuntimeException. Preserve context and causes in message functions without adding exception properties.
Reason: Manifests, selected composition and authored staging input can trigger these failures. The plan found no evidence that any condition is exclusively a code defect, so require, check or error would misclassify input failures.
Alternatives considered: Replace failures with defect checks to reduce enum entries. The input-driven conditions do not justify that choice.

## [2026-10-03] Preserve handled failure sets during code conversion
Context: Replacing typed exception catches with SkillBillRuntimeException catches can absorb failures that previously propagated.
Decision: Keep exact manifest-schema and skill-shape code checks at their handling boundaries and retain shell-content classification guards at shared catches. Rethrow failures outside each handled set.
Reason: A shared exception type alone cannot distinguish these handled failures from database or other runtime failures. The conversion must preserve propagation, including cancellation and interruption.
Revisit when: The last legacy subclass and codeless caller are gone. Remove transition support then, while retaining guarded coded catches.
