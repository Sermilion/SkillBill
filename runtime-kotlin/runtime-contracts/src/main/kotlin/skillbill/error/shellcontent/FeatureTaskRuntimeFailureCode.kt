package skillbill.error.shellcontent

import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeHandoffProjectionContext

enum class FeatureTaskRuntimeFailureCode : RuntimeFailureCode {
  INVALID_REPAIR_RECEIPT,
  INVALID_FINDING_VERIFICATION_RECORD,
  INVALID_CHECKPOINT_IDENTITY_SCHEMA,
  INVALID_CHECKPOINT_IDENTITY_VERSION,
  INVALID_QUARANTINE_SCHEMA,
  INVALID_IMPLEMENTATION_ATTEMPT_SCHEMA,
  INVALID_PHASE_HANDOFF_SCHEMA,
  INVALID_PERSISTENCE_SCHEMA,
  INVALID_PROJECTION_MEASUREMENT_SCHEMA,
  INVALID_SHARED_EVIDENCE_PROJECTION_SCHEMA,
  INVALID_BUILD_RECEIPT_SCHEMA,
  INVALID_VALIDATION_EVIDENCE_SCHEMA,
  INVALID_READINESS_EVIDENCE_SCHEMA,
  INVALID_EXECUTION_IDENTITY_SCHEMA,
  INVALID_WORKER_OWNERSHIP_SCHEMA,
  FEATURE_TASK_RUNTIME_CONTRACT_REJECTED,
  INVALID_EXECUTION_PLAN_SCHEMA,
  EXECUTION_PLAN_CONFLICT,
  SHARED_EVIDENCE_FINGERPRINT_CONTRADICTION,
}

fun invalidFeatureTaskRuntimeRepairReceipt(
  fieldPath: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.INVALID_REPAIR_RECEIPT,
    "Feature-task-runtime repair receipt fails at '${fieldPath.ifBlank { "<root>" }}': $reason",
    cause,
  )

fun invalidFeatureTaskRuntimeFindingVerificationRecord(
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.INVALID_FINDING_VERIFICATION_RECORD,
    "Feature-task-runtime finding verification record is invalid: $reason",
    cause,
  )

fun invalidFeatureTaskRuntimeCheckpointIdentitySchema(
  sourceLabel: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.INVALID_CHECKPOINT_IDENTITY_SCHEMA,
    "Feature-task-runtime checkpoint identity '${sourceLabel.ifBlank { "<unknown>" }}' fails schema " +
      "validation: $reason",
    cause,
  )

fun invalidFeatureTaskRuntimeCheckpointIdentityVersion(
  expectedContractVersion: String,
  actualContractVersion: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.INVALID_CHECKPOINT_IDENTITY_VERSION,
    "Feature-task-runtime checkpoint-identity record uses unsupported contract version " +
      "'${actualContractVersion.ifBlank { "<absent>" }}'; this runtime reads " +
      "'$expectedContractVersion'. The store is quarantined and regenerated rather than reinterpreted.",
    cause,
  )

fun invalidFeatureTaskRuntimeQuarantineSchema(
  sourceLabel: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.INVALID_QUARANTINE_SCHEMA,
    "Feature-task-runtime quarantine record '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation: $reason",
    cause,
  )

fun invalidFeatureTaskRuntimeImplementationAttemptSchema(
  sourceLabel: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.INVALID_IMPLEMENTATION_ATTEMPT_SCHEMA,
    "Feature-task-runtime implementation attempt '${sourceLabel.ifBlank { "<unknown>" }}' fails schema " +
      "validation: $reason",
    cause,
  )

fun invalidFeatureTaskRuntimePhaseHandoffSchema(
  sourceLabel: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.INVALID_PHASE_HANDOFF_SCHEMA,
    "Feature-task-runtime phase handoff '$sourceLabel' fails schema validation: $reason",
    cause,
  )

