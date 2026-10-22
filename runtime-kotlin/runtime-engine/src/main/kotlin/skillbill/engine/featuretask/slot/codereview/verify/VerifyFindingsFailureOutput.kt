package skillbill.engine.featuretask.slot.codereview.verify

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.featuretask.persist.workflowArtifactEntryMap
import skillbill.goalrunner.subtaskreview.FeatureTaskRuntimeVerificationSignalKeys
import skillbill.goalrunner.subtaskreview.GoalSubtaskReviewStructuredFindingsParse
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDispositionVerdict
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal object VerifyFindingsFailureOutput {
  private const val SUMMARY = "Finding verification unavailable; original review findings retained for repair."

  fun carryReviewFindings(upstream: List<FeatureTaskRuntimePhaseOutput>): NormalizedFeatureTaskRuntimePhaseOutput {
    val review = upstream.lastOrNull { it.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW }
    val findings =
      review?.let {
        GoalSubtaskReviewStructuredFindingsParse.structuredFindings(
          (
            it.normalizedOutput ?: NormalizedFeatureTaskRuntimePhaseOutput.fromEnvelopeText(
              it.payload,
              it.phaseId,
            )
          ).envelopeWireMap(),
        )
      }.orEmpty()
    val dispositions =
      findings.mapNotNull { it.findingId }.distinct().map { id ->
        FeatureTaskRuntimeFindingVerificationDisposition(
          findingId = id,
          disposition = FeatureTaskRuntimeFindingVerificationDispositionVerdict.VERIFIED,
          reason = SUMMARY,
          boundaryContextUnavailable = true,
        )
      }
    return NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        mapOf(
          SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
          SharedPayloadKeys.PHASE_ID to FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
          SharedPayloadKeys.STATUS to "completed",
          SharedPayloadKeys.SUMMARY to SUMMARY,
          SharedPayloadKeys.VERDICT to
            if (dispositions.isEmpty()) {
              FeatureTaskRuntimeVerdict.NO_FINDINGS_VERIFIED.wireValue
            } else {
              FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED.wireValue
            },
          SharedPayloadKeys.PRODUCED_OUTPUTS to
            mapOf(
              SharedPayloadKeys.VALUE to SUMMARY,
              FeatureTaskRuntimeVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS to
                dispositions.map { workflowArtifactEntryMap(it.asWorkflowArtifactEntry()) },
            ),
        ),
      ),
    )
  }
}
