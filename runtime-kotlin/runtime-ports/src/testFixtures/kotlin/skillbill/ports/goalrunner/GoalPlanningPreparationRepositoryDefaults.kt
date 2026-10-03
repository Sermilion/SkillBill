package skillbill.ports.goalrunner

import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationRecord
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint

abstract class GoalPlanningPreparationRepositoryDefaults : GoalPlanningPreparationRepository {
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

  open override fun checkpointSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint) = Unit

  open override fun replaceSharedPreplan(
    checkpoint: SharedGoalPreplanCheckpoint,
    expectedPayloadSha256: String,
    cascadePlanSubtaskIds: List<Int>,
  ) = Unit

  open override fun advanceSharedPreplanProvenance(
    identity: GoalPlanningIdentity,
    expectedPayloadSha256: String,
    provenance: GoalPlanningContractProvenance,
  ) = Unit

  open override fun cascadeSiblingPlansAfterSharedPreplanRefresh(
    parentGoalWorkflowId: String,
    cascadePlanSubtaskIds: List<Int>,
  ): List<Int> = emptyList()

  open override fun findSharedPreplan(expectedIdentity: GoalPlanningIdentity): SharedGoalPreplanCheckpoint? = null

  open override fun deleteSharedPreplan(
    identity: GoalPlanningIdentity,
    expectedPayloadSha256: String,
  ): Int = 0

  open override fun invalidateSharedPreplan(
    identity: GoalPlanningIdentity,
    expectedPayloadSha256: String,
  ): Int = 0

  open override fun listPreparedPlanSubtaskIds(parentGoalWorkflowId: String): List<Int> = emptyList()

  open override fun hasPreparedSharedPreplan(parentGoalWorkflowId: String): Boolean = false

  open override fun sharedPreplanPayloadSha256(parentGoalWorkflowId: String): String? = null

  open override fun checkpointSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) = Unit

  open override fun replaceSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) = Unit

  open override fun deleteSubtaskPlan(
    parentGoalWorkflowId: String,
    subtaskId: Int,
  ): Int = 0

  open override fun findSubtaskPlan(
    expectedIdentity: GoalPlanningIdentity,
    subtaskId: Int,
    governedSubSpecPath: String,
  ): GoalSubtaskPlanCheckpoint? = null

  open override fun listSubtaskPlansOrdered(
    expectedIdentity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
  ): List<GoalSubtaskPlanCheckpoint> = emptyList()

  open override fun markPrepared(record: GoalPlanningPreparationRecord) = Unit

  open override fun deleteByGoal(parentGoalWorkflowId: String): Int = 0
}
