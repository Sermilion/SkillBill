package skillbill.ports.goalrunner

import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationCountResult
import skillbill.ports.goalrunner.model.GoalSubtaskPlanListResult
import skillbill.ports.goalrunner.model.GoalSubtaskPlanLookupResult
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.model.SharedGoalPreplanLookupResult

abstract class GoalPlanningPreparationRepositoryDefaults : GoalPlanningPreparationRepository {
  open override fun findSharedPreplan(expectedIdentity: GoalPlanningIdentity): SharedGoalPreplanLookupResult =
    SharedGoalPreplanLookupResult.Found(null)

  open override fun listPreparedPlanSubtaskIds(parentGoalWorkflowId: String): List<Int> = emptyList()

  open override fun hasPreparedSharedPreplan(parentGoalWorkflowId: String): Boolean = false

  open override fun sharedPreplanPayloadSha256(parentGoalWorkflowId: String): String? = null

  open override fun findSubtaskPlan(
    expectedIdentity: GoalPlanningIdentity,
    subtaskId: Int,
    governedSubSpecPath: String,
  ): GoalSubtaskPlanLookupResult = GoalSubtaskPlanLookupResult.Found(null)

  open override fun listSubtaskPlansOrdered(
    expectedIdentity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
  ): GoalSubtaskPlanListResult = GoalSubtaskPlanListResult.Found(emptyList())

  open override fun preparedPlanCount(
    expectedIdentity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
  ): GoalPlanningPreparationCountResult = GoalPlanningPreparationCountResult.Applied(0)

  open override fun deleteByGoal(parentGoalWorkflowId: String): Int = 0
}
