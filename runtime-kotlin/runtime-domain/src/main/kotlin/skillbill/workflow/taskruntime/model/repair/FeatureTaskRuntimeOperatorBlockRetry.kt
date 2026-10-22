package skillbill.workflow.taskruntime.model.repair

const val FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_REASON_MAX_LENGTH: Int = 1000
internal const val FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_REASON_KEY: String = "reason"
internal const val FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_RETRIED_AT_KEY: String = "retried_at"
internal const val FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_PREVIOUS_BLOCKED_REASON_KEY: String =
  "previous_blocked_reason"
internal const val FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_REOPENED_PHASE_IDS_KEY: String =
  "reopened_phase_ids"
internal const val FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_RECOVERY_ATTEMPT_ID_KEY: String = "recovery_attempt_id"
internal const val FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_PRESERVE_BUDGET_KEY: String = "preserve_budget"

data class FeatureTaskRuntimeOperatorBlockRetry(
  val phaseId: String,
  val reason: String,
  val retriedAt: String,
  val recoveryAttemptId: String? = null,
  val preserveBudget: Boolean = false,
) {
  init {
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimeOperatorBlockRetry.phaseId must be non-blank." }
    require(reason.length in 1..FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_REASON_MAX_LENGTH && reason.isNotBlank()) {
      "FeatureTaskRuntimeOperatorBlockRetry.reason must contain " +
        "1..$FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_REASON_MAX_LENGTH characters."
    }
    require(retriedAt.isNotBlank()) { "FeatureTaskRuntimeOperatorBlockRetry.retriedAt must be non-blank." }
    require((recoveryAttemptId != null) == preserveBudget) {
      "Incident recovery retries require an attempt id and budget preservation together."
    }
    require(recoveryAttemptId == null || recoveryAttemptId.isNotBlank()) {
      "Incident recovery retry attempt id must be non-blank."
    }
  }
}
