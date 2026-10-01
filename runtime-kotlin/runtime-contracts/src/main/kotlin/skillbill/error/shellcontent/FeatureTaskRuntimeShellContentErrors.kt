package skillbill.error.shellcontent

import skillbill.error.core.ShellContentContractException
import skillbill.error.featuretask.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.featuretask.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.error.featuretask.FeatureTaskRuntimePhaseOutputFailureKind
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeHandoffProjectionContext
import skillbill.error.featuretask.coarseFailureKindForPhaseOutputWireCode

data class FeatureTaskRuntimePhaseOutputStructuralRepairSource(
  val label: String,
  val offset: Int,
  val line: Int,
  val column: Int,
)

data class FeatureTaskRuntimePhaseOutputStructuralRepair(
  val originalDigest: String,
  val repairedDigest: String,
  val format: String,
  val operation: String,
  val source: FeatureTaskRuntimePhaseOutputStructuralRepairSource,
)

class InvalidFeatureTaskRuntimePhaseOutputSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
  val payloadFreeReason: String? = null,
  val failureCode: String = FeatureTaskRuntimePhaseOutputFailureCode.SCHEMA_INVALID.wireValue,
  val structuralRepair: FeatureTaskRuntimePhaseOutputStructuralRepair? = null,
) : ShellContentContractException(
    "Feature-task-runtime phase output '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation: $reason",
    cause,
  ) {
  val failureKind: FeatureTaskRuntimePhaseOutputFailureKind
    get() = coarseFailureKindForPhaseOutputWireCode(failureCode)

  val structuralRepairOriginalDigest: String?
    get() = structuralRepair?.originalDigest

  val structuralRepairRepairedDigest: String?
    get() = structuralRepair?.repairedDigest

  val structuralRepairFormat: String?
    get() = structuralRepair?.format

  val structuralRepairOperation: String?
    get() = structuralRepair?.operation

  val structuralRepairSourceLabel: String?
    get() = structuralRepair?.source?.label

  val structuralRepairSourceOffset: Int?
    get() = structuralRepair?.source?.offset

  val structuralRepairSourceLine: Int?
    get() = structuralRepair?.source?.line

  val structuralRepairSourceColumn: Int?
    get() = structuralRepair?.source?.column

  val acceptedAfterStructuralRepair: Boolean
    get() = structuralRepair != null
}

class InvalidFeatureTaskRuntimeHandoffProjectionError(
  val context: InvalidFeatureTaskRuntimeHandoffProjectionContext,
  cause: Throwable? = null,
) : ShellContentContractException(
    "Feature-task-runtime handoff projection '${context.projectionName.ifBlank { "<unknown>" }}' " +
      "(contract ${context.projectionContractId.ifBlank { "<unknown>" }}@" +
      "${context.projectionContractVersion.ifBlank { "<unknown>" }}) " +
      "for consumer phase '${context.consumerPhaseId.ifBlank { "<unknown>" }}' " +
      "in workflow '${context.workflowId?.ifBlank { null } ?: "<unknown>"}' " +
      "was rejected [${context.failureKind}]: ${context.reason}",
    cause,
  ) {
  val workflowId: String? get() = context.workflowId
  val consumerPhaseId: String get() = context.consumerPhaseId
  val projectionName: String get() = context.projectionName
  val projectionContractId: String get() = context.projectionContractId
  val projectionContractVersion: String get() = context.projectionContractVersion
  val failureKind: FeatureTaskRuntimeHandoffProjectionFailureKind get() = context.failureKind
  val reason: String get() = context.reason
}

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

class FeatureTaskRuntimePhaseOrderViolationError(
  val phaseId: String,
  val requiredPhaseId: String,
  val requiredVerdict: String,
  val observedVerdict: String?,
) : ShellContentContractException(
    "Feature-task-runtime phase '$phaseId' is unreachable until '$requiredPhaseId' settles with the verdict " +
      "'$requiredVerdict', but it settled with " +
      "'${observedVerdict ?: "<no completed verdict>"}'; the run fails loudly rather than silently advancing.",
  )

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
