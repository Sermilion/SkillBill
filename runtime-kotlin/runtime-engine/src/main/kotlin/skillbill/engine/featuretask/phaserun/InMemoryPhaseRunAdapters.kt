package skillbill.engine.featuretask.phaserun

import skillbill.engine.featuretask.lifecycle.continuation.GoalContinuationStateRecordRequest
import skillbill.engine.featuretask.lifecycle.continuation.GoalReviewPassCompletionRequest
import skillbill.engine.featuretask.lifecycle.remediation.RemediationDegradationSignal
import skillbill.engine.featuretask.lifecycle.subtask.SubtaskCommitPreservationRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementEnvelope
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewInputPreparation
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewPassReservation
import skillbill.engine.featuretask.slot.state.PhaseRunCheckpoints
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.PhaseRunSettlements
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import java.nio.file.Path

internal object InMemoryPhaseRunGoal : PhaseRunGoal {
  override fun recordGoalContinuationState(request: GoalContinuationStateRecordRequest): Boolean = false

  override fun reserveGoalReviewPass(workflowId: String): GoalSubtaskReviewPassReservation =
    GoalSubtaskReviewPassReservation.MissingState

  override fun updateReviewState(
    workflowId: String,
    transform: (GoalSubtaskReviewState) -> GoalSubtaskReviewState,
  ): GoalSubtaskReviewState? = null

  override fun completeGoalReviewPass(request: GoalReviewPassCompletionRequest): GoalSubtaskReviewState? = null

  override fun buildGoalReviewInput(
    workflowId: String,
    gitOperations: WorkflowGitOperations,
    repoRoot: Path,
    scopedUntrackedExclusions: List<String>?,
    ownedPathspec: List<String>,
  ): GoalSubtaskReviewInputPreparation = GoalSubtaskReviewInputPreparation.MissingState

  override fun reviewState(workflowId: String): GoalSubtaskReviewState? = null

  override fun lastGoalReviewResult(workflowId: String): String? = null

  override fun appendRemediationRollbackDegradationEvidence(
    workflowId: String,
    signal: RemediationDegradationSignal,
  ) = Unit
}

internal object InMemoryPhaseRunSettlements : PhaseRunSettlements {
  override fun findEnvelope(
    workflowId: String,
    phaseId: String,
    attempt: Int,
  ): FeatureTaskPhaseSettlementEnvelope? = null

  override fun clear(
    workflowId: String,
    phaseId: String,
    attempt: Int,
  ): Boolean = false
}

internal object InMemoryPhaseRunCheckpoints : PhaseRunCheckpoints {
  override fun commitSubtask(request: SubtaskCommitPreservationRequest): WorkflowGitOperationResult =
    error(
      "An in-memory phase run keeps no durable state and cannot write a subtask checkpoint commit; " +
        "run the full feature-task workflow instead.",
    )
}
