package skillbill.infrastructure.sqlite.workflow.shared

import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.GoalPlanningStatusProjectionSql
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.translateSqlFailure
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.translateSqlFailureResult
import skillbill.infrastructure.sqlite.workflow.goalrunner.shared.GoalSharedPreplanSql
import skillbill.ports.goalrunner.SharedGoalPreplanRepository
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationCountResult
import skillbill.ports.goalrunner.model.GoalPlanningPreparationWriteResult
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.goalrunner.model.SharedGoalPreplanLookupResult

internal class SharedGoalPreplanStore(
  private val statusProjection: GoalPlanningStatusProjectionSql,
  private val sharedPreplan: GoalSharedPreplanSql,
) : SharedGoalPreplanRepository {
  override fun hasPreparedSharedPreplan(parentGoalWorkflowId: String): Boolean =
    translateSqlFailure(
      parentGoalWorkflowId,
      0,
    ) {
      statusProjection.hasPreparedSharedPreplan(parentGoalWorkflowId)
    }

  override fun listPreparedPlanSubtaskIds(parentGoalWorkflowId: String): List<Int> =
    translateSqlFailure(
      parentGoalWorkflowId,
      0,
    ) {
      statusProjection.listPreparedPlanSubtaskIds(parentGoalWorkflowId)
    }

  override fun checkpointSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint): GoalPlanningPreparationWriteResult =
    translateSqlFailure(checkpoint.identity.parentGoalWorkflowId, 0) {
      sharedPreplan.checkpointSharedPreplan(checkpoint)
    }

  override fun replaceSharedPreplan(
    checkpoint: SharedGoalPreplanCheckpoint,
    expectedPayloadSha256: String,
    cascadePlanSubtaskIds: List<Int>,
  ): GoalPlanningPreparationWriteResult =
    translateSqlFailure(checkpoint.identity.parentGoalWorkflowId, 0) {
      sharedPreplan.replaceSharedPreplan(checkpoint, expectedPayloadSha256, cascadePlanSubtaskIds)
    }

  override fun advanceSharedPreplanProvenance(
    identity: GoalPlanningIdentity,
    expectedPayloadSha256: String,
    provenance: GoalPlanningContractProvenance,
  ): GoalPlanningPreparationWriteResult =
    translateSqlFailure(identity.parentGoalWorkflowId, 0) {
      sharedPreplan.advanceSharedPreplanProvenance(identity, expectedPayloadSha256, provenance)
    }

  override fun cascadeSiblingPlansAfterSharedPreplanRefresh(
    parentGoalWorkflowId: String,
    cascadePlanSubtaskIds: List<Int>,
  ): List<Int> =
    translateSqlFailure(parentGoalWorkflowId, 0) {
      sharedPreplan.cascadeSiblingPlansAfterSharedPreplanRefresh(parentGoalWorkflowId, cascadePlanSubtaskIds)
    }

  override fun findSharedPreplan(expectedIdentity: GoalPlanningIdentity): SharedGoalPreplanLookupResult =
    translateSqlFailureResult(
      expectedIdentity.parentGoalWorkflowId,
      0,
      SharedGoalPreplanLookupResult::Conflicted,
    ) {
      sharedPreplan.findSharedPreplan(expectedIdentity)
    }

  override fun deleteSharedPreplan(
    identity: GoalPlanningIdentity,
    expectedPayloadSha256: String,
  ): GoalPlanningPreparationCountResult =
    translateSqlFailureResult(
      identity.parentGoalWorkflowId,
      0,
      GoalPlanningPreparationCountResult::Conflicted,
    ) {
      sharedPreplan.deleteSharedPreplan(identity, expectedPayloadSha256)
    }

  override fun invalidateSharedPreplan(
    identity: GoalPlanningIdentity,
    expectedPayloadSha256: String,
  ): GoalPlanningPreparationCountResult =
    translateSqlFailureResult(
      identity.parentGoalWorkflowId,
      0,
      GoalPlanningPreparationCountResult::Conflicted,
    ) {
      sharedPreplan.invalidateSharedPreplan(identity, expectedPayloadSha256)
    }

  override fun sharedPreplanPayloadSha256(parentGoalWorkflowId: String): String? =
    translateSqlFailure(parentGoalWorkflowId, 0) {
      sharedPreplan.sharedPreplanPayloadSha256(parentGoalWorkflowId)
    }
}
