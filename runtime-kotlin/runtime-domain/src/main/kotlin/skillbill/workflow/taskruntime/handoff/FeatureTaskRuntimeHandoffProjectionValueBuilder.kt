package skillbill.workflow.taskruntime.handoff

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.contracts.review.ReviewFindingPayloadKeys
import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeMeasuredFactKeys
import skillbill.error.featuretask.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeCompactReferenceKind
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionField
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.REPOSITORY_CHECKPOINT_FIELD
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDispositionVerdict
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal object FeatureTaskRuntimeHandoffProjectionValueBuilder {
  private val phaseProjectionContractIds: Set<String> =
    setOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REVIEW_CLEARANCE,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REVIEW_REPAIR_REQUEST,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.FINDINGS_VERIFICATION_INPUT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.FINDINGS_VERIFICATION_DISPOSITIONS,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REPAIR_PLAN,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.CHANGE_RECEIPT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_REQUEST,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_RECEIPT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BUILD_RECEIPT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BOUNDARY_CANDIDATES,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.HISTORY_RECEIPT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_REQUEST,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_RECEIPT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PR_REQUEST,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE,
    )

  fun phaseProjectionFields(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
    output: FeatureTaskRuntimePhaseOutput,
  ): List<FeatureTaskRuntimeHandoffProjectionField>? {
    if (declaration.projectionContractId !in phaseProjectionContractIds) return null
    val envelope =
      output.normalizedOutput?.envelope
        ?: JsonCodec.parseObjectOrNull(output.payload)?.let { JsonCodec.jsonElementToValue(it) }
          ?.let(JsonCodec::anyToStringAnyMap)
        ?: rejectFeatureTaskRuntimeHandoffProjection(
          inputs,
          declaration,
          FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
          "validated producer output could not be decoded as an object.",
        )
    val produced = JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS]).orEmpty()
    val runtimeOwned = runtimeOwnedPhaseProjectionValues(inputs, declaration, produced)
    return declaration.declaredFieldNames.mapNotNull { name ->
      val value =
        runtimeOwned[name] ?: when {
          name == SharedPayloadKeys.VERDICT -> envelope[name]
          else -> resolveDeclaredPhaseField(produced, name)
        }
      value?.let {
        FeatureTaskRuntimeHandoffProjectionField(name, projectionValue(name, it, inputs, declaration))
      }
    }
  }

  private fun runtimeOwnedPhaseProjectionValues(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
    produced: Map<String, Any?>,
  ): Map<String, Any?> =
    when (declaration.projectionContractId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REVIEW_REPAIR_REQUEST ->
        mapOf(
          "unresolved_blocker_findings" to verifiedFindingsProjection(inputs, produced),
          REPOSITORY_CHECKPOINT_FIELD to checkpointFingerprint(inputs),
        )
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.FINDINGS_VERIFICATION_INPUT ->
        mapOf(
          ReviewVerificationSignalKeys.REVIEW_FINDINGS to reviewFindingsForVerificationProjection(produced),
          REPOSITORY_CHECKPOINT_FIELD to checkpointFingerprint(inputs),
        )
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.FINDINGS_VERIFICATION_DISPOSITIONS ->
        mapOf(
          ReviewVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS to
            produced[ReviewVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS],
          REPOSITORY_CHECKPOINT_FIELD to checkpointFingerprint(inputs),
        )
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.CHANGE_RECEIPT ->
        mapOf(
          "changed_paths" to inputs.resolvedCheckpoint?.workingTreeOwnedPaths.orEmpty(),
          "tests_added" to (produced["tests_added"] as? List<*>).orEmpty().filterIsInstance<String>(),
          "tests_updated" to (produced["tests_updated"] as? List<*>).orEmpty().filterIsInstance<String>(),
          "deviations" to (produced["deviations"] as? List<*>).orEmpty().filterIsInstance<String>(),
          REPOSITORY_CHECKPOINT_FIELD to checkpointFingerprint(inputs),
        )
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE ->
        phaseProseProjectionValues(inputs, declaration, produced)
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.HISTORY_RECEIPT ->
        measuredHistoryFacts(produced)
      else -> FeatureTaskRuntimeHandoffProjectionFinalization.finalizationProjectionValues(inputs, declaration)
    }.filterValues { it != null }

  private fun measuredHistoryFacts(produced: Map<String, Any?>): Map<String, Any?> {
    val measured = JsonCodec.anyToStringAnyMap(produced[FeatureTaskRuntimeMeasuredFactKeys.MEASURED_FACTS]).orEmpty()
    return listOf(
      FeatureTaskRuntimeMeasuredFactKeys.CHANGED_PATHS,
      FeatureTaskRuntimeMeasuredFactKeys.HISTORY_WRITTEN,
      FeatureTaskRuntimeMeasuredFactKeys.DECISIONS_RECORDED,
    ).associateWith { key -> measured[key] ?: FeatureTaskRuntimeMeasuredFactKeys.UNKNOWN }
  }

  private fun checkpointFingerprint(inputs: FeatureTaskRuntimeHandoffProjectionInputs): Map<String, String>? =
    inputs.resolvedCheckpoint?.let {
      mapOf(ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT_FINGERPRINT to it.fingerprint)
    }

  private fun phaseProseProjectionValues(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
    produced: Map<String, Any?>,
  ): Map<String, Any?> {
    val value =
      resolveDeclaredPhaseField(produced, SharedPayloadKeys.VALUE)
        ?: rejectFeatureTaskRuntimeHandoffProjection(
          inputs,
          declaration,
          FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
          "produced_outputs.value is required for phase prose handoff.",
        )
    val valueText = value.toString()
    if (valueText.isBlank()) {
      rejectFeatureTaskRuntimeHandoffProjection(
        inputs,
        declaration,
        FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
        "produced_outputs.value must contain non-blank prose for phase handoff.",
      )
    }
    val fields = linkedMapOf<String, Any?>(SharedPayloadKeys.VALUE to valueText)
    resolveDeclaredPhaseField(produced, SharedPayloadKeys.PROMPT)
      ?.toString()
      ?.takeIf(String::isNotBlank)
      ?.let { fields["directive"] = it }
    return fields
  }

  private fun verifiedFindingsProjection(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    produced: Map<String, Any?>,
  ): List<Map<String, Any?>> {
    val reviewProduced =
      inputs.resolvedUpstream.outputsByPhaseId[
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
      ]?.let(FeatureTaskRuntimeHandoffProjectionFinalization::genericProducedOutputs).orEmpty()
    val reviewFindingsById =
      reviewFindingsForVerificationProjection(reviewProduced)
        .associateBy { it[ReviewFindingPayloadKeys.FINDING_ID]?.toString().orEmpty() }
    return FeatureTaskRuntimeFindingVerificationDisposition.parseList(
      produced[ReviewVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS],
      "produced_outputs.finding_dispositions",
    )
      .filter { it.disposition == FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED }
      .map { disposition ->
        val review = reviewFindingsById[disposition.findingId]
        val severity =
          (review?.get("severity") as? String)
            ?.trim()
            ?.lowercase()
            ?.takeIf(String::isNotBlank)
            ?: "blocker"
        mapOf(
          ReviewFindingPayloadKeys.FINDING_ID to disposition.findingId,
          "severity" to severity,
          "location" to (review?.get("location") ?: "repository"),
          "expected_outcome" to (
            review?.get("message")?.toString()?.takeIf(String::isNotBlank)
              ?: disposition.reason
              ?: "Verified finding."
          ),
          "criterion_refs" to emptyList<String>(),
          "task_refs" to emptyList<String>(),
        )
      }
  }

  private fun reviewFindingsForVerificationProjection(produced: Map<String, Any?>): List<Map<String, Any?>> =
    (produced[ReviewVerificationSignalKeys.REVIEW_FINDINGS] as? List<*>).orEmpty()
      .mapNotNull(JsonCodec::anyToStringAnyMap)
      .map { finding ->
        val severity = (finding["severity"] as? String)?.takeIf(String::isNotBlank) ?: "blocker"
        mapOf(
          ReviewFindingPayloadKeys.FINDING_ID to (
            finding[ReviewFindingPayloadKeys.FINDING_ID]
              ?: finding[ReviewFindingPayloadKeys.F_NUMBER]
              ?: finding[DecompositionPlanningPayloadKeys.ID]
          ),
          "severity" to severity,
          "location" to (
            finding["location"] ?: finding[ReviewFindingPayloadKeys.REPOSITORY_PATH] ?: finding["path"] ?: "repository"
          ),
          "message" to (
            finding["message"] ?: finding["description"] ?: finding["expected_outcome"] ?: "Review finding."
          ),
          ReviewFindingPayloadKeys.ISSUE_CATEGORY to (
            finding[ReviewFindingPayloadKeys.ISSUE_CATEGORY] ?: finding["category"] ?: "other"
          ),
          ReviewFindingPayloadKeys.CLAIM_VERDICT to finding[ReviewFindingPayloadKeys.CLAIM_VERDICT],
          ReviewFindingPayloadKeys.SCOPE_DISPOSITION to finding[ReviewFindingPayloadKeys.SCOPE_DISPOSITION],
        ).filterValues { it != null }
      }

  private fun resolveDeclaredPhaseField(
    produced: Map<String, Any?>,
    name: String,
  ): Any? {
    produced[name]?.let { return it }
    val resultContainers =
      listOf(
        "audit_result",
        "review_result",
        "validation_result",
        "build_receipt",
        "commit_push_result",
      )
    val nested =
      resultContainers.firstNotNullOfOrNull { container ->
        JsonCodec.anyToStringAnyMap(produced[container])?.get(name)
      }
    if (nested != null) return nested
    return null
  }

  private fun projectionValue(
    name: String,
    value: Any,
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): FeatureTaskRuntimeHandoffProjectionValue {
    if (name == FeatureTaskRuntimeHandoffProjectionEnvelopeWire.REPOSITORY_CHECKPOINT_FIELD) {
      val checkpoint = JsonCodec.anyToStringAnyMap(value)
      val fingerprint =
        (checkpoint?.get(ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT_FINGERPRINT) as? String)
          ?.takeIf(String::isNotBlank)
          ?: rejectFeatureTaskRuntimeHandoffProjection(
            inputs,
            declaration,
            FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
            "repository_checkpoint must contain a non-blank fingerprint.",
          )
      return FeatureTaskRuntimeHandoffProjectionValue.CompactReference(
        FeatureTaskRuntimeCompactReferenceKind.REPOSITORY_CHECKPOINT,
        fingerprint,
      )
    }
    return when (value) {
      is Iterable<*> ->
        FeatureTaskRuntimeHandoffProjectionValue.TextList(
          value.map { item ->
            when (item) {
              is String -> item
              is Map<*, *> ->
                JsonCodec.mapToJsonString(
                  item.entries.associate { (key, entryValue) -> key.toString() to entryValue },
                )
              else -> item.toString()
            }
          },
        )
      is Map<*, *> ->
        FeatureTaskRuntimeHandoffProjectionValue.Text(
          JsonCodec.mapToJsonString(value.entries.associate { (key, entryValue) -> key.toString() to entryValue }),
        )
      else -> FeatureTaskRuntimeHandoffProjectionValue.Text(value.toString())
    }
  }
}
