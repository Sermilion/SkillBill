package skillbill.ports.goalrunner

import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationCountResult
import skillbill.ports.goalrunner.model.GoalPlanningPreparationRecord
import skillbill.ports.goalrunner.model.GoalPlanningPreparationWriteResult
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.GoalSubtaskPlanListResult
import skillbill.ports.goalrunner.model.GoalSubtaskPlanLookupResult
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.goalrunner.model.SharedGoalPreplanLookupResult

abstract class GoalPlanningPreparationRepositoryDefaults : GoalPlanningPreparationRepository {
  override fun transferPlanningOwnership(
    source: GoalPlanningIdentity,
    target: GoalPlanningIdentity,
  ): GoalPlanningPreparationWriteResult = error("Planning ownership transfer is not configured in this test.")

  open override fun migrateSharedPreplan(
    source: SharedGoalPreplanCheckpoint,
    target: SharedGoalPreplanCheckpoint,
  ) = Unit

  open override fun migrateSubtaskPlan(
    source: GoalSubtaskPlanCheckpoint,
    target: GoalSubtaskPlanCheckpoint,
  ) = Unit

  open override fun listSubtaskPlansForMigration(identity: GoalPlanningIdentity): List<GoalSubtaskPlanCheckpoint> =
    emptyList()

  open override fun checkpointSharedPreplan(
    checkpoint: SharedGoalPreplanCheckpoint,
  ): GoalPlanningPreparationWriteResult = GoalPlanningPreparationWriteResult.Applied

  open override fun replaceSharedPreplan(
    checkpoint: SharedGoalPreplanCheckpoint,
    expectedPayloadSha256: String,
    cascadePlanSubtaskIds: List<Int>,
  ): GoalPlanningPreparationWriteResult = GoalPlanningPreparationWriteResult.Applied

  open override fun advanceSharedPreplanProvenance(
    identity: GoalPlanningIdentity,
    expectedPayloadSha256: String,
    provenance: GoalPlanningContractProvenance,
  ): GoalPlanningPreparationWriteResult = GoalPlanningPreparationWriteResult.Applied

  open override fun cascadeSiblingPlansAfterSharedPreplanRefresh(
    parentGoalWorkflowId: String,
    cascadePlanSubtaskIds: List<Int>,
  ): List<Int> = emptyList()

  open override fun findSharedPreplan(expectedIdentity: GoalPlanningIdentity): SharedGoalPreplanLookupResult =
    SharedGoalPreplanLookupResult.Found(null)

  open override fun deleteSharedPreplan(
    identity: GoalPlanningIdentity,
    expectedPayloadSha256: String,
  ): GoalPlanningPreparationCountResult = GoalPlanningPreparationCountResult.Applied(0)

  open override fun invalidateSharedPreplan(
    identity: GoalPlanningIdentity,
    expectedPayloadSha256: String,
  ): GoalPlanningPreparationCountResult = GoalPlanningPreparationCountResult.Applied(0)

  open override fun listPreparedPlanSubtaskIds(parentGoalWorkflowId: String): List<Int> = emptyList()

  open override fun hasPreparedSharedPreplan(parentGoalWorkflowId: String): Boolean = false

  open override fun sharedPreplanPayloadSha256(parentGoalWorkflowId: String): String? = null

  open override fun checkpointSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint): GoalPlanningPreparationWriteResult =
    GoalPlanningPreparationWriteResult.Applied

  open override fun replaceSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint): GoalPlanningPreparationWriteResult =
    GoalPlanningPreparationWriteResult.Applied

  open override fun deleteSubtaskPlan(
    parentGoalWorkflowId: String,
    subtaskId: Int,
  ): Int = 0

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

  open override fun markPrepared(record: GoalPlanningPreparationRecord): GoalPlanningPreparationWriteResult =
    GoalPlanningPreparationWriteResult.Applied

  open override fun deleteByGoal(parentGoalWorkflowId: String): Int = 0
}
