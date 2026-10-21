package skillbill.engine.featuretask.phase.record

import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementAckStatus
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementCompleteRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.core.FeatureTaskPhaseSettlementService
import skillbill.ports.persistence.UnitOfWork
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal fun settleReviewPhase(
  unitOfWork: UnitOfWork,
  request: FeatureTaskRuntimePhaseStateRequest,
  service: FeatureTaskPhaseSettlementService,
) {
  if (request.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) return
  val output = checkNotNull(request.normalizedOutput) { "Completed review requires normalized output." }
  val acknowledgment =
    service.complete(
      FeatureTaskPhaseSettlementCompleteRequest(
        workflowId = request.workflowId,
        phaseId = request.phaseId,
        attempt = request.attemptCount,
        value = output.summary,
        prompt = output.output.prompt,
        summary = output.summary,
        verdict = output.verdict,
      ),
      unitOfWork.featureTaskPhaseSettlements,
    )
  check(acknowledgment.status == FeatureTaskPhaseSettlementAckStatus.OK) {
    "Completed review phase settlement was refused."
  }
}
