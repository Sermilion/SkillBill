package skillbill.error.shellcontent

import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.ShellContentContractException
import skillbill.error.core.SkillBillRuntimeException

enum class WorkflowFailureCode : RuntimeFailureCode {
  INVALID_WORKFLOW_STATE_SCHEMA,
  PROSE_FEATURE_TASK_WORKFLOW_WRITE_REFUSED,
  INVALID_WORK_LIST_ROW,
  WORKFLOW_ISSUE_KEY_CONFLICT,
  LEGACY_PROSE_WORKFLOW,
  INVALID_REJECTED_OUTPUT_DIAGNOSTIC_SCHEMA,
  INVALID_PRODUCER_OUTPUT_EVIDENCE_SCHEMA,
  GOAL_VERIFICATION_BOUNDARY_CAP_EXCEEDED,
}

fun invalidWorkflowStateSchemaError(message: String, cause: Throwable? = null): SkillBillRuntimeException =
  SkillBillRuntimeException(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, message, cause)

fun proseFeatureTaskWorkflowWriteRefusedError(
  workflowId: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    WorkflowFailureCode.PROSE_FEATURE_TASK_WORKFLOW_WRITE_REFUSED,
    "Feature-task workflow '$workflowId' write refused: mode=prose is retired. The prose engine " +
      "is deleted; re-run this feature on the runtime engine (mode=runtime) instead. Legacy " +
      "prose rows remain readable for history but no new prose writes are accepted.",
    cause,
  )

fun invalidWorkListRowError(message: String, cause: Throwable? = null): SkillBillRuntimeException =
  SkillBillRuntimeException(WorkflowFailureCode.INVALID_WORK_LIST_ROW, message, cause)

fun workflowIssueKeyConflictError(
  workflowId: String,
  persistedIssueKey: String,
  requestedIssueKey: String,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    WorkflowFailureCode.WORKFLOW_ISSUE_KEY_CONFLICT,
    "Workflow '$workflowId' is already associated with issue key '$persistedIssueKey', not '$requestedIssueKey'.",
  )

fun legacyProseWorkflowError(workflowId: String, issueKey: String?): SkillBillRuntimeException =
  SkillBillRuntimeException(
    WorkflowFailureCode.LEGACY_PROSE_WORKFLOW,
    "Workflow '$workflowId' is a legacy prose-mode row; the prose engine is retired and this row " +
      "cannot be resumed, continued, or updated. Re-run this work through the runtime engine instead: " +
      "`skill-bill goal ${issueKey?.trim()?.ifEmpty { null } ?: "<ISSUE_KEY>"}`.",
  )

fun invalidRejectedOutputDiagnosticSchemaError(message: String): SkillBillRuntimeException =
  SkillBillRuntimeException(WorkflowFailureCode.INVALID_REJECTED_OUTPUT_DIAGNOSTIC_SCHEMA, message)

fun invalidProducerOutputEvidenceSchemaError(message: String): SkillBillRuntimeException =
  SkillBillRuntimeException(WorkflowFailureCode.INVALID_PRODUCER_OUTPUT_EVIDENCE_SCHEMA, message)

fun goalVerificationBoundaryCapExceededError(message: String): SkillBillRuntimeException =
  SkillBillRuntimeException(WorkflowFailureCode.GOAL_VERIFICATION_BOUNDARY_CAP_EXCEEDED, message)

class InvalidDecompositionManifestSchemaError(
  val sourceLabel: String,
  val reason: String,
  val failureCode: String? = null,
  cause: Throwable? = null,
) : ShellContentContractException(
    "Decomposition manifest '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation: $reason",
    cause,
  )

class InvalidDecompositionManifestBundleJournalError(
  val sourceLabel: String,
  val reason: String,
  val failureCode: String? = null,
  cause: Throwable? = null,
) : ShellContentContractException(
    "Decomposition manifest bundle journal '${sourceLabel.ifBlank { "<unknown>" }}' is invalid: $reason",
    cause,
  )
