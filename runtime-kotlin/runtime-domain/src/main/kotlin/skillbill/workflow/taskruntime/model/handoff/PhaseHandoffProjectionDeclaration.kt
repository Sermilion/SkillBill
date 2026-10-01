package skillbill.workflow.taskruntime.model.handoff

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PHASE_HANDOFF_CONTRACT_VERSION
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseHandoffSchemaError
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.handoff.task.FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeCompactReferenceKind
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionBudget
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffPromptVisibility
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.handoff.task.PROJECTION_NAME_PATTERN

data class PhaseHandoffProjectionDeclaration(
  val consumerPhaseId: String,
  val sourceRef: FeatureTaskRuntimeHandoffSourceRef,
  val shape: PhaseHandoffProjectionShape,
  val delivery: PhaseHandoffProjectionDelivery = PhaseHandoffProjectionDelivery(),
) {
  val projectionName: String get() = shape.projectionName
  val projectionContractId: String get() = shape.projectionContractId
  val projectionContractVersion: String get() = shape.projectionContractVersion
  val promptVisibility: FeatureTaskRuntimeHandoffPromptVisibility get() = shape.promptVisibility
  val budget: FeatureTaskRuntimeHandoffProjectionBudget get() = shape.budget
  val declaredFieldNames: List<String> get() = shape.declaredFieldNames
  val checkpointPolicy: FeatureTaskRuntimeRepositoryCheckpointPolicy get() = delivery.checkpointPolicy
  val required: Boolean get() = delivery.required
  val allowsPrivateArtifactReference: Boolean get() = delivery.allowsPrivateArtifactReference
  val inlineAlternative: FeatureTaskRuntimeCompactReferenceKind? get() = delivery.inlineAlternative
  val authorizedReferenceKinds: Set<FeatureTaskRuntimeCompactReferenceKind>
    get() =
      delivery.authorizedReferenceKinds.ifEmpty {
        listOfNotNull(delivery.inlineAlternative).toSet()
      }
  val producerIteration: FeatureTaskRuntimeProducerIteration
    get() =
      delivery.producerIteration ?: FeatureTaskRuntimeProducerIteration(
        phaseId =
          (sourceRef as? FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput)?.producingPhaseId
            ?: consumerPhaseId,
        iteration = 1,
      )

  init {
    require(consumerPhaseId.isNotBlank()) { "PhaseHandoffProjectionDeclaration.consumerPhaseId must be non-blank." }
    require(PROJECTION_NAME_PATTERN.matches(projectionName)) {
      "PhaseHandoffProjectionDeclaration.projectionName must match ${PROJECTION_NAME_PATTERN.pattern}, " +
        "was '$projectionName'."
    }
    require(projectionContractId.isNotBlank()) {
      "PhaseHandoffProjectionDeclaration.projectionContractId must be non-blank."
    }
    require(projectionContractVersion.isNotBlank()) {
      "PhaseHandoffProjectionDeclaration.projectionContractVersion must be non-blank."
    }
    require(declaredFieldNames.isNotEmpty()) {
      "PhaseHandoffProjectionDeclaration '$projectionName' must declare at least one field name; an open " +
        "projection shape cannot be validated."
    }
    require(declaredFieldNames.distinct().size == declaredFieldNames.size) {
      "PhaseHandoffProjectionDeclaration '$projectionName' declares duplicate field names."
    }
    require(declaredFieldNames.none { it in FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES }) {
      "PhaseHandoffProjectionDeclaration '$projectionName' declares a forbidden raw-context field name."
    }
    require(inlineAlternative == null || inlineAlternative in authorizedReferenceKinds) {
      "PhaseHandoffProjectionDeclaration '$projectionName' inline alternative must be explicitly authorized."
    }
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf(
      SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_PHASE_HANDOFF_CONTRACT_VERSION,
      "consumer_phase_id" to consumerPhaseId,
      "projection_name" to projectionName,
      "source" to sourceRef.toDeclarationMap(),
      "projection_contract" to
        mapOf(
          DecompositionPlanningPayloadKeys.ID to projectionContractId,
          "version" to projectionContractVersion,
        ),
      "prompt_visibility" to promptVisibility.wireValue,
      "budget" to
        mapOf(
          "max_utf8_bytes" to budget.maxUtf8Bytes,
          "max_collection_items" to budget.maxCollectionItems,
        ),
      "checkpoint_policy" to checkpointPolicy.wireValue,
      "producer_iteration" to
        mapOf(
          SharedPayloadKeys.PHASE_ID to producerIteration.phaseId,
          "iteration" to producerIteration.iteration,
        ),
      "declared_fields" to declaredFieldNames,
      "required" to required,
      "allows_private_artifact_reference" to allowsPrivateArtifactReference,
    ).apply {
      inlineAlternative?.let { put("inline_alternative", it.wireValue) }
      if (authorizedReferenceKinds.isNotEmpty()) {
        put("authorized_reference_kinds", authorizedReferenceKinds.map { it.wireValue }.sorted())
      }
    }

  companion object {
    internal fun fromArtifactMap(raw: Map<String, Any?>): PhaseHandoffProjectionDeclaration {
      val allowed =
        setOf(
          SharedPayloadKeys.CONTRACT_VERSION, "consumer_phase_id", "projection_name", "source", "projection_contract",
          "prompt_visibility", "budget", "checkpoint_policy", "producer_iteration", "declared_fields",
          "required", "allows_private_artifact_reference", "inline_alternative", "authorized_reference_kinds",
        )
      invalidIf(
        raw.keys.any { it !in allowed } ||
          raw[SharedPayloadKeys.CONTRACT_VERSION] != FEATURE_TASK_RUNTIME_PHASE_HANDOFF_CONTRACT_VERSION,
      )
      val source = raw["source"] as? Map<*, *> ?: invalid()
      val sourceRef = sourceRefOf(source)
      val contract = raw["projection_contract"] as? Map<*, *> ?: invalid()
      val budget = raw["budget"] as? Map<*, *> ?: invalid()
      val producer = raw["producer_iteration"] as? Map<*, *> ?: invalid()
      val references =
        (raw["authorized_reference_kinds"] as? List<*>).orEmpty().map {
          FeatureTaskRuntimeCompactReferenceKind.fromWire(it as? String ?: invalid())
        }.toSet()
      val inlineAlternative =
        (raw["inline_alternative"] as? String)
          ?.let(FeatureTaskRuntimeCompactReferenceKind::fromWire)
      return PhaseHandoffProjectionDeclaration(
        consumerPhaseId = raw.string("consumer_phase_id"),
        sourceRef = sourceRef,
        shape =
          PhaseHandoffProjectionShape(
            projectionName = raw.string("projection_name"),
            projectionContractId = contract.string(DecompositionPlanningPayloadKeys.ID),
            projectionContractVersion = contract.string("version"),
            promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.fromWire(raw.string("prompt_visibility")),
            budget =
              FeatureTaskRuntimeHandoffProjectionBudget(
                maxUtf8Bytes = budget.int("max_utf8_bytes"),
                maxCollectionItems = budget.int("max_collection_items"),
              ),
            declaredFieldNames = (raw["declared_fields"] as? List<*>)?.map { it as? String ?: invalid() } ?: invalid(),
          ),
        delivery =
          PhaseHandoffProjectionDelivery(
            checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.fromWire(raw.string("checkpoint_policy")),
            required = raw.boolean("required"),
            allowsPrivateArtifactReference = raw.boolean("allows_private_artifact_reference"),
            producerIteration =
              FeatureTaskRuntimeProducerIteration(
                phaseId = producer.string(SharedPayloadKeys.PHASE_ID),
                iteration = producer.int("iteration"),
              ),
            inlineAlternative = inlineAlternative,
            authorizedReferenceKinds = references,
          ),
      )
    }

    private fun sourceRefOf(source: Map<*, *>): FeatureTaskRuntimeHandoffSourceRef =
      when (source["kind"]) {
        "upstream_phase_output" ->
          FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput(
            source.string(DecompositionPlanningPayloadKeys.ID),
          )
        "run_invariant_field" ->
          FeatureTaskRuntimeHandoffSourceRef.RunInvariantField(
            FeatureTaskRuntimeRunInvariantPromptField.fromWire(source.string(DecompositionPlanningPayloadKeys.ID)),
          )
        "derived_ceremony_scaling" -> FeatureTaskRuntimeHandoffSourceRef.DerivedCeremonyScaling
        "addon_content" ->
          FeatureTaskRuntimeHandoffSourceRef.AddonContentRef(
            source.string(DecompositionPlanningPayloadKeys.ID),
          )
        FeatureTaskRuntimeHandoffSourceRef.SHARED_REVIEW_EVIDENCE_WIRE ->
          FeatureTaskRuntimeHandoffSourceRef.SharedReviewEvidence
        FeatureTaskRuntimeHandoffSourceRef.REPAIR_LEDGER_WIRE ->
          FeatureTaskRuntimeHandoffSourceRef.RepairLedger
        FeatureTaskRuntimeHandoffSourceRef.RETIRED_PRIOR_GAP_MEMORY_WIRE ->
          invalid()
        else -> invalid()
      }

    private fun Map<*, *>.string(key: String): String = (this[key] as? String)?.takeIf(String::isNotBlank) ?: invalid()

    private fun Map<*, *>.int(key: String): Int = (this[key] as? Number)?.toInt() ?: invalid()

    private fun Map<*, *>.boolean(key: String): Boolean = this[key] as? Boolean ?: invalid()

    private fun invalidIf(condition: Boolean) {
      if (condition) invalid()
    }

    private fun invalid(): Nothing =
      throw InvalidFeatureTaskRuntimePhaseHandoffSchemaError(
        sourceLabel = "phase-handoff-declaration",
        reason = "unsupported version, unknown field, or malformed closed-world value",
      )
  }
}
