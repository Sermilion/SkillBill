package skillbill.engine.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerOperatorDecisionRequest
import skillbill.engine.goalrunner.model.GoalRunnerOperatorDecisionResult
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.recovery.recommendedDurableChildRecoveryCommand
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.model.goalreview.GoalSubtaskOperatorDecision
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangePause

@Inject
class GoalOperatorDecisionService(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val phaseRecorder: FeatureTaskRuntimePhaseRecorder,
) {
  fun record(request: GoalRunnerOperatorDecisionRequest): GoalRunnerOperatorDecisionResult {
    when (val resolved = resolveChildWorkflow(request)) {
      is ResolvedChildWorkflow.Rejected -> return resolved.result
      is ResolvedChildWorkflow.Ok -> {
        val noChangePause = phaseRecorder.loadNoChangePause(resolved.childWorkflowId)
        if (noChangePause != null && noChangePause.operatorDecision == null) {
          return recordNoChangeDecision(request, resolved, noChangePause)
        }
        val childProgress = outcomeStore.progress(resolved.childWorkflowId)
        return GoalRunnerOperatorDecisionResult.Rejected(
          request.issueKey,
          "Operator decisions over review remediation are removed; " +
            "the run advances to validate after one implement_fix round. " +
            "Recover with: '${recommendedDurableChildRecoveryCommand(
              request.issueKey,
              request.subtaskId,
              resolved.subtaskStatus,
              childProgress,
            )}'.",
        )
      }
    }
  }

  private fun recordNoChangeDecision(
    request: GoalRunnerOperatorDecisionRequest,
    resolved: ResolvedChildWorkflow.Ok,
    noChangePause: FeatureTaskRuntimeNoChangePause,
  ): GoalRunnerOperatorDecisionResult {
    val instructions = request.instructions?.trim()
    val rejectReason =
      when {
        request.decision == GoalSubtaskOperatorDecision.RETRY_FIX && instructions.isNullOrBlank() ->
          "retry_fix on a no-change pause needs non-blank operator instructions; pass --instructions \"<text>\"."
        request.decision != GoalSubtaskOperatorDecision.RETRY_FIX && request.instructions != null ->
          "--instructions applies only to retry_fix; ${request.decision.wireValue} takes no instructions."
        else -> null
      }
    if (rejectReason != null) {
      return GoalRunnerOperatorDecisionResult.Rejected(request.issueKey, rejectReason)
    }
    phaseRecorder.persistNoChangePause(
      resolved.childWorkflowId,
      noChangePause.copy(operatorDecision = request.decision.wireValue, operatorInstructions = instructions),
    )
    return GoalRunnerOperatorDecisionResult.Recorded(
      issueKey = request.issueKey,
      parentWorkflowId = resolved.parentWorkflowId,
      subtaskId = request.subtaskId,
      workflowId = resolved.childWorkflowId,
      decision = request.decision.wireValue,
    )
  }

  private fun resolveChildWorkflow(request: GoalRunnerOperatorDecisionRequest): ResolvedChildWorkflow {
    val loaded = manifestStore.loadByIssueKey(request.issueKey, request.repoRoot)
    val subtask = loaded?.manifest?.subtasks?.firstOrNull { it.id == request.subtaskId }
    val workflowId = subtask?.workflowId?.takeIf(String::isNotBlank)
    val rejectReason =
      when {
        loaded == null ->
          "No prepared goal exists for '${request.issueKey}'."
        subtask == null ->
          "Subtask ${request.subtaskId} is not part of this goal."
        workflowId == null ->
          "Subtask ${request.subtaskId} has no child workflow to record an operator decision against."
        else -> null
      }
    return if (rejectReason != null) {
      ResolvedChildWorkflow.Rejected(GoalRunnerOperatorDecisionResult.Rejected(request.issueKey, rejectReason))
    } else {
      ResolvedChildWorkflow.Ok(
        parentWorkflowId = requireNotNull(loaded).parentWorkflowId,
        childWorkflowId = requireNotNull(workflowId),
        subtaskStatus = requireNotNull(subtask).status.decompositionStatus(),
      )
    }
  }

  private sealed class ResolvedChildWorkflow {
    data class Rejected(val result: GoalRunnerOperatorDecisionResult.Rejected) : ResolvedChildWorkflow()

    data class Ok(
      val parentWorkflowId: String,
      val childWorkflowId: String,
      val subtaskStatus: DecompositionStatus?,
    ) : ResolvedChildWorkflow()
  }
}
