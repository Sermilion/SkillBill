package skillbill.engine.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerOperatorDecisionRequest
import skillbill.engine.goalrunner.model.GoalRunnerOperatorDecisionResult
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.recovery.recommendedDurableChildRecoveryCommand
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus

@Inject
class GoalOperatorDecisionService(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
) {
  fun record(request: GoalRunnerOperatorDecisionRequest): GoalRunnerOperatorDecisionResult {
    when (val resolved = resolveChildWorkflow(request)) {
      is ResolvedChildWorkflow.Rejected -> return resolved.result
      is ResolvedChildWorkflow.Ok -> {
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
