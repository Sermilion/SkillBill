## [2026-10-05] Preserve scaffold capture with an exact input code
Context: SKILL-401 subtask 7 converts scaffold input assertions formerly handled as IllegalArgumentException to coded failures. Older scaffold codes already reached MCP as captured runtime failures.
Decision: Add ScaffoldFailureCode.INVALID_INPUT and include only that entry in isShellContentContractFailure. Keep other scaffold codes excluded and retain ReviewContextFailureCode classification.
Reason: MCP previously skipped capture for these input assertions. Reusing INVALID_PAYLOAD or classifying the whole scaffold family would change which failures produce telemetry rows. The existing shared predicate preserves capture without a dispatcher change.
Alternatives considered: Reuse INVALID_PAYLOAD or classify all ScaffoldFailureCode entries. Both erase the distinction between newly converted input rejections and older coded failures.

## [2026-10-04] Keep decomposition failure codes with their vocabulary owners
Context: SKILL-399 subtask 8 removes manifest and bundle-journal exception classes whose failureCode properties mixed domain wire codes with workflow-only conditions.
Decision: Reuse DecompositionManifestValidationFailureCode entries for existing domain vocabulary. WorkflowFailureCode owns five other manifest conditions and one entry per distinct journal literal.
Reason: Parallel codes would duplicate the domain vocabulary, and runtime-contracts cannot depend on the domain enum. Missing-manifest and incomplete-bundle conditions need their own workflow entries because neither has a domain wire code.
Alternatives considered: Pass wire strings or add parallel workflow entries for domain codes. The settled plan passes enum entries directly and keeps each vocabulary with its owner.

## [2026-10-04] Preserve workflow-state family after class removal
Context: SKILL-399 subtask 7 removes InvalidWorkflowStateSchemaError after subtask 6 converted its checkpoint-version subclass to a distinct code.
Decision: Classify both WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA and FeatureTaskRuntimeFailureCode.INVALID_CHECKPOINT_IDENTITY_VERSION through isInvalidWorkflowStateFailure. Register WorkflowFailureCode in shell-content classification.
Reason: The former inheritance relationship routed both failures through workflow-state recovery. Both codes must retain that handling, including checkpoint remediation refusal, while unrelated failures propagate.

## [2026-10-04] Retain code-checked catches around shared workflow decoders
Context: Workflow open, input update, persistence, verification and parent discovery use shared decoders that throw workflow-state failures.
Decision: Keep code-checked catches and their existing fallback values and diagnostic records. Keep the workflow update catch outside the database transaction.
Reason: The transaction needs the throw to roll back. Changing the shared decoders to return values would expand this conversion into a decoder refactor, which the subtask excludes.
Alternatives considered: Return null, emptyMap or an Error result at decode boundaries. The settled plan retains catches because these decoders have many throwing sites and shared callers.

## [2026-10-04] Keep input-driven workflow and record failures coded
Context: SKILL-399 subtask 7 replaces eight workflow exception classes while preserving their messages and handling boundaries.
Decision: Use WorkflowFailureCode with SkillBillRuntimeException for all eight conditions. Keep one message function per former class, including message-only failures.
Reason: Stored records, rows, issue-key input, retired prose state, agent output and goal limits can trigger these failures. Defect assertions would misclassify them; message functions follow the landed conversion pattern and keep the many state-error constructions short.
Alternatives considered: Replace failures with require, check or error. The plan reserves those mechanisms for conditions that only a code defect can trigger.

## [2026-10-04] Preserve checkpoint-version workflow-state handling
Context: SKILL-399 subtask 6 removes a checkpoint-version exception that inherited InvalidWorkflowStateSchemaError, while Workflow-class conversion belongs to subtask 7.
Decision: Keep a distinct checkpoint-version code and classify it through isInvalidWorkflowStateFailure alongside the legacy workflow-state type. Retarget former catches to the predicate and rethrow unrelated failures.
Reason: Inheritance previously routed unsupported checkpoint versions through workflow-state recovery. Remediation must still refuse unsupported semantics instead of treating the checkpoint as absent, and transaction failures must still roll back.
Revisit when: Subtask 7 converts the workflow-state class. Extend the predicate with its state code while retaining checkpoint-version membership.
Superseded by: Preserve workflow-state family after class removal (2026-10-04)

## [2026-10-04] Pass failure context and violation reasons explicitly
Context: SKILL-399 subtask 6 removes reason, fieldPath and payloadFreeReason properties used by five receipt, review-state, gate-integrity, persistence and shared-evidence re-wrap sites.
Decision: Pass anchors and failure factories into decoders, and return nullable violation reasons where consumers choose contextual rejection or recorded degradation. Keep violation abstract on the wire-validator port.
Reason: Explicit values preserve receipt indexes, cause chains, guidance and degradation reasons without recovering context from caught exception properties or parsing message text. Each consumer can apply its existing boundary policy.
Alternatives considered: Retain typed exception properties or catch the shared exception to reconstruct context. The plan removes these reads and keeps ports free of default implementations.

## [2026-10-03] Keep input-driven install, schema and configuration failures coded
Context: SKILL-399 subtask 3 removes sixteen Install exception classes while preserving their messages, causes and handling boundaries.
Decision: Use InstallFailureCode with SkillBillRuntimeException for all sixteen conditions. Shared message factories retain producer context without typed exception properties.
Reason: Configuration, persisted records, schema input and selected install state can trigger these failures. The plan reserves defect assertions for conditions that external input cannot trigger, so this slice retains coded failures.
Alternatives considered: Replace failures with require, check or error. These input-driven conditions do not justify defect classification.

## [2026-10-03] Preserve scaffold and review classification boundaries
Context: SKILL-399 subtask 2 replaces Scaffold and ReviewContext throwable hierarchies with the same shared exception type.
Decision: Include ReviewContextFailureCode in isShellContentContractFailure and exclude ScaffoldFailureCode. Converted review catches retain exact handled codes and rethrow other failures.
Reason: ReviewContext failures previously extended ShellContentContractException. Scaffold failures extended SkillBillRuntimeException directly, so including them would widen shell-content handling and change propagation.
Superseded by: Preserve scaffold capture with an exact input code (2026-10-05)

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
