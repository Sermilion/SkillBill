package skillbill.workflow.taskruntime.artifact

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeRepairReceiptError
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairLedgerEntry
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceiptDecodeObservations
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceiptDecoded
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.audit.featureTaskRuntimeQuarantineEntriesFromWire
import skillbill.workflow.taskruntime.model.audit.featureTaskRuntimeQuarantineRecordToWire
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffEnvelope
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjection
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationFieldAdoption
import skillbill.workflow.taskruntime.model.persistence.featureTaskRuntimeCheckpointIdentitiesFromArtifact
import skillbill.workflow.taskruntime.model.persistence.featureTaskRuntimeCheckpointIdentitiesToArtifact
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.repair.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_PREVIOUS_BLOCKED_REASON_KEY
import skillbill.workflow.taskruntime.model.repair.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_REASON_KEY
import skillbill.workflow.taskruntime.model.repair.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_REOPENED_PHASE_IDS_KEY
import skillbill.workflow.taskruntime.model.repair.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_RETRIED_AT_KEY
import skillbill.workflow.taskruntime.model.repair.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeReadinessEvidence
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationEvidence
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateExecutionEvidence
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRunRecord
import skillbill.workflow.taskruntime.phaseartifacts.decomposeTerminalFrom
import skillbill.workflow.taskruntime.phaseartifacts.goalContinuationFieldAdoptionFrom
import skillbill.workflow.taskruntime.phaseartifacts.operatorBlockRetryFrom
import skillbill.workflow.taskruntime.phaseartifacts.phaseLedgerFrom
import skillbill.workflow.taskruntime.phaseartifacts.phaseRecordsFrom
import skillbill.workflow.taskruntime.phaseartifacts.resolvedBranchFrom

private fun artifactsMap(artifacts: Any?): Map<String, Any?> =
  when {
    artifacts == null -> emptyMap()
    else ->
      JsonCodec.anyToStringAnyMap(artifacts)
        ?: throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime workflow artifacts must decode to an object.",
        )
  }

class FeatureTaskRuntimePhaseRecords(
  private val delegate: Map<String, FeatureTaskRuntimePhaseRecord>,
) : Map<String, FeatureTaskRuntimePhaseRecord> by delegate {
  override val values: Collection<FeatureTaskRuntimePhaseRecord> get() = delegate.values

  override operator fun get(key: String): FeatureTaskRuntimePhaseRecord? = delegate[key]
}

fun phaseRecordsFromWorkflowArtifacts(artifacts: Any?): FeatureTaskRuntimePhaseRecords =
  FeatureTaskRuntimePhaseRecords(phaseRecordsFrom(artifactsMap(artifacts)))

fun resolvedBranchFromWorkflowArtifacts(artifacts: Any?): FeatureTaskRuntimeResolvedBranch? =
  resolvedBranchFrom(artifactsMap(artifacts))

fun operatorBlockRetryFromWorkflowArtifacts(artifacts: Any?): FeatureTaskRuntimeOperatorBlockRetry? =
  operatorBlockRetryFrom(artifactsMap(artifacts))

fun FeatureTaskRuntimeOperatorBlockRetry.asWorkflowArtifactEntry(
  previousBlockedReason: String,
  reopenedPhaseIds: List<String>,
): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(
    mapOf(
      SharedPayloadKeys.PHASE_ID to phaseId,
      FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_REASON_KEY to reason,
      FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_RETRIED_AT_KEY to retriedAt,
      FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_PREVIOUS_BLOCKED_REASON_KEY to previousBlockedReason,
      FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_REOPENED_PHASE_IDS_KEY to reopenedPhaseIds,
    ),
  )

fun goalContinuationFieldAdoptionFromWorkflowArtifacts(
  artifacts: Any?,
): FeatureTaskRuntimeGoalContinuationFieldAdoption? = goalContinuationFieldAdoptionFrom(artifactsMap(artifacts))

fun decomposeTerminalFromWorkflowArtifacts(artifacts: Any?): FeatureTaskRuntimeDecomposeTerminal? =
  decomposeTerminalFrom(artifactsMap(artifacts))

