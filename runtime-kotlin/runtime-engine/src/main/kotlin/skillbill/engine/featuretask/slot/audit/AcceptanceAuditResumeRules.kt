package skillbill.engine.featuretask.slot.audit

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.engine.featuretask.slot.state.isRetiredAuditGapLoop
import skillbill.engine.featuretask.slot.state.recordEnvelope
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord

internal object AcceptanceAuditResumeRules : PhaseResumeRules {
  override val resumesPastCompletion: Boolean = true

  override val invalidatesLaterStepsWhileIncomplete: Boolean = true

  override fun resumedRecord(
    record: FeatureTaskRuntimePhaseRecord,
    stripped: FeatureTaskRuntimePhaseRecord,
  ): FeatureTaskRuntimePhaseRecord {
    if (!hasLegacyAuditGapLineage(record)) return stripped
    return when (stripped.status.workflowStepStatus()) {
      WorkflowStepStatus.COMPLETED ->
        if (hasLegacyRemovedAuditVerdict(stripped)) discardLegacyAuditActiveState(stripped) else stripped
      WorkflowStepStatus.BLOCKED,
      WorkflowStepStatus.PAUSED,
      WorkflowStepStatus.RUNNING,
      -> discardLegacyAuditActiveState(stripped)
      else -> stripped
    }
  }

  override fun dropsBlockedLedgerEntry(
    raw: FeatureTaskRuntimePhaseRecord?,
    resumed: FeatureTaskRuntimePhaseRecord?,
  ): Boolean =
    raw?.let(::hasLegacyAuditGapLineage) == true ||
      resumed?.status?.workflowStepStatus() == WorkflowStepStatus.PENDING

  override fun withholdsDurableOutput(record: FeatureTaskRuntimePhaseRecord): Boolean =
    hasLegacyRemovedAuditVerdict(record)

  override fun invalidatesResumedCompletion(
    record: FeatureTaskRuntimePhaseRecord,
    output: () -> FeatureTaskRuntimePhaseOutput?,
  ): Boolean = isLegacyRemovedAuditCompletion(record)

  internal fun isLegacyAuditGapPersistedBlock(record: FeatureTaskRuntimePhaseRecord): Boolean =
    record.status.workflowStepStatus() == WorkflowStepStatus.BLOCKED && hasLegacyAuditGapLineage(record)

  private fun hasLegacyAuditGapLineage(record: FeatureTaskRuntimePhaseRecord): Boolean =
    isRetiredAuditGapLoop(record.loopId) ||
      hasLegacyRemovedAuditVerdict(record) ||
      hasLegacyAuditGapInnerGaps(record)

  private fun discardLegacyAuditActiveState(record: FeatureTaskRuntimePhaseRecord): FeatureTaskRuntimePhaseRecord =
    record.copy(
      status = WorkflowStepStatus.PENDING,
      outputArtifact = null,
      finishedAt = null,
      durationMillis = null,
      blockedReason = null,
      failureDisposition = null,
      loopId = null,
      edgeIteration = null,
    )

  private fun hasLegacyRemovedAuditVerdict(record: FeatureTaskRuntimePhaseRecord): Boolean {
    val envelope = recordEnvelope(record) ?: return false
    val verdict = (envelope[SharedPayloadKeys.VERDICT] as? String)?.trim()?.lowercase()
    return verdict == FeatureTaskRuntimeVerdict.GAPS_FOUND.wireValue
  }

  private fun hasLegacyAuditGapInnerGaps(record: FeatureTaskRuntimePhaseRecord): Boolean {
    val envelope = recordEnvelope(record) ?: return false
    val produced = JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS]) ?: return false
    val directGaps = produced["gaps"] as? List<*>
    return !directGaps.isNullOrEmpty() || hasLegacyInnerGaps(produced[SharedPayloadKeys.VALUE]?.toString())
  }

  private fun hasLegacyInnerGaps(value: String?): Boolean {
    if (value.isNullOrBlank()) return false
    val inner =
      runCatching {
        JsonCodec.parseObjectOrNull(value)
          ?.let(JsonCodec::jsonElementToValue)
          ?.let(JsonCodec::anyToStringAnyMap)
      }.getOrNull() ?: return false
    return (inner["gaps"] as? List<*>)?.isNotEmpty() == true
  }

  private fun isLegacyRemovedAuditCompletion(record: FeatureTaskRuntimePhaseRecord): Boolean =
    hasLegacyRemovedAuditVerdict(record) && record.status.workflowStepStatus() == WorkflowStepStatus.COMPLETED
}
