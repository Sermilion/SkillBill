package skillbill.engine.featuretask.slot.audit.planning

import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.phase.core.auditProseValue
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal object AuditPlanFixStep : PhaseStepHooks {
  override fun completionRejection(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: PhaseAcceptedStepExecution,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? {
    if ((outputMap[SharedPayloadKeys.STATUS] as? String).workflowStepStatus() != WorkflowStepStatus.COMPLETED) {
      return null
    }
    val audit =
      context.progress.phase(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)
        .output?.normalizedOutput?.envelopeWireMap()
    return AuditFixPlanCoverage.rejection(
      context.request.runInvariants.acceptanceCriteria,
      auditProseValue(audit).orEmpty(),
      auditProseValue(outputMap).orEmpty(),
    )
  }
}