fun invalidFeatureTaskRuntimePersistenceSchema(
  sourceLabel: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.INVALID_PERSISTENCE_SCHEMA,
    "Feature-task-runtime persistence record '$sourceLabel' fails schema validation: $reason",
    cause,
  )

fun invalidFeatureTaskRuntimeProjectionMeasurementSchema(
  sourceLabel: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.INVALID_PROJECTION_MEASUREMENT_SCHEMA,
    "Feature-task-runtime projection measurement '$sourceLabel' fails schema validation: $reason",
    cause,
  )

fun invalidFeatureTaskRuntimeSharedEvidenceProjectionSchema(
  sourceLabel: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.INVALID_SHARED_EVIDENCE_PROJECTION_SCHEMA,
    "Feature-task-runtime shared evidence projection '$sourceLabel' fails schema validation: $reason",
    cause,
  )

fun invalidFeatureTaskRuntimeBuildReceiptSchema(
  sourceLabel: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.INVALID_BUILD_RECEIPT_SCHEMA,
    "Feature-task-runtime build receipt '$sourceLabel' fails schema validation: $reason",
    cause,
  )

fun invalidFeatureTaskRuntimeValidationEvidenceSchema(
  sourceLabel: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.INVALID_VALIDATION_EVIDENCE_SCHEMA,
    "Feature-task-runtime validation evidence '$sourceLabel' fails schema validation: $reason",
    cause,
  )

fun invalidFeatureTaskRuntimeReadinessEvidenceSchema(
  sourceLabel: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.INVALID_READINESS_EVIDENCE_SCHEMA,
    "Feature-task-runtime readiness evidence '$sourceLabel' fails schema validation: $reason",
    cause,
  )

fun featureTaskRuntimeOperatorDecisionRejected(
  workflowId: String,
  decision: String,
  reason: String,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.FEATURE_TASK_RUNTIME_CONTRACT_REJECTED,
    "Operator decision '$decision' was rejected for workflow '$workflowId': $reason",
  )

fun invalidFeatureTaskExecutionIdentitySchema(
  sourceLabel: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.INVALID_EXECUTION_IDENTITY_SCHEMA,
    "Feature-task execution identity '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation: $reason",
    cause,
  )

fun invalidFeatureTaskRuntimeWorkerOwnershipSchema(
  sourceLabel: String,
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.INVALID_WORKER_OWNERSHIP_SCHEMA,
    "Feature-task runtime worker ownership '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation: $reason",
    cause,
  )

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

fun featureTaskRuntimePhaseOrderViolationMessage(
  phaseId: String,
  requiredPhaseId: String,
  requiredVerdict: String,
  observedVerdict: String?,
): String =
  "Feature-task-runtime phase '$phaseId' is unreachable until '$requiredPhaseId' settles with the verdict " +
    "'$requiredVerdict', but it settled with " +
    "'${observedVerdict ?: "<no completed verdict>"}'; the run fails loudly rather than silently advancing."

fun invalidFeatureTaskRuntimeExecutionPlanSchema(reason: String, cause: Throwable? = null): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.INVALID_EXECUTION_PLAN_SCHEMA,
    "Invalid feature-task runtime execution plan: $reason",
    cause,
  )

fun featureTaskRuntimeExecutionPlanConflict(): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.EXECUTION_PLAN_CONFLICT,
    "The immutable execution plan cannot be replaced, removed, or adopted after workflow creation.",
  )

fun featureTaskRuntimeSharedEvidenceFingerprintContradiction(
  addressedFingerprint: String,
  recordedFingerprint: String,
  sourceLabel: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.SHARED_EVIDENCE_FINGERPRINT_CONTRADICTION,
    "Shared review evidence at '$sourceLabel' is addressed by fingerprint '$addressedFingerprint' but " +
      "records fingerprint '$recordedFingerprint'; refusing to serve evidence for a contradicted checkpoint.",
    cause,
  )
