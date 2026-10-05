package skillbill.workflow.taskruntime.model.handoff.task

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.contracts.scaffold.wire.optionalString
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_HANDOFF_ENVELOPE_CONTRACT_VERSION
import skillbill.error.shellcontent.invalidFeatureTaskRuntimePhaseHandoffSchema
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
    val reason = violation(consumerPhaseId, projections, contractVersion)
    require(reason == null) { reason.orEmpty() }
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
    internal fun violation(
      consumerPhaseId: String,
      projections: List<FeatureTaskRuntimeHandoffProjection>,
      contractVersion: String,
    ): String? =
      when {
        consumerPhaseId.isBlank() -> "FeatureTaskRuntimeHandoffEnvelope.consumerPhaseId must be non-blank."
        contractVersion.isBlank() -> "FeatureTaskRuntimeHandoffEnvelope.contractVersion must be non-blank."
        projections.map { it.projectionName }.distinct().size != projections.size ->
          "FeatureTaskRuntimeHandoffEnvelope for '$consumerPhaseId' contains duplicate projection names."
        else -> null
      }

    internal fun fromEnvelopeMap(raw: Map<String, Any?>): FeatureTaskRuntimeHandoffEnvelope {
      val reader = handoffReader(raw)
      val consumerPhaseId = reader.requiredString("consumer_phase_id")
      val projections = reader.requiredList("projections").map(::projectionFromWire)
      val repositoryCheckpoint =
        reader.optionalNestedObject(ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT)?.let {
          val checkpointReader = handoffReader(it)
          val fingerprint =
            checkpointReader.requiredString(ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT_FINGERPRINT)
          val baseRef = checkpointReader.optionalString("base_ref")
          val headRef = checkpointReader.optionalString("head_ref")
          val workingTreeOwnedPaths = checkpointReader.optionalStringList("working_tree_owned_paths")
          FeatureTaskRuntimeRepositoryCheckpoint.violation(fingerprint, workingTreeOwnedPaths)?.let(::decodeError)
          FeatureTaskRuntimeRepositoryCheckpoint(fingerprint, baseRef, headRef, workingTreeOwnedPaths)
        }
      val contractVersion = reader.requiredString(SharedPayloadKeys.CONTRACT_VERSION)
      FeatureTaskRuntimeHandoffEnvelope.violation(consumerPhaseId, projections, contractVersion)?.let(::decodeError)
      return FeatureTaskRuntimeHandoffEnvelope(consumerPhaseId, projections, repositoryCheckpoint, contractVersion)
    }

    private fun projectionFromWire(raw: Any?): FeatureTaskRuntimeHandoffProjection {
      val reader = handoffReader(raw.toStringKeyedArtifactMap(::decodeError))
      val projectionName = reader.requiredString("projection_name")
      val sourceRefWire = reader.requiredString("source_ref")
      FeatureTaskRuntimeHandoffSourceRef.violation(sourceRefWire)?.let(::decodeError)
      val sourceRef = FeatureTaskRuntimeHandoffSourceRef.fromWire(sourceRefWire)
      val projectionContractId = reader.requiredString("projection_contract_id")
      val projectionContractVersion = reader.requiredString("projection_contract_version")
      val promptVisibility =
        FeatureTaskRuntimeHandoffPromptVisibility.fromWire(reader.requiredString("prompt_visibility"))
      val producerIteration = reader.requiredNestedObject("producer_iteration").let {
        val iterationReader = handoffReader(it)
        val phaseId = iterationReader.requiredString(SharedPayloadKeys.PHASE_ID)
        val iteration = iterationReader.requiredInt("iteration")
        FeatureTaskRuntimeProducerIteration.violation(phaseId, iteration)?.let(::decodeError)
        FeatureTaskRuntimeProducerIteration(phaseId, iteration)
      }
      val fields = reader.requiredList("fields").map(::fieldFromWire)
      return FeatureTaskRuntimeHandoffProjection(
        projectionName = projectionName,
        sourceRef = sourceRef,
        projectionContractId = projectionContractId,
        projectionContractVersion = projectionContractVersion,
        promptVisibility = promptVisibility,
        fields = fields,
        producerIteration = producerIteration,
      )
    }

    private fun fieldFromWire(raw: Any?): FeatureTaskRuntimeHandoffProjectionField {
      val reader = handoffReader(raw.toStringKeyedArtifactMap(::decodeError))
      val name = reader.requiredString(DecompositionPlanningPayloadKeys.NAME)
      val value =
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
          }
      FeatureTaskRuntimeHandoffProjectionField.violation(name)?.let(::decodeError)
      return FeatureTaskRuntimeHandoffProjectionField(name, value)
    }

    private fun decodeError(detail: String): Nothing =
      throw invalidFeatureTaskRuntimePhaseHandoffSchema(sourceLabel = "<wire>", reason = detail)

    private fun handoffReader(map: Map<String, Any?>): DurableArtifactMapReader =
      DurableArtifactMapReader(map) { message -> decodeError(message) }
  }
}
