package skillbill.error.shellcontent

import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.ShellContentContractException
import skillbill.error.core.SkillBillRuntimeException

enum class InstallFailureCode : RuntimeFailureCode {
  INVALID_INSTALL_PLAN_SCHEMA,
  INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA,
  INVALID_TELEMETRY_EVENT_SCHEMA,
  INVALID_GOAL_OBSERVABILITY_EVENT_SCHEMA,
  INVALID_GOAL_PROGRESS_EVENT_SCHEMA,
  INVALID_IDE_STATUS_SCHEMA,
  INVALID_GOAL_SUBTASK_REVIEW_STATE_SCHEMA,
  MISSING_INSTALL_SELECTION_RECORD,
  UNREADABLE_INSTALL_SELECTION_RECORD,
  MALFORMED_INSTALL_SELECTION_RECORD,
  UNREADABLE_BASELINE_MANIFEST,
  RECONCILIATION_CONFLICT,
  MALFORMED_REPO_LOCAL_CONFIG,
  REPO_LOCAL_CONFIG_FAILURE,
  CONTRACT_VERSION_MISMATCH,
}

fun invalidInstallPlanSchemaError(
  fieldPath: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(
  InstallFailureCode.INVALID_INSTALL_PLAN_SCHEMA,
  "Install plan fails schema validation at '${fieldPath.ifBlank { "<root>" }}': $reason",
  cause,
)

fun invalidNativeAgentCompositionSchemaError(
  sourceLabel: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(
  InstallFailureCode.INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA,
  "Native agent composition source '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation: $reason",
  cause,
)

fun invalidTelemetryEventSchemaError(
  fieldPath: String,
  eventName: String?,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(
  InstallFailureCode.INVALID_TELEMETRY_EVENT_SCHEMA,
  "Telemetry event '${eventName ?: "<unknown>"}' fails schema validation at " +
    "'${fieldPath.ifBlank { "<root>" }}': $reason",
  cause,
)

fun invalidGoalObservabilityEventSchemaError(
  sourceLabel: String,
  fieldPath: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(
  InstallFailureCode.INVALID_GOAL_OBSERVABILITY_EVENT_SCHEMA,
  "Goal observability event '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation at " +
    "'${fieldPath.ifBlank { "<root>" }}': $reason",
  cause,
)

fun invalidGoalProgressEventSchemaError(
  sourceLabel: String,
  fieldPath: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(
  InstallFailureCode.INVALID_GOAL_PROGRESS_EVENT_SCHEMA,
  "Goal progress event '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation at " +
    "'${fieldPath.ifBlank { "<root>" }}': $reason",
  cause,
)

fun invalidIdeStatusSchemaError(
  sourceLabel: String,
  fieldPath: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(
  InstallFailureCode.INVALID_IDE_STATUS_SCHEMA,
  "IDE status '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation at " +
    "'${fieldPath.ifBlank { "<root>" }}': $reason",
  cause,
)

fun invalidGoalSubtaskReviewStateSchemaError(
  sourceLabel: String,
  fieldPath: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(
  InstallFailureCode.INVALID_GOAL_SUBTASK_REVIEW_STATE_SCHEMA,
  "Goal subtask review state '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation at " +
    "'${fieldPath.ifBlank { "<root>" }}': $reason",
  cause,
)

fun missingInstallSelectionRecordError(path: String, cause: Throwable? = null): SkillBillRuntimeException =
  SkillBillRuntimeException(
    InstallFailureCode.MISSING_INSTALL_SELECTION_RECORD,
    "Install selection record is missing at '${path.ifBlank { "<unknown>" }}'.",
    cause,
  )

fun unreadableInstallSelectionRecordError(path: String, cause: Throwable? = null): SkillBillRuntimeException =
  SkillBillRuntimeException(
    InstallFailureCode.UNREADABLE_INSTALL_SELECTION_RECORD,
    "Install selection record at '${path.ifBlank { "<unknown>" }}' cannot be read.",
    cause,
  )

fun malformedInstallSelectionRecordError(
  path: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(
  InstallFailureCode.MALFORMED_INSTALL_SELECTION_RECORD,
  "Install selection record at '${path.ifBlank { "<unknown>" }}' is malformed: $reason",
  cause,
)

fun unreadableBaselineManifestError(
  path: String,
  reason: String? = null,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(
  InstallFailureCode.UNREADABLE_BASELINE_MANIFEST,
  "Baseline manifest at '${path.ifBlank { "<unknown>" }}' cannot be read" +
    (reason?.let { ": $it." } ?: "."),
  cause,
)

fun reconciliationConflictError(
  skillRelativePath: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(
  InstallFailureCode.RECONCILIATION_CONFLICT,
  "Reconciliation failed for skill '${skillRelativePath.ifBlank { "<unknown>" }}': $reason",
  cause,
)

fun unreadableRepoLocalConfigError(path: String, cause: Throwable? = null): SkillBillRuntimeException =
  SkillBillRuntimeException(
    InstallFailureCode.REPO_LOCAL_CONFIG_FAILURE,
    "Repo-local config at '${path.ifBlank { "<unknown>" }}' cannot be read.",
    cause,
  )

fun malformedRepoLocalConfigError(
  path: String,
  key: String,
  value: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(
  InstallFailureCode.MALFORMED_REPO_LOCAL_CONFIG,
  "Repo-local config at '${path.ifBlank { "<unknown>" }}' is malformed: " +
    "key '${key.ifBlank { "<root>" }}' value '$value' $reason",
  cause,
)

fun malformedMachineConfigError(
  path: String,
  key: String,
  value: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException = SkillBillRuntimeException(
  InstallFailureCode.REPO_LOCAL_CONFIG_FAILURE,
  "Machine config at '${path.ifBlank { "<unknown>" }}' is malformed: " +
    "key '${key.ifBlank { "<root>" }}' value '$value' $reason",
  cause,
)

class InvalidGoalPlanningPreparationSchemaError(
  val sourceLabel: String,
  val fieldPath: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
    "Goal planning preparation '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation at " +
      "'${fieldPath.ifBlank { "<root>" }}': $reason",
    cause,
  )

class IncompatibleGoalPlanningPreparationRecoveryError(
  val workflowId: String,
  val subtaskId: Int,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
    "Goal planning preparation '$workflowId' subtask $subtaskId cannot be recovered: $reason",
    cause,
  )
