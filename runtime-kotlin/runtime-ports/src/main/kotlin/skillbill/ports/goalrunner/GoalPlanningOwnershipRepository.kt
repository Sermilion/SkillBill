package skillbill.ports.goalrunner

import skillbill.ports.goalrunner.model.GoalPlanningOwnership
import skillbill.ports.goalrunner.model.GoalPlanningOwnershipWriteResult
import java.nio.file.Path

const val GOAL_PLANNING_OWNERSHIP_CONTRACT_VERSION: String = "0.1"

interface GoalPlanningOwnershipRepository {
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

  fun findByWorkflow(workflowId: String): GoalPlanningOwnership?

  fun findByIssue(
    repositoryIdentity: String,
    normalizedIssueKey: String,
  ): GoalPlanningOwnership?
}
