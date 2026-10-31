package skillbill.engine.featuretask.model.review

import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState

public sealed interface GoalSubtaskReviewPassReservation {
  public data object MissingState : GoalSubtaskReviewPassReservation
}

public data class GoalSubtaskReviewPassReserved(val state: GoalSubtaskReviewState) : GoalSubtaskReviewPassReservation

public data class GoalSubtaskReviewPassInFlight(val state: GoalSubtaskReviewState) : GoalSubtaskReviewPassReservation

public data class GoalSubtaskReviewPassCarryForward(
  val state: GoalSubtaskReviewState,
) : GoalSubtaskReviewPassReservation

public sealed interface GoalSubtaskReviewInputPreparation {
  public data object MissingState : GoalSubtaskReviewInputPreparation
}

public data class GoalSubtaskReviewInputBlocked(val reason: String) : GoalSubtaskReviewInputPreparation

public data class GoalSubtaskReviewInputReady(
  public val state: GoalSubtaskReviewState,
  public val input: GoalSubtaskReviewInput,
) : GoalSubtaskReviewInputPreparation
