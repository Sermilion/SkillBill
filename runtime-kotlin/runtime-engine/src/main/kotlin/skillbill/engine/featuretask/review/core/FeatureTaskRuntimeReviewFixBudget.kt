package skillbill.engine.featuretask.review.core

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskRuntimeGoalContinuationRecorder
import skillbill.engine.featuretask.lifecycle.continuation.reviewState
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeContinuationKind
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

fun reviewFixCapExhaustion(
  ledger: List<FeatureTaskRuntimePhaseLedgerEntry>?,
  goalReviewCapReached: Boolean?,
): Boolean? {
  if (ledger == null) return null
  if (goalReviewCapReached == true) return true
  return ledger.any {
    it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_CAP_EXHAUSTED &&
      it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
  }
}

fun auditGapIterationCount(ledger: List<FeatureTaskRuntimePhaseLedgerEntry>?): Int? {
  if (ledger == null) return null
  return ledger.count(::isAuditGapRound)
}

private fun isAuditGapRound(entry: FeatureTaskRuntimePhaseLedgerEntry): Boolean =
  when (entry.action) {
    FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE ->
      entry.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID ||
        entry.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_REPAIR_LOOP_ID
    FeatureTaskRuntimePhaseLedgerAction.FIX_LOOP_ITERATION ->
      entry.phaseId in PhaseSlot.AUDIT.steps &&
        FeatureTaskRuntimeContinuationKind.fromLedgerDetail(entry.blockedReason) ==
        FeatureTaskRuntimeContinuationKind.AUDIT_AC_RETRY
    else -> false
  }

@Inject
class FeatureTaskRuntimeReviewFixBudget(
  private val recorder: FeatureTaskRuntimePhaseRecorder,
  private val goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
) {
  fun reviewFixCapExhaustion(workflowId: String): Boolean? =
    reviewFixCapExhaustion(
      recorder.loadPhaseLedger(workflowId),
      goalContinuationRecorder.reviewState(workflowId)?.reviewCapReached,
    )

  fun auditGapIterationCount(workflowId: String): Int? = auditGapIterationCount(recorder.loadPhaseLedger(workflowId))
}
