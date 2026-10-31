package skillbill.engine.featuretask.persist

import skillbill.engine.featuretask.model.execution.GoalChildHistoryDraftWriter
import skillbill.goalrunner.model.history.GoalChildHistoryDraft
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.persistence.UnitOfWork

public data class GoalRunnerRestoredChildReference(
  public val childWorkflowId: String,
  public val acceptance: GoalRunnerOutOfBandAcceptance,
)

public interface GoalRunnerGoalChildHistoryDraftStore : GoalChildHistoryDraftWriter {
  public fun goalChildHistoryDrafts(parentWorkflowId: String): List<GoalChildHistoryDraft>

  public fun hasRequiredGoalChildHistoryDraft(
    parentWorkflowId: String,
    subtaskId: Int,
    childWorkflowId: String,
  ): Boolean

  public fun hasRequiredGoalChildHistoryDraft(
    unitOfWork: UnitOfWork,
    parentWorkflowId: String,
    subtaskId: Int,
    childWorkflowId: String,
  ): Boolean = hasRequiredGoalChildHistoryDraft(parentWorkflowId, subtaskId, childWorkflowId)
}
