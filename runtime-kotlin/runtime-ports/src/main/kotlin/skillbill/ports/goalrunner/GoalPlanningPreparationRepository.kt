package skillbill.ports.goalrunner

import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalPlanningStatusState.BLOCKED
import skillbill.goalrunner.model.GoalPlanningStatusState.NOT_STARTED
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationRecord
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint

interface SharedGoalPreplanRepository {
  fun checkpointSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint)

  fun replaceSharedPreplan(
    checkpoint: SharedGoalPreplanCheckpoint,
    expectedPayloadSha256: String,
    cascadePlanSubtaskIds: List<Int> = emptyList(),
  )

  fun advanceSharedPreplanProvenance(
    identity: GoalPlanningIdentity,
    expectedPayloadSha256: String,
    provenance: GoalPlanningContractProvenance,
  )

  fun cascadeSiblingPlansAfterSharedPreplanRefresh(
    parentGoalWorkflowId: String,
    cascadePlanSubtaskIds: List<Int>,
  ): List<Int>

  fun findSharedPreplan(expectedIdentity: GoalPlanningIdentity): SharedGoalPreplanCheckpoint?

  fun deleteSharedPreplan(
    identity: GoalPlanningIdentity,
    expectedPayloadSha256: String,
  ): Int

  fun invalidateSharedPreplan(
    identity: GoalPlanningIdentity,
    expectedPayloadSha256: String,
  ): Int

  fun listPreparedPlanSubtaskIds(parentGoalWorkflowId: String): List<Int>

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

  fun checkpointSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint)

  fun replaceSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint)

  fun deleteSubtaskPlan(
    parentGoalWorkflowId: String,
    subtaskId: Int,
  ): Int

  fun findSubtaskPlan(
    expectedIdentity: GoalPlanningIdentity,
    subtaskId: Int,
    governedSubSpecPath: String,
  ): GoalSubtaskPlanCheckpoint?

  fun listSubtaskPlansOrdered(
    expectedIdentity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
  ): List<GoalSubtaskPlanCheckpoint>

  fun preparedPlanCount(
    expectedIdentity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
  ): Int = listSubtaskPlansOrdered(expectedIdentity, orderedDescriptors).size
}

interface LegacyGoalPlanningPreparationRepository {
  fun markPrepared(record: GoalPlanningPreparationRecord)

  fun deleteByGoal(parentGoalWorkflowId: String): Int
}

interface GoalPlanningPreparationRepository :
  SharedGoalPreplanRepository,
  GoalSubtaskPlanRepository,
  LegacyGoalPlanningPreparationRepository {
  fun migrateSharedPreplan(
    source: SharedGoalPreplanCheckpoint,
    target: SharedGoalPreplanCheckpoint,
  )

  fun listSubtaskPlansForMigration(identity: GoalPlanningIdentity): List<GoalSubtaskPlanCheckpoint>

  fun migrateSubtaskPlan(
    source: GoalSubtaskPlanCheckpoint,
    target: GoalSubtaskPlanCheckpoint,
  )
}
