package skillbill.ports.goalrunner

import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalPlanningStatusState.BLOCKED
import skillbill.goalrunner.model.GoalPlanningStatusState.NOT_STARTED
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationCountResult
import skillbill.ports.goalrunner.model.GoalSubtaskPlanListResult
import skillbill.ports.goalrunner.model.GoalSubtaskPlanLookupResult
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.model.SharedGoalPreplanLookupResult

interface GoalPlanningStatusReadRepository {
  fun listPreparedPlanSubtaskIds(parentGoalWorkflowId: String): List<Int>

  fun preparedPlanStatusCount(parentGoalWorkflowId: String): Int = listPreparedPlanSubtaskIds(parentGoalWorkflowId).size

  fun preparedPlanIdsForSubtasks(
    parentGoalWorkflowId: String,
    subtaskIds: Set<Int>,
  ): Set<Int> = listPreparedPlanSubtaskIds(parentGoalWorkflowId).filterTo(mutableSetOf(), subtaskIds::contains)
}

interface SharedGoalPreplanRepository : GoalPlanningStatusReadRepository {
  fun findSharedPreplan(expectedIdentity: GoalPlanningIdentity): SharedGoalPreplanLookupResult

  fun hasPreparedSharedPreplan(parentGoalWorkflowId: String): Boolean

  fun sharedPreplanPayloadSha256(parentGoalWorkflowId: String): String?
}

interface GoalSubtaskPlanRepository {
  fun boundedStatus(
    parentGoalWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int? = null,
    blockedReason: String? = null,
  ): GoalPlanningStatusSnapshot =
    GoalPlanningStatusSnapshot(
      state =
        if (blockedReason == null) {
          NOT_STARTED
        } else {
          BLOCKED
        },
      sharedPreplanPrepared = false,
      plannedSubtaskCount = 0,
      totalSubtaskCount = orderedSubtaskIds.size,
      currentPlanningSubtaskId = blockedSubtaskId ?: orderedSubtaskIds.firstOrNull(),
      reason = blockedReason ?: "Goal planning has not started.",
    )

  fun findSubtaskPlan(
    expectedIdentity: GoalPlanningIdentity,
    subtaskId: Int,
    governedSubSpecPath: String,
  ): GoalSubtaskPlanLookupResult

  fun listSubtaskPlansOrdered(
    expectedIdentity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
  ): GoalSubtaskPlanListResult

  fun preparedPlanCount(
    expectedIdentity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
  ): GoalPlanningPreparationCountResult
}

interface LegacyGoalPlanningPreparationRepository {
  fun deleteByGoal(parentGoalWorkflowId: String): Int
}

interface GoalPlanningPreparationRepository :
  SharedGoalPreplanRepository,
  GoalSubtaskPlanRepository,
  LegacyGoalPlanningPreparationRepository