fun phaseLedgerFromWorkflowArtifacts(artifacts: Any?): List<FeatureTaskRuntimePhaseLedgerEntry> =
  phaseLedgerFrom(artifactsMap(artifacts))

fun FeatureTaskRuntimeValidationGateProgress.asWorkflowArtifactEntry(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap())

fun FeatureTaskRuntimeValidationGateRunRecord.presentationWireMap(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap())

fun FeatureTaskRuntimeRepairLedgerEntry.projectionWireMap(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toProjectionMap())

fun decodeValidationGateProgressFromArtifact(raw: Any?): FeatureTaskRuntimeValidationGateProgress? =
  JsonCodec.anyToStringAnyMap(raw)?.let(FeatureTaskRuntimeValidationGateProgress::fromArtifactMap)

internal fun decodeValidationGateProgressFromArtifact(
  raw: Map<String, Any?>,
): FeatureTaskRuntimeValidationGateProgress = FeatureTaskRuntimeValidationGateProgress.fromArtifactMap(raw)

fun FeatureTaskRuntimeValidationGateExecutionEvidence.asWorkflowArtifactEntry(
  repositoryCheckpoint: String,
): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap(repositoryCheckpoint))

fun decodeValidationGateExecutionEvidenceFromArtifact(
  raw: Any?,
  sourceLabel: String,
): FeatureTaskRuntimeValidationGateExecutionEvidence? =
  JsonCodec.anyToStringAnyMap(raw)?.let {
    FeatureTaskRuntimeValidationGateExecutionEvidence.fromArtifactMap(it, sourceLabel)
  }

internal fun decodeValidationGateExecutionEvidenceFromArtifact(
  raw: Map<String, Any?>,
  sourceLabel: String,
): FeatureTaskRuntimeValidationGateExecutionEvidence =
  FeatureTaskRuntimeValidationGateExecutionEvidence.fromArtifactMap(raw, sourceLabel)

fun FeatureTaskRuntimeValidationEvidence.asWorkflowArtifactEntry(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap())

fun FeatureTaskRuntimeReadinessEvidence.asWorkflowArtifactEntry(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap())

fun decodeReadinessEvidenceFromArtifact(
  raw: Any?,
  sourceLabel: String,
): FeatureTaskRuntimeReadinessEvidence? =
  JsonCodec.anyToStringAnyMap(raw)?.let { FeatureTaskRuntimeReadinessEvidence.fromArtifactMap(it, sourceLabel) }

internal fun decodeReadinessEvidenceFromArtifact(
  raw: Map<String, Any?>,
  sourceLabel: String,
): FeatureTaskRuntimeReadinessEvidence = FeatureTaskRuntimeReadinessEvidence.fromArtifactMap(raw, sourceLabel)

fun decodeValidationEvidenceFromArtifact(
  raw: Any?,
  sourceLabel: String,
): FeatureTaskRuntimeValidationEvidence? =
  JsonCodec.anyToStringAnyMap(raw)?.let { FeatureTaskRuntimeValidationEvidence.fromArtifactMap(it, sourceLabel) }

internal fun decodeValidationEvidenceFromArtifact(
  raw: Map<String, Any?>,
  sourceLabel: String,
): FeatureTaskRuntimeValidationEvidence = FeatureTaskRuntimeValidationEvidence.fromArtifactMap(raw, sourceLabel)

fun FeatureTaskRuntimeRepairReceipt.asWorkflowArtifactEntry(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap())

internal fun decodeRepairReceiptFromArtifact(
  raw: Any?,
  sourceLabel: String,
): FeatureTaskRuntimeRepairReceipt? =
  JsonCodec.anyToStringAnyMap(raw)?.let { FeatureTaskRuntimeRepairReceipt.fromArtifactMap(it, sourceLabel) }

internal fun decodeRepairReceiptFromArtifact(
  raw: Map<String, Any?>,
  sourceLabel: String,
): FeatureTaskRuntimeRepairReceipt = FeatureTaskRuntimeRepairReceipt.fromArtifactMap(raw, sourceLabel)

