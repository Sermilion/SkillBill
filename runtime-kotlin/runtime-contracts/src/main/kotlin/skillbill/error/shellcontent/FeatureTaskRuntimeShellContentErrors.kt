package skillbill.error.shellcontent

import skillbill.error.core.ShellContentContractException
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeHandoffProjectionContext

fun invalidFeatureTaskRuntimePhaseOutputSchema(
  sourceLabel: String,
  reason: String,
  code: FeatureTaskRuntimePhaseOutputFailureCode = FeatureTaskRuntimePhaseOutputFailureCode.SCHEMA_INVALID,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    code,
    "Feature-task-runtime phase output '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation: $reason",
    cause,
  )

fun invalidFeatureTaskRuntimeHandoffProjection(
  context: InvalidFeatureTaskRuntimeHandoffProjectionContext,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    context.failureKind,
    "Feature-task-runtime handoff projection '${context.projectionName.ifBlank { "<unknown>" }}' " +
      "(contract ${context.projectionContractId.ifBlank { "<unknown>" }}@" +
      "${context.projectionContractVersion.ifBlank { "<unknown>" }}) " +
      "for consumer phase '${context.consumerPhaseId.ifBlank { "<unknown>" }}' " +
      "in workflow '${context.workflowId?.ifBlank { null } ?: "<unknown>"}' " +
      "was rejected [${context.failureKind}]: ${context.reason}",
    cause,
  )

class InvalidFeatureTaskRuntimeRepairReceiptError(
  val fieldPath: String,
  val reason: String,
  val payloadFreeReason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
    "Feature-task-runtime repair receipt fails at '${fieldPath.ifBlank { "<root>" }}': $reason",
    cause,
  )

class InvalidFeatureTaskRuntimeFindingVerificationRecordError(
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
    "Feature-task-runtime finding verification record is invalid: $reason",
    cause,
  )

class InvalidFeatureTaskRuntimeCheckpointIdentitySchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
    "Feature-task-runtime checkpoint identity '${sourceLabel.ifBlank { "<unknown>" }}' fails schema " +
      "validation: $reason",
    cause,
  )

class InvalidFeatureTaskRuntimeCheckpointIdentityVersionError(
  val expectedContractVersion: String,
  val actualContractVersion: String,
  cause: Throwable? = null,
) : InvalidWorkflowStateSchemaError(
    "Feature-task-runtime checkpoint-identity record uses unsupported contract version " +
      "'${actualContractVersion.ifBlank { "<absent>" }}'; this runtime reads " +
      "'$expectedContractVersion'. The store is quarantined and regenerated rather than reinterpreted.",
    cause,
  )

class InvalidFeatureTaskRuntimeQuarantineSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
    "Feature-task-runtime quarantine record '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation: $reason",
    cause,
  )

class InvalidFeatureTaskRuntimeImplementationAttemptSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
    "Feature-task-runtime implementation attempt '${sourceLabel.ifBlank { "<unknown>" }}' fails schema " +
      "validation: $reason",
    cause,
  )

class InvalidFeatureTaskRuntimePhaseHandoffSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
    "Feature-task-runtime phase handoff '$sourceLabel' fails schema validation: $reason",
    cause,
  )

class InvalidFeatureTaskRuntimePersistenceSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
    "Feature-task-runtime persistence record '$sourceLabel' fails schema validation: $reason",
    cause,
  )

class InvalidFeatureTaskRuntimeProjectionMeasurementSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
    "Feature-task-runtime projection measurement '$sourceLabel' fails schema validation: $reason",
    cause,
  )

class InvalidFeatureTaskRuntimeSharedEvidenceProjectionSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
    "Feature-task-runtime shared evidence projection '$sourceLabel' fails schema validation: $reason",
    cause,
  )

class InvalidFeatureTaskRuntimeBuildReceiptSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
  val payloadFreeReason: String? = null,
  val failureCode: String = "schema_invalid",
) : ShellContentContractException(
    "Feature-task-runtime build receipt '$sourceLabel' fails schema validation: $reason",
    cause,
  )

class InvalidFeatureTaskRuntimeValidationEvidenceSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
    "Feature-task-runtime validation evidence '$sourceLabel' fails schema validation: $reason",
    cause,
  )

class InvalidFeatureTaskRuntimeReadinessEvidenceSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
    "Feature-task-runtime readiness evidence '$sourceLabel' fails schema validation: $reason",
    cause,
  )

fun featureTaskRuntimePhaseOrderViolationMessage(
  phaseId: String,
  requiredPhaseId: String,
  requiredVerdict: String,
  observedVerdict: String?,
): String =
    "Feature-task-runtime phase '$phaseId' is unreachable until '$requiredPhaseId' settles with the verdict " +
      "'$requiredVerdict', but it settled with " +
      "'${observedVerdict ?: "<no completed verdict>"}'; the run fails loudly rather than silently advancing."

class FeatureTaskRuntimeOperatorDecisionRejectedError(
  val workflowId: String,
  val decision: String,
  val reason: String,
) : ShellContentContractException(
    "Operator decision '$decision' was rejected for workflow '$workflowId': $reason",
  )

class InvalidFeatureTaskExecutionIdentitySchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
    "Feature-task execution identity '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation: $reason",
    cause,
  )

class InvalidFeatureTaskRuntimeWorkerOwnershipSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
    "Feature-task runtime worker ownership '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation: $reason",
    cause,
  )
