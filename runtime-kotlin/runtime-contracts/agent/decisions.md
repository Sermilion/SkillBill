## [2026-10-03] Keep input-driven install, schema and configuration failures coded
Context: SKILL-399 subtask 3 removes sixteen Install exception classes while preserving their messages, causes and handling boundaries.
Decision: Use InstallFailureCode with SkillBillRuntimeException for all sixteen conditions. Shared message factories retain producer context without typed exception properties.
Reason: Configuration, persisted records, schema input and selected install state can trigger these failures. The plan reserves defect assertions for conditions that external input cannot trigger, so this slice retains coded failures.
Alternatives considered: Replace failures with require, check or error. These input-driven conditions do not justify defect classification.

## [2026-10-03] Preserve scaffold and review classification boundaries
Context: SKILL-399 subtask 2 replaces Scaffold and ReviewContext throwable hierarchies with the same shared exception type.
Decision: Include ReviewContextFailureCode in isShellContentContractFailure and exclude ScaffoldFailureCode. Converted review catches retain exact handled codes and rethrow other failures.
Reason: ReviewContext failures previously extended ShellContentContractException. Scaffold failures extended SkillBillRuntimeException directly, so including them would widen shell-content handling and change propagation.

## [2026-10-03] Keep input-driven scaffold and review failures coded
Context: SKILL-399 subtask 2 removes twenty exception classes while preserving scaffold output and review failure behavior.
Decision: Use ScaffoldFailureCode and ReviewContextFailureCode with SkillBillRuntimeException. Message functions retain context and causes without adding typed exception properties.
Reason: Payloads, authored skill identity, review evidence and spec input can trigger these failures. The plan permits defect assertions only when external input cannot trigger a condition; this slice establishes no such condition.
Alternatives considered: Replace failures with require, check or error. Input-driven conditions do not justify defect classification.

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
