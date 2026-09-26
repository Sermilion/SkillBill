package skillbill.engine.featuretask.review.core

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.review.ReviewFindingPayloadKeys
import skillbill.goalrunner.subtaskreview.FeatureTaskRuntimeVerificationSignalKeys
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.parsing.ReviewFindingActionability
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeReviewSeverity
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewVerdict
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationVerdict
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

object FeatureTaskRuntimeOutputVerification {
  internal fun verdictFor(
    phaseId: String,
    outputObject: FeatureTaskRuntimeWorkflowArtifactMap?,
    stepRule: FeatureTaskRuntimeStepVerdictRule? = null,
  ): FeatureTaskRuntimeVerdict {
    val wireVerdict =
      (outputObject?.get(SharedPayloadKeys.VERDICT) as? String)
        ?.takeIf(String::isNotBlank)
        ?.let { value -> FeatureTaskRuntimeVerdict.rejectRemovedVerdict(value, "phase output verdict") }
    return when (phaseId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> reviewVerdict(outputObject, wireVerdict)
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS ->
        findingVerificationVerdict(wireVerdict)
      else -> stepRule?.verdictFor(wireVerdict, outputObject) ?: wireVerdict ?: FeatureTaskRuntimeVerdict.ADVANCE
    }
  }

  internal fun carriesFindingDispositions(outputObject: FeatureTaskRuntimeWorkflowArtifactMap?): Boolean =
    outputObject?.get(SharedPayloadKeys.PRODUCED_OUTPUTS)
      ?.let(JsonCodec::anyToStringAnyMap)
      ?.containsKey(FeatureTaskRuntimeVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS) == true

  internal fun dispositionsFrom(
    outputObject: FeatureTaskRuntimeWorkflowArtifactMap?,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition> =
    findingVerificationVerdictFrom(outputObject)?.dispositions.orEmpty()

  internal fun verifiedFindingDispositions(
    outputObject: FeatureTaskRuntimeWorkflowArtifactMap?,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition> =
    findingVerificationVerdictFrom(outputObject)?.verifiedDispositions.orEmpty()

  internal fun rejectedFindingDispositions(
    outputObject: FeatureTaskRuntimeWorkflowArtifactMap?,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition> =
    findingVerificationVerdictFrom(outputObject)?.rejectedDispositions.orEmpty()

  internal fun unresolvedReviewFindings(
    outputObject: FeatureTaskRuntimeWorkflowArtifactMap?,
  ): List<FeatureTaskRuntimeReviewFinding> = reviewVerdictFrom(outputObject)?.unresolvedFindings.orEmpty()
}

private fun findingVerificationVerdict(wireVerdict: FeatureTaskRuntimeVerdict?): FeatureTaskRuntimeVerdict =
  requireNotNull(wireVerdict) {
    "verify_findings phase output is missing verdict."
  }

private fun findingVerificationVerdictFrom(
  outputObject: FeatureTaskRuntimeWorkflowArtifactMap?,
): FeatureTaskRuntimeFindingVerificationVerdict? {
  val dispositionsRaw =
    outputObject?.get(SharedPayloadKeys.PRODUCED_OUTPUTS)
      ?.let(JsonCodec::anyToStringAnyMap)
      ?.get(FeatureTaskRuntimeVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS) as? List<*>
      ?: return null
  val dispositions =
    FeatureTaskRuntimeFindingVerificationDisposition.parseList(
      dispositionsRaw,
      "produced_outputs.${FeatureTaskRuntimeVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS}",
    )
  return FeatureTaskRuntimeFindingVerificationVerdict(dispositions)
}

private fun reviewVerdict(
  outputObject: FeatureTaskRuntimeWorkflowArtifactMap?,
  wireVerdict: FeatureTaskRuntimeVerdict?,
): FeatureTaskRuntimeVerdict {
  val reviewVerdict = reviewVerdictFrom(outputObject)
  return reviewVerdict?.verdict ?: wireVerdict ?: FeatureTaskRuntimeVerdict.ADVANCE
}

private fun reviewVerdictFrom(outputObject: FeatureTaskRuntimeWorkflowArtifactMap?): FeatureTaskRuntimeReviewVerdict? {
  val findingsRaw =
    outputObject?.get(SharedPayloadKeys.PRODUCED_OUTPUTS)
      ?.let(JsonCodec::anyToStringAnyMap)
      ?.get(FeatureTaskRuntimeVerificationSignalKeys.REVIEW_FINDINGS) as? List<*>
      ?: return null
  val findings = findingsRaw.mapNotNull(::actionableReviewFinding)
  return FeatureTaskRuntimeReviewVerdict(findings)
}

private fun actionableReviewFinding(entry: Any?): FeatureTaskRuntimeReviewFinding? {
  val map = JsonCodec.anyToStringAnyMap(entry) ?: return null
  val severity = (map["severity"] as? String)?.takeIf(String::isNotBlank)
  val message = (map["message"] as? String)?.takeIf(String::isNotBlank)
  if (severity == null || message == null) return null
  val claimVerdict = optionalClaimVerdict(map[ReviewFindingPayloadKeys.CLAIM_VERDICT])
  val scopeDisposition = optionalScopeDisposition(map[ReviewFindingPayloadKeys.SCOPE_DISPOSITION])
  if (!ReviewFindingActionability.isActionable(claimVerdict, scopeDisposition)) {
    return null
  }
  return FeatureTaskRuntimeReviewFinding(FeatureTaskRuntimeReviewSeverity.fromWire(severity), message)
}

private fun optionalClaimVerdict(raw: Any?): ReviewClaimVerdict? {
  val value = (raw as? String)?.trim()?.takeIf(String::isNotBlank) ?: return null
  return ReviewClaimVerdict.entries.firstOrNull { it.wireValue == value }
}

private fun optionalScopeDisposition(raw: Any?): ReviewScopeDisposition? {
  val value = (raw as? String)?.trim()?.takeIf(String::isNotBlank) ?: return null
  return ReviewScopeDisposition.entries.firstOrNull { it.wireValue == value }
}
