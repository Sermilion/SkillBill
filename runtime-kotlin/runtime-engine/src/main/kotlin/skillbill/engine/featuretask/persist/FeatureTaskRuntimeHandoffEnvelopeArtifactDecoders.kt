package skillbill.engine.featuretask.persist

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePersistenceSchemaError
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.artifact.decodeDeliveredProjectionRecordFromArtifact
import skillbill.workflow.taskruntime.model.core.FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeDeliveredProjectionRecord
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.phaseartifacts.decodeStrictKeyedArtifactMap
import skillbill.workflow.taskruntime.phaseartifacts.schemaError

internal fun phaseBriefingsFrom(
  artifacts: Map<String, Any?>,
  validateEnvelope: (Map<String, Any?>) -> Unit = {},
): Map<String, FeatureTaskRuntimePhaseLaunchBriefing> =
  decodeStrictKeyedArtifactMap(
    FeatureTaskRuntimeWorkflowArtifactMap.from(artifacts),
    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS.label(),
    ignoreEntry = { it in PhaseSlot.AUDIT.steps },
  ) { _, briefingMap ->
    val briefing = FeatureTaskRuntimePhaseLaunchBriefing.fromBriefingArtifactWire(briefingMap)
    validateEnvelope(handoffEnvelopeWireMap(briefingMap))
    briefing
  }

private fun handoffEnvelopeWireMap(briefingMap: Map<String, Any?>): Map<String, Any?> =
  JsonCodec.anyToStringAnyMap(briefingMap["handoff_envelope"])
    ?: schemaError(
      "Feature-task-runtime artifact '${DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS.label()}' " +
        "entry must carry a " +
        "'handoff_envelope' object.",
    )

internal fun deliveredProjectionsFrom(
  artifacts: Map<String, Any?>,
  validateEnvelope: (Map<String, Any?>) -> Unit = {},
  validatePersistenceRecord: (Map<String, Any?>) -> Unit = {},
): Map<String, FeatureTaskRuntimeDeliveredProjectionRecord> =
  deliveredProjectionHistoryFrom(artifacts, validateEnvelope, validatePersistenceRecord)
    .values
    .groupBy(FeatureTaskRuntimeDeliveredProjectionRecord::consumerPhaseId)
    .mapValues { (_, records) -> records.maxBy(FeatureTaskRuntimeDeliveredProjectionRecord::iteration) }

internal fun deliveredProjectionHistoryFrom(
  artifacts: Map<String, Any?>,
  validateEnvelope: (Map<String, Any?>) -> Unit = {},
  validatePersistenceRecord: (Map<String, Any?>) -> Unit = {},
): Map<String, FeatureTaskRuntimeDeliveredProjectionRecord> =
  decodeStrictKeyedArtifactMap(
    FeatureTaskRuntimeWorkflowArtifactMap.from(artifacts),
    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS.label(),
    ignoreEntry = {
      it in PhaseSlot.AUDIT.steps || it.split('|').getOrNull(1) in PhaseSlot.AUDIT.steps
    },
  ) { key, recordMap ->
    try {
      validatePersistenceRecord(recordMap)
    } catch (error: InvalidFeatureTaskRuntimePersistenceSchemaError) {
      val consumerPhaseId = recordMap["consumer_phase_id"] as? String ?: "<unknown>"
      throw InvalidFeatureTaskRuntimePersistenceSchemaError(
        sourceLabel = "consumer-phase:$consumerPhaseId/delivered-projection:$key",
        reason = "${error.reason}; $FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE.",
        cause = error,
      )
    }
    val delivered =
      decodeDeliveredProjectionRecordFromArtifact(recordMap)
        ?: schemaError(
          "Feature-task-runtime delivered projection '$key' is malformed.",
        )
    validateEnvelope(
      JsonCodec.anyToStringAnyMap(recordMap["handoff_envelope"])
        ?: schemaError(
          "Feature-task-runtime artifact '" +
            "${DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS.label()}' " +
            "entry must carry a 'handoff_envelope' object.",
        ),
    )
    delivered
  }
