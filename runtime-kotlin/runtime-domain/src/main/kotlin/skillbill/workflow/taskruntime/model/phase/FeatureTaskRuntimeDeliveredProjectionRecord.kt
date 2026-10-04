package skillbill.workflow.taskruntime.model.phase

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.workflow.taskruntime.model.core.FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffEnvelope
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.handoff.task.REPOSITORY_CHECKPOINT_FIELD

data class FeatureTaskRuntimeDeliveredProjectionRecord(
  val workflowId: String,
  val consumerPhaseId: String,
  val iteration: Int,
  val envelope: FeatureTaskRuntimeHandoffEnvelope,
  val sourceProducerIterations: List<FeatureTaskRuntimeProducerIteration> =
    envelope.projections.map { it.producerIteration }.distinct(),
) {
  val repositoryCheckpointFingerprint: String =
    envelope.repositoryCheckpoint?.fingerprint ?: "not_required:$consumerPhaseId"

  init {
    require(workflowId.isNotBlank()) { "FeatureTaskRuntimeDeliveredProjectionRecord.workflowId must be non-blank." }
    require(consumerPhaseId.isNotBlank()) {
      "FeatureTaskRuntimeDeliveredProjectionRecord.consumerPhaseId must be non-blank."
    }
    require(iteration >= 1) {
      "FeatureTaskRuntimeDeliveredProjectionRecord.iteration must be >= 1, was $iteration."
    }
    require(sourceProducerIterations.toSet() == envelope.projections.map { it.producerIteration }.toSet()) {
      "FeatureTaskRuntimeDeliveredProjectionRecord source producer identities must match its delivered projections."
    }
    require(envelope.consumerPhaseId == consumerPhaseId) {
      "FeatureTaskRuntimeDeliveredProjectionRecord for '$consumerPhaseId' carries an envelope addressed to " +
        "'${envelope.consumerPhaseId}'."
    }
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf(
      SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION,
      "record_kind" to "delivered_projection",
      SharedPayloadKeys.WORKFLOW_ID to workflowId,
      "consumer_phase_id" to consumerPhaseId,
      "consumer_delivery_iteration" to iteration,
      "source_producer_iterations" to
        sourceProducerIterations.map {
          mapOf(SharedPayloadKeys.PHASE_ID to it.phaseId, "iteration" to it.iteration)
        },
      REPOSITORY_CHECKPOINT_FIELD to
        mapOf(ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT_FINGERPRINT to repositoryCheckpointFingerprint),
      "handoff_envelope" to envelope.toEnvelopeMap(),
    )

  companion object {
    internal fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeDeliveredProjectionRecord {
      requireExactDeliveredProjectionFields(raw)
      requireSupportedPersistenceContract(raw)
      requireDeliveredProjectionRecordKind(raw)
      val record = decodeDeliveredProjection(raw)
      requireMatchingCheckpoint(raw, record)
      return record
    }

    private fun requireSupportedPersistenceContract(raw: Map<String, Any?>) {
      val contractVersion =
        raw[SharedPayloadKeys.CONTRACT_VERSION] as? String ?: missing(
          SharedPayloadKeys.CONTRACT_VERSION,
        )
      if (contractVersion != FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION) {
        throw invalidWorkflowStateSchemaError(
          "Feature-task-runtime delivered projection uses unsupported persistence contract version " +
            "'$contractVersion'; $FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE.",
        )
      }
    }

    private fun requireDeliveredProjectionRecordKind(raw: Map<String, Any?>) {
      if (raw["record_kind"] != "delivered_projection") {
        throw invalidWorkflowStateSchemaError(
          "Feature-task-runtime prompt-facing persistence record must have kind 'delivered_projection'; " +
            "private evidence cannot be read through this API.",
        )
      }
    }

    private fun decodeDeliveredProjection(raw: Map<String, Any?>) =
      FeatureTaskRuntimeDeliveredProjectionRecord(
        workflowId = raw[SharedPayloadKeys.WORKFLOW_ID] as? String ?: missing(SharedPayloadKeys.WORKFLOW_ID),
        consumerPhaseId = raw["consumer_phase_id"] as? String ?: missing("consumer_phase_id"),
        iteration =
          (raw["consumer_delivery_iteration"] as? Number)?.toInt()
            ?: missing("consumer_delivery_iteration"),
        envelope =
          FeatureTaskRuntimeHandoffEnvelope.fromEnvelopeMap(
            JsonCodec.anyToStringAnyMap(raw["handoff_envelope"])

              ?: missing("handoff_envelope"),
          ),
        sourceProducerIterations = decodeSourceProducerIterations(raw),
      )

    private fun decodeSourceProducerIterations(raw: Map<String, Any?>): List<FeatureTaskRuntimeProducerIteration> =
      (raw["source_producer_iterations"] as? List<*>)
        ?.map { identity ->
          val map = JsonCodec.anyToStringAnyMap(identity) ?: missing("source_producer_iterations")
          FeatureTaskRuntimeProducerIteration(
            phaseId = map[SharedPayloadKeys.PHASE_ID] as? String ?: missing("source_producer_iterations.phase_id"),
            iteration =
              (map["iteration"] as? Number)?.toInt()
                ?: missing("source_producer_iterations.iteration"),
          )
        } ?: missing("source_producer_iterations")

    private fun requireMatchingCheckpoint(
      raw: Map<String, Any?>,
      record: FeatureTaskRuntimeDeliveredProjectionRecord,
    ) {
      val checkpoint =
        JsonCodec.anyToStringAnyMap(raw[REPOSITORY_CHECKPOINT_FIELD])
          ?: missing(REPOSITORY_CHECKPOINT_FIELD)
      val persistedFingerprint =
        checkpoint[ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT_FINGERPRINT] as? String
          ?: missing("repository_checkpoint.fingerprint")
      if (persistedFingerprint != record.repositoryCheckpointFingerprint) {
        throw invalidWorkflowStateSchemaError(
          "Feature-task-runtime delivered projection checkpoint identity does not match its validated envelope; " +
            "restart the consumer phase from current repository state.",
        )
      }
    }

    private fun requireExactDeliveredProjectionFields(raw: Map<String, Any?>) {
      val expected =
        setOf(
          SharedPayloadKeys.CONTRACT_VERSION,
          "record_kind",
          SharedPayloadKeys.WORKFLOW_ID,
          "consumer_phase_id",
          "consumer_delivery_iteration",
          "source_producer_iterations",
          REPOSITORY_CHECKPOINT_FIELD,
          "handoff_envelope",
        )
      val unexpected = raw.keys - expected
      if (unexpected.isNotEmpty()) {
        throw invalidWorkflowStateSchemaError(
          "Feature-task-runtime delivered-projection record contains unsupported fields; " +
            "$FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE.",
        )
      }
    }

    private fun missing(field: String): Nothing =
      throw invalidWorkflowStateSchemaError(
        "Feature-task-runtime delivered-projection record is missing field '$field'; " +
          "$FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE.",
      )
  }
}
