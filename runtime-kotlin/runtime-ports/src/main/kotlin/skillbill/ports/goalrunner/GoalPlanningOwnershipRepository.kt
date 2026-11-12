package skillbill.ports.goalrunner

import skillbill.goalrunner.model.GoalPlanningCheckoutRecovery
import skillbill.ports.goalrunner.model.GoalPlanningOwnership
import skillbill.ports.goalrunner.model.GoalPlanningOwnershipWriteResult
import java.nio.file.Path

const val GOAL_PLANNING_OWNERSHIP_CONTRACT_VERSION: String = "0.1"

interface GoalPlanningOwnershipRepository : GoalPlanningCheckoutRecoveryRepository {
  fun reserve(ownership: GoalPlanningOwnership): GoalPlanningOwnershipWriteResult

  fun markInitialized(
    reservationId: String,
    path: Path,
    gitWorktreeIdentity: String,
  ): GoalPlanningOwnershipWriteResult

  fun transfer(
    reservationId: String,
    parentWorkflowId: String,
    path: Path,
  ): GoalPlanningOwnershipWriteResult

  fun supersede(
    reservationId: String,
    workflowId: String,
    path: Path,
  ): GoalPlanningOwnershipWriteResult

  fun reactivate(
    reservationId: String,
    workflowId: String,
    path: Path,
  ): GoalPlanningOwnershipWriteResult

  fun markLegacyBundlePreserved(
    workflowId: String,
    path: Path,
  ): Boolean

  fun findByWorkflow(workflowId: String): GoalPlanningOwnership?

  fun findByIssue(
    repositoryIdentity: String,
    normalizedIssueKey: String,
  ): GoalPlanningOwnership?
}

interface GoalPlanningCheckoutRecoveryRepository {
  fun checkoutRecovery(ownership: GoalPlanningOwnership): GoalPlanningCheckoutRecovery?

  fun isConsumedCheckoutRecovery(
    ownership: GoalPlanningOwnership,
    generation: String,
  ): Boolean

  fun advanceCheckoutRecovery(
    ownership: GoalPlanningOwnership,
    recovery: GoalPlanningCheckoutRecovery,
    nextState: GoalPlanningCheckoutRecovery.State,
    replacementGitIdentity: String? = null,
  ): Boolean
}
