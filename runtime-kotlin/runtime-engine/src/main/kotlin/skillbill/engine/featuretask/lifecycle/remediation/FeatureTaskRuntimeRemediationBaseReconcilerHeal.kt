package skillbill.engine.featuretask.lifecycle.remediation

import skillbill.engine.featuretask.lifecycle.continuation.reviewStateFromArtifacts
import skillbill.engine.featuretask.model.subtask.PersistHealedRemediationBaseRequest
import skillbill.engine.featuretask.model.subtask.ResolvedReviewFixCheckpoint
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputFailureReason
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import java.nio.file.Path

internal fun remediationBaseHealReason(
  stored: String?,
  target: String,
  latestRemediationResolved: ResolvedReviewFixCheckpoint?,
): String =
  when {
    stored == null -> "committed_but_unrecorded"
    latestRemediationResolved != null && latestRemediationResolved.sha == target -> "committed_but_unrecorded"
    else -> "recorded_but_superseded"
  }

internal fun FeatureTaskRuntimeRemediationBaseReconciler.persistHealedRemediationBaseState(
  request: PersistHealedRemediationBaseRequest,
): GoalSubtaskReviewState? {
  val headSha = request.gitOperations.headCommitSha(request.repoRoot).value.orEmpty().trim()
  return database.transaction { unitOfWork ->
    val record =
      unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, request.workflowId)
        ?: return@transaction null
    val artifacts = record.artifacts
    val latest = reviewStateFromArtifacts(artifacts) ?: return@transaction null
    if (latest.remediationBaseSha == request.target) return@transaction latest
    val updated = latest.copy(remediationBaseSha = request.target)
    val evidenceEntry =
      remediationBaseRecoveryEvidenceEntry(
        RemediationBaseRecovery(
          originalSha = request.stored,
          replacementSha = request.target,
          reason = request.reason,
          goalBranch = request.continuation.goalBranch,
          headSha = headSha,
        ),
      )
    val priorEvidence =
      (DurableWorkflowArtifactFamily.GOAL_REVIEW_BASE_RECOVERIES.value(artifacts) as? List<*>).orEmpty()
    patcher.save(
      record,
      unitOfWork.workflowStates,
      mapOf(
        DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_STATE.entry(updated.toPersistenceWire()),
        DurableWorkflowArtifactFamily.GOAL_REVIEW_BASE_RECOVERIES.entry(priorEvidence + evidenceEntry),
      ),
    )
    updated
  }
}

internal fun recoveredRemediationBaseSha(
  stored: String?,
  state: GoalSubtaskReviewState,
  continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
): String? {
  if (stored == null) return null
  val request =
    runCatching {
      GoalSubtaskReviewBaselineRecoveryRequest(
        unreachableSha = stored,
        failureReason = GoalSubtaskReviewInputFailureReason.BASE_NOT_ANCESTOR,
        baselineUntrackedPaths = state.baselineUntrackedPaths,
      )
    }.getOrNull() ?: return null
  val recovered = gitOperations.recoverGoalSubtaskReviewBaseline(repoRoot, request, continuation.goalBranch)
  if (recovered.status != WorkflowGitOperationStatus.OK) return null
  return recovered.baseline?.reviewBaseSha
}
