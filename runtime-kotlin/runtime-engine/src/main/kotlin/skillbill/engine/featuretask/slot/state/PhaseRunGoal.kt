package skillbill.engine.featuretask.slot.state

import skillbill.engine.featuretask.lifecycle.continuation.GoalContinuationStateRecordRequest
import skillbill.engine.featuretask.lifecycle.continuation.GoalReviewPassCompletionRequest
import skillbill.engine.featuretask.lifecycle.remediation.RemediationDegradationSignal
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewInputPreparation
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewPassReservation
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import java.nio.file.Path

/**
 * The goal-continuation reads and writes one goal child run makes: the continuation state, the goal review pass and
 * its input, and the remediation degradation evidence. Every operation is keyed by the run's workflow id.
 */
internal interface PhaseRunGoal {
  /** Records the goal continuation state [request] carries. Returns false when the write did not apply. */
  fun recordGoalContinuationState(request: GoalContinuationStateRecordRequest): Boolean

  /** Reserves the goal review pass, or reports carry-forward, in-flight, or missing review state. */
  fun reserveGoalReviewPass(workflowId: String): GoalSubtaskReviewPassReservation

  /** Applies [transform] to the goal review state. Returns the updated state, or null when none is recorded. */
  fun updateReviewState(
    workflowId: String,
    transform: (GoalSubtaskReviewState) -> GoalSubtaskReviewState,
  ): GoalSubtaskReviewState?

  /** Completes the reserved goal review pass. Returns the updated state, or null when it could not persist. */
  fun completeGoalReviewPass(request: GoalReviewPassCompletionRequest): GoalSubtaskReviewState?

  /** Builds and persists the goal review input scoped to the owned and untracked paths. */
  fun buildGoalReviewInput(
    workflowId: String,
    gitOperations: WorkflowGitOperations,
    repoRoot: Path,
    scopedUntrackedExclusions: List<String>?,
    ownedPathspec: List<String>,
  ): GoalSubtaskReviewInputPreparation

  /** The goal review state of [workflowId], if recorded. */
  fun reviewState(workflowId: String): GoalSubtaskReviewState?

  /** The raw result of the last goal review pass of [workflowId], if recorded. */
  fun lastGoalReviewResult(workflowId: String): String?

  /** Appends the remediation rollback degradation [signal] to the goal review evidence. */
  fun appendRemediationRollbackDegradationEvidence(
    workflowId: String,
    signal: RemediationDegradationSignal,
  )
}
