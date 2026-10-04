package skillbill.ports.featuretask.model

import skillbill.contracts.workflow.identity.task.FEATURE_TASK_RUNTIME_WORKER_OWNERSHIP_CONTRACT_VERSION
import skillbill.error.shellcontent.invalidFeatureTaskRuntimeWorkerOwnershipSchema
import java.time.Instant
import java.time.format.DateTimeParseException

data class FeatureTaskRuntimeWorkerOwnership(
  val workflowId: String,
  val generation: Long,
  val ownerToken: String,
  val hostIdentity: String,
  val bootIdentity: String,
  val pid: Long,
  val processBirthToken: String,
  val leaseState: FeatureTaskRuntimeWorkerLeaseState,
  val heartbeatAt: String,
  val expiresAt: String,
  val phaseId: String,
  val phaseAttempt: Int,
  val contractVersion: String = FEATURE_TASK_RUNTIME_WORKER_OWNERSHIP_CONTRACT_VERSION,
) {
  val heartbeatAtInstant: Instant = parseLeaseInstant(workflowId, "heartbeat_at", heartbeatAt)
  val expiresAtInstant: Instant = parseLeaseInstant(workflowId, "expires_at", expiresAt)

  companion object {
    internal fun parseLeaseInstant(
      workflowId: String,
      field: String,
      value: String,
    ): Instant =
      try {
        Instant.parse(value)
      } catch (_: DateTimeParseException) {
        throw invalidFeatureTaskRuntimeWorkerOwnershipSchema(
          workflowId,
          "$field must be an RFC 3339 instant",
        )
      }
  }
}

fun parseFeatureTaskRuntimeWorkerLeaseInstant(
  workflowId: String,
  field: String,
  value: String,
): Instant = FeatureTaskRuntimeWorkerOwnership.parseLeaseInstant(workflowId, field, value)

enum class FeatureTaskRuntimeWorkerLeaseState(val wireValue: String) {
  ACTIVE("active"),
  TAKEOVER_RESERVED("takeover_reserved"),
  ;

  companion object {
    fun fromWire(value: String?): FeatureTaskRuntimeWorkerLeaseState? =
      value?.trim()?.let { candidate -> entries.firstOrNull { it.wireValue == candidate } }
  }
}

data class FeatureTaskRuntimeCrashReconciliationCandidate(
  val ownership: FeatureTaskRuntimeWorkerOwnership,
  val currentStepId: String,
  val workflowStatus: String,
)
