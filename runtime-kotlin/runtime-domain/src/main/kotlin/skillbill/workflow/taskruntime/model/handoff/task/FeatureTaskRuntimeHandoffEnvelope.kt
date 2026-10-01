package skillbill.workflow.taskruntime.model.handoff.task

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.contracts.scaffold.wire.optionalString
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_HANDOFF_ENVELOPE_CONTRACT_VERSION
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseHandoffSchemaError
import skillbill.workflow.model.persistence.artifact.DurableArtifactMapReader
import skillbill.workflow.model.persistence.artifact.toStringKeyedArtifactMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint

data class FeatureTaskRuntimeHandoffEnvelope(
  val consumerPhaseId: String,
  val projections: List<FeatureTaskRuntimeHandoffProjection> = emptyList(),
  val repositoryCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint? = null,
  val contractVersion: String = FEATURE_TASK_RUNTIME_HANDOFF_ENVELOPE_CONTRACT_VERSION,
) {
  init {
    require(consumerPhaseId.isNotBlank()) { "FeatureTaskRuntimeHandoffEnvelope.consumerPhaseId must be non-blank." }
    require(contractVersion.isNotBlank()) { "FeatureTaskRuntimeHandoffEnvelope.contractVersion must be non-blank." }
    val names = projections.map { it.projectionName }
    require(names.distinct().size == names.size) {
      "FeatureTaskRuntimeHandoffEnvelope for '$consumerPhaseId' contains duplicate projection names."
    }
  }

  val promptVisibleProjections: List<FeatureTaskRuntimeHandoffProjection>
    get() = projections.filter { it.promptVisibility == FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE }

  internal fun toEnvelopeMap(): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      SharedPayloadKeys.CONTRACT_VERSION to contractVersion,
      "consumer_phase_id" to consumerPhaseId,
      "projections" to projections.map { it.toEnvelopeMap() },
    ).apply {
      repositoryCheckpoint?.let { put(ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT, it.toEnvelopeMap()) }
    }

  companion object {
    internal fun fromEnvelopeMap(raw: Map<String, Any?>): FeatureTaskRuntimeHandoffEnvelope {
      val reader = handoffReader(raw)
      return try {
        FeatureTaskRuntimeHandoffEnvelope(
          consumerPhaseId = reader.requiredString("consumer_phase_id"),
          projections = reader.requiredList("projections").map(::projectionFromWire),
          repositoryCheckpoint =
            reader.optionalNestedObject(ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT)?.let {
              val checkpointReader = handoffReader(it)
              FeatureTaskRuntimeRepositoryCheckpoint(
                fingerprint =
                  checkpointReader.requiredString(
                    ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT_FINGERPRINT,
                  ),
                baseRef = checkpointReader.optionalString("base_ref"),
                headRef = checkpointReader.optionalString("head_ref"),
                workingTreeOwnedPaths = checkpointReader.optionalStringList("working_tree_owned_paths"),
              )
            },
          contractVersion = reader.requiredString(SharedPayloadKeys.CONTRACT_VERSION),
        )
      } catch (error: IllegalArgumentException) {
        throw InvalidFeatureTaskRuntimePhaseHandoffSchemaError(
          sourceLabel = "<wire>",
          reason = error.message ?: "handoff envelope is invalid.",
          cause = error,
        )
      }
    }

    private fun projectionFromWire(raw: Any?): FeatureTaskRuntimeHandoffProjection {
      val reader = handoffReader(raw.toStringKeyedArtifactMap(::decodeError))
      return FeatureTaskRuntimeHandoffProjection(
        projectionName = reader.requiredString("projection_name"),
        sourceRef = FeatureTaskRuntimeHandoffSourceRef.fromWire(reader.requiredString("source_ref")),
        projectionContractId = reader.requiredString("projection_contract_id"),
        projectionContractVersion = reader.requiredString("projection_contract_version"),
        promptVisibility =
          FeatureTaskRuntimeHandoffPromptVisibility
            .fromWire(reader.requiredString("prompt_visibility")),
        producerIteration =
          reader.requiredNestedObject("producer_iteration").let {
            val iterationReader = handoffReader(it)
            FeatureTaskRuntimeProducerIteration(
              phaseId = iterationReader.requiredString(SharedPayloadKeys.PHASE_ID),
              iteration = iterationReader.requiredInt("iteration"),
            )
          },
        fields = reader.requiredList("fields").map(::fieldFromWire),
      )
    }

    private fun fieldFromWire(raw: Any?): FeatureTaskRuntimeHandoffProjectionField {
      val reader = handoffReader(raw.toStringKeyedArtifactMap(::decodeError))
      val name = reader.requiredString(DecompositionPlanningPayloadKeys.NAME)
      return FeatureTaskRuntimeHandoffProjectionField(
        name = name,
        value =
          when (val kind = reader.requiredString("kind")) {
            "text" -> FeatureTaskRuntimeHandoffProjectionValue.Text(reader.requiredString("text"))
            "text_list" ->
              FeatureTaskRuntimeHandoffProjectionValue.TextList(
                reader.optionalStringList("items"),
              )
            "compact_reference" ->
              FeatureTaskRuntimeHandoffProjectionValue.CompactReference(
                kind = FeatureTaskRuntimeCompactReferenceKind.fromWire(reader.requiredString("reference_kind")),
                value = reader.requiredString("reference_value"),
              )
            else -> decodeError("projection field '$name' has unknown value kind '$kind'.")
          },
      )
    }

    private fun decodeError(detail: String): Nothing =
      throw InvalidFeatureTaskRuntimePhaseHandoffSchemaError(sourceLabel = "<wire>", reason = detail)

    private fun handoffReader(map: Map<String, Any?>): DurableArtifactMapReader =
      DurableArtifactMapReader(map) { message -> decodeError(message) }
  }
}
