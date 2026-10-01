package skillbill.engine.featuretask.slot.codereview.history

import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeCurrentPhaseExecutionContext
import skillbill.engine.featuretask.phase.core.attemptPhaseExecution
import skillbill.engine.featuretask.phase.core.defaultPhaseExecution
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecutionKind
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal object CodeReviewHistory {
  fun currentExecution(
    stepId: String,
    context: FeatureTaskRuntimeCurrentPhaseExecutionContext,
  ): IdeStatusCurrentPhaseExecution? =
    when (stepId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ->
        activeReviewPassNumber(context.records[stepId], context.ledger)?.let { pass ->
          IdeStatusCurrentPhaseExecution(
            phaseId = stepId,
            kind = IdeStatusCurrentPhaseExecutionKind.PASS,
            count = pass,
          )
        } ?: attemptPhaseExecution(stepId, context)
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS -> attemptPhaseExecution(stepId, context)
      else -> defaultPhaseExecution(stepId, context)
    }

  private fun activeReviewPassNumber(
    record: FeatureTaskRuntimePhaseRecord?,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  ): Int? {
    val pass = record?.reviewPassNumber ?: return null
    if (record.status.workflowStepStatus() != WorkflowStepStatus.COMPLETED) return pass
    val latestReviewFixEdge =
      ledger
        .filter {
          it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE &&
            it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID &&
            it.edgeIteration != null
        }
        .maxByOrNull { it.sequenceNumber }
    val ledgerEdge = latestReviewFixEdge?.edgeIteration
    val reenteredReview =
      record.takeIf {
        it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
      }?.edgeIteration
    return ledgerEdge?.let { edge ->
      reenteredReview?.takeIf { it >= edge }
    }?.let { pass }
  }
}