fun decodeRepairReceiptFromArtifactWithObservations(
  raw: Any?,
  sourceLabel: String,
): FeatureTaskRuntimeRepairReceiptDecoded? {
  val collector = FeatureTaskRuntimeRepairReceiptDecodeObservations.Collector()
  val receipt =
    JsonCodec.anyToStringAnyMap(raw)?.let {
      FeatureTaskRuntimeRepairReceipt.fromArtifactMap(it, sourceLabel, collector)
    } ?: return null
  return FeatureTaskRuntimeRepairReceiptDecoded(receipt, collector.finish())
}

internal fun decodeRepairReceiptFromArtifactWithObservations(
  raw: Map<String, Any?>,
  sourceLabel: String,
): FeatureTaskRuntimeRepairReceiptDecoded {
  val collector = FeatureTaskRuntimeRepairReceiptDecodeObservations.Collector()
  val receipt = FeatureTaskRuntimeRepairReceipt.fromArtifactMap(raw, sourceLabel, collector)
  return FeatureTaskRuntimeRepairReceiptDecoded(receipt, collector.finish())
}

fun validateRepairReceiptWireEntries(
  raw: Any,
  path: String,
) {
  FeatureTaskRuntimeRepairReceipt.validateEntries(
    JsonCodec.anyToStringAnyMap(raw)
      ?: throw InvalidFeatureTaskRuntimeRepairReceiptError(
        fieldPath = path,
        reason = "must be an object.",
        payloadFreeReason = "$path must be an object.",
      ),
    path,
  )
}

fun List<FeatureTaskRuntimeQuarantineEntry>.asQuarantineWorkflowArtifactEntry(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(featureTaskRuntimeQuarantineRecordToWire(this))

fun decodeQuarantineEntriesFromArtifact(raw: Any?): List<FeatureTaskRuntimeQuarantineEntry> =
  featureTaskRuntimeQuarantineEntriesFromWire(raw)

fun List<FeatureTaskRuntimeCheckpointIdentity>.asCheckpointIdentitiesArtifactEntry():
  FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(featureTaskRuntimeCheckpointIdentitiesToArtifact(this))

fun decodeCheckpointIdentitiesFromArtifact(raw: Any?): List<FeatureTaskRuntimeCheckpointIdentity> =
  featureTaskRuntimeCheckpointIdentitiesFromArtifact(raw)

fun FeatureTaskRuntimeHandoffEnvelope.asWorkflowArtifactEntry(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toEnvelopeMap())

fun decodeHandoffEnvelopeFromArtifact(raw: Any?): FeatureTaskRuntimeHandoffEnvelope? =
  JsonCodec.anyToStringAnyMap(raw)?.let(FeatureTaskRuntimeHandoffEnvelope::fromEnvelopeMap)

internal fun decodeHandoffEnvelopeFromArtifact(raw: Map<String, Any?>): FeatureTaskRuntimeHandoffEnvelope =
  FeatureTaskRuntimeHandoffEnvelope.fromEnvelopeMap(raw)

fun FeatureTaskRuntimeHandoffProjection.asWorkflowArtifactEntry(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toEnvelopeMap())

fun FeatureTaskRuntimeRepositoryCheckpoint.asWorkflowArtifactEntry(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toEnvelopeMap())

fun FeatureTaskRuntimePhaseRecord.asWorkflowArtifactEntry(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap())

fun decodePhaseRecordFromArtifact(raw: Any?): FeatureTaskRuntimePhaseRecord? =
  JsonCodec.anyToStringAnyMap(raw)?.let(FeatureTaskRuntimePhaseRecord::fromArtifactMap)

internal fun decodePhaseRecordFromArtifact(raw: Map<String, Any?>): FeatureTaskRuntimePhaseRecord =
  FeatureTaskRuntimePhaseRecord.fromArtifactMap(raw)

fun FeatureTaskRuntimePhaseLedgerEntry.asWorkflowArtifactEntry(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap())

fun decodePhaseLedgerEntryFromArtifact(raw: Any?): FeatureTaskRuntimePhaseLedgerEntry? =
  JsonCodec.anyToStringAnyMap(raw)?.let(FeatureTaskRuntimePhaseLedgerEntry::fromArtifactMap)

internal fun decodePhaseLedgerEntryFromArtifact(raw: Map<String, Any?>): FeatureTaskRuntimePhaseLedgerEntry =
  FeatureTaskRuntimePhaseLedgerEntry.fromArtifactMap(raw)
