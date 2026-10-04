package skillbill.ports.goalrunner

import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalPlanningStatusState.BLOCKED
import skillbill.goalrunner.model.GoalPlanningStatusState.NOT_STARTED
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

interface SharedGoalPreplanRepository {
  fun checkpointSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint): GoalPlanningPreparationWriteResult

  fun replaceSharedPreplan(
    checkpoint: SharedGoalPreplanCheckpoint,
    expectedPayloadSha256: String,
    cascadePlanSubtaskIds: List<Int> = emptyList(),
  ): GoalPlanningPreparationWriteResult

  fun advanceSharedPreplanProvenance(
    identity: GoalPlanningIdentity,
    expectedPayloadSha256: String,
    provenance: GoalPlanningContractProvenance,
  ): GoalPlanningPreparationWriteResult

  fun cascadeSiblingPlansAfterSharedPreplanRefresh(
    parentGoalWorkflowId: String,
    cascadePlanSubtaskIds: List<Int>,
  ): List<Int>

  fun findSharedPreplan(expectedIdentity: GoalPlanningIdentity): SharedGoalPreplanLookupResult

  fun deleteSharedPreplan(
    identity: GoalPlanningIdentity,
    expectedPayloadSha256: String,
  ): GoalPlanningPreparationCountResult

  fun invalidateSharedPreplan(
    identity: GoalPlanningIdentity,
    expectedPayloadSha256: String,
  ): GoalPlanningPreparationCountResult

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

  fun checkpointSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint): GoalPlanningPreparationWriteResult

  fun replaceSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint): GoalPlanningPreparationWriteResult

  fun deleteSubtaskPlan(
    parentGoalWorkflowId: String,
    subtaskId: Int,
  ): Int

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
  fun markPrepared(record: GoalPlanningPreparationRecord): GoalPlanningPreparationWriteResult

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
