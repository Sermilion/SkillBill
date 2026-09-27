package skillbill.workflow.taskruntime.phase

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.envelope.SettlementEnvelopeRequest
import skillbill.workflow.taskruntime.model.handoff.envelope.SettlementStatus
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY

object ProsePhaseOutputSynthesizer {
  private val PROSE_PHASE_IDS: Set<String> =
    setOf(
      PHASE_PREPLAN,
      PHASE_PLAN,
      PHASE_IMPLEMENT,
      PHASE_SIMPLIFY,
      PHASE_AUDIT,
      PHASE_VALIDATE,
      PHASE_WRITE_HISTORY,
      PHASE_PR,
    )

  fun isProsePhase(phaseId: String): Boolean = phaseId in PROSE_PHASE_IDS

  fun trySynthesize(
    phaseOutputText: String,
    phaseId: String,
  ): Any? {
    if (!isProsePhase(phaseId)) return null
    return recoverFinalObject(phaseOutputText, phaseId)
  }

  fun recoverFinalObject(
    phaseOutputText: String,
    stepName: String,
  ): FeatureTaskRuntimeWorkflowArtifactMap? {
    val request = recoveryRequest(phaseOutputText, stepName) ?: return null
    return FeatureTaskRuntimeWorkflowArtifactMap.from(stampEnvelope(request))
  }

  fun envelopeFromSettlement(request: SettlementEnvelopeRequest): Any {
    require(isProsePhase(request.phaseId)) { "phaseId must be a prose phase, was '${request.phaseId}'." }
    require(request.value.any { !it.isWhitespace() }) { "value must be non-blank." }
    require(request.summary.any { !it.isWhitespace() }) { "summary must be non-blank." }
    return stampEnvelope(request)
  }

  private fun recoveryRequest(
    phaseOutputText: String,
    stepName: String,
  ): SettlementEnvelopeRequest? {
    val parsed = ProsePhaseOutputParse.bestEffortParse(phaseOutputText)
    if (parsed == null || !ProsePhaseOutputParse.identityCompatible(parsed, stepName)) return null
    val status = ProsePhaseOutputParse.recoverStatus(parsed) ?: return null
    val value =
      ProsePhaseOutputRecover.directValue(parsed)
        ?: ProsePhaseOutputRecover.recoverLegacyValue(parsed)
        ?: return null
    val settledAsFailure = status == SettlementStatus.BLOCKED.wireValue || status == SettlementStatus.FAILED.wireValue
    return SettlementEnvelopeRequest(
      phaseId = stepName,
      status = status,
      value = value,
      summary = ProsePhaseOutputRecover.recoverSummary(parsed, value),
      prompt = ProsePhaseOutputRecover.recoverPrompt(parsed),
      verdict = ProsePhaseOutputRecover.recoverVerdict(parsed),
      failureDisposition = if (settledAsFailure) ProsePhaseOutputRecover.recoverFailureDisposition(parsed) else null,
    )
  }

  private fun stampEnvelope(request: SettlementEnvelopeRequest): Map<String, Any?> {
    val produced = linkedMapOf<String, Any?>(SharedPayloadKeys.VALUE to request.value)
    if (!request.prompt.isNullOrBlank()) {
      produced[SharedPayloadKeys.PROMPT] = request.prompt
    }
    val envelope =
      linkedMapOf<String, Any?>(
        SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
        SharedPayloadKeys.PHASE_ID to request.phaseId,
        SharedPayloadKeys.STATUS to request.status.wireValue,
        SharedPayloadKeys.SUMMARY to request.summary,
        SharedPayloadKeys.PRODUCED_OUTPUTS to produced,
      )
    val settledAsFailure = request.status == SettlementStatus.BLOCKED || request.status == SettlementStatus.FAILED
    if (settledAsFailure && !request.failureDisposition.isNullOrBlank()) {
      envelope[SharedPayloadKeys.FAILURE_DISPOSITION] = request.failureDisposition
    }
    if (!request.verdict.isNullOrBlank()) {
      envelope[SharedPayloadKeys.VERDICT] = request.verdict
    }
    return envelope
  }
}
