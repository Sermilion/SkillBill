package skillbill.ports.goalrunner

import skillbill.goalrunner.model.GoalPlanningCheckoutRecovery
import skillbill.ports.goalrunner.model.GoalPlanningOwnership
import skillbill.ports.goalrunner.model.GoalPlanningOwnershipWriteResult
import java.nio.file.Path

object EmptyGoalPlanningOwnershipRepository : GoalPlanningOwnershipRepository {
  override fun checkoutRecovery(ownership: GoalPlanningOwnership): GoalPlanningCheckoutRecovery? = null

  override fun isConsumedCheckoutRecovery(
    ownership: GoalPlanningOwnership,
    generation: String,
  ): Boolean = false

  override fun advanceCheckoutRecovery(
    ownership: GoalPlanningOwnership,
    recovery: GoalPlanningCheckoutRecovery,
    nextState: GoalPlanningCheckoutRecovery.State,
    replacementGitIdentity: String?,
  ): Boolean = false

  override fun reserve(ownership: GoalPlanningOwnership): GoalPlanningOwnershipWriteResult =
    GoalPlanningOwnershipWriteResult.Conflict(null)

  override fun markInitialized(
    reservationId: String,
    path: Path,
    gitWorktreeIdentity: String,
  ): GoalPlanningOwnershipWriteResult = GoalPlanningOwnershipWriteResult.Missing(path)

  override fun transfer(
    reservationId: String,
    parentWorkflowId: String,
    path: Path,
  ): GoalPlanningOwnershipWriteResult = GoalPlanningOwnershipWriteResult.Missing(path)

  override fun supersede(
    reservationId: String,
    workflowId: String,
    path: Path,
  ): GoalPlanningOwnershipWriteResult = GoalPlanningOwnershipWriteResult.Missing(path)

  override fun reactivate(
    reservationId: String,
    workflowId: String,
    path: Path,
  ): GoalPlanningOwnershipWriteResult = GoalPlanningOwnershipWriteResult.Missing(path)

  override fun markLegacyBundlePreserved(
    workflowId: String,
    path: Path,
  ): Boolean = false

  override fun findByWorkflow(workflowId: String): GoalPlanningOwnership? = null

  override fun findByIssue(
    repositoryIdentity: String,
    normalizedIssueKey: String,
  ): GoalPlanningOwnership? = null
}
