package skillbill.infrastructure.sqlite.workflow.goalrunner.planning

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.FeatureTaskRuntimeMigrationFailureCode
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.workflow.goalrunner.shared.GoalSharedPreplanSql
import skillbill.infrastructure.sqlite.workflow.goalrunner.subtask.GoalSubtaskPlanSql
import skillbill.infrastructure.sqlite.workflow.goalrunner.subtask.GoalSubtaskPlanStore
import skillbill.infrastructure.sqlite.workflow.shared.SharedGoalPreplanStore
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.ports.goalrunner.GoalSubtaskPlanRepository
import skillbill.ports.goalrunner.SharedGoalPreplanRepository
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationRecord
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import java.sql.Connection
import java.sql.SQLException

internal class GoalPlanningPreparationStore(
  private val connection: Connection,
  diagnostics: RuntimeDiagnostics,
  private val transactionActive: Boolean = false,
) : GoalPlanningPreparationRepository,
  SharedGoalPreplanRepository by SharedGoalPreplanStore(
    GoalPlanningStatusProjectionSql(connection),
    GoalSharedPreplanSql(connection, diagnostics),
  ),
  GoalSubtaskPlanRepository by GoalSubtaskPlanStore(
    GoalPlanningStatusProjectionSql(connection),
    GoalSubtaskPlanSql(connection, GoalSharedPreplanSql(connection, diagnostics), diagnostics),
  ) {
  private val sharedPreplan = GoalSharedPreplanSql(connection, diagnostics)
  private val subtaskPlan = GoalSubtaskPlanSql(connection, sharedPreplan, diagnostics)
  internal val preparationRecord = GoalPlanningPreparationRecordSql(connection, diagnostics)

  override fun listSubtaskPlansForMigration(identity: GoalPlanningIdentity): List<GoalSubtaskPlanCheckpoint> =
    subtaskPlan.listSubtaskPlansForMigration(identity)

  override fun migrateSharedPreplan(
    source: SharedGoalPreplanCheckpoint,
    target: SharedGoalPreplanCheckpoint,
  ) {
    requireMigrationTransaction()
    if (target.provenance.copy(phaseOutputContractVersion = source.provenance.phaseOutputContractVersion) !=
      source.provenance || sharedPreplan.findSharedPreplan(source.identity) != source ||
      target.copy(
        provenance = source.provenance,
        payloadSha256 = source.payloadSha256,
        preplanPayload = source.preplanPayload,
      ) != source
    ) {
      staleMigration()
    }
    try {
      connection.prepareStatement(
        """UPDATE goal_shared_preplans SET phase_output_contract_version = ?, payload_sha256 = ?,
        preplan_payload_json = ? WHERE parent_goal_workflow_id = ? AND normalized_issue_key = ?
        AND repository_identity = ? AND preparation_status = ? AND contract_version = ?
        AND parent_spec_hash = ? AND decomposition_manifest_hash = ? AND planning_contract_id = ?
        AND planning_contract_version = ? AND phase_output_contract_id = ?
        AND phase_output_contract_version = ? AND payload_sha256 = ? AND preplan_payload_json = ?
        AND repair_evidence_json IS ? AND created_at = ?""",
      ).use {
        it.bindAll(
          target.provenance.phaseOutputContractVersion,
          target.payloadSha256,
          target.preplanPayload,
          source.identity.parentGoalWorkflowId,
          source.identity.normalizedIssueKey,
          source.identity.repositoryIdentity,
          source.preparationStatus.wireValue,
          source.contractVersion,
          source.provenance.parentSpecHash,
          source.provenance.decompositionManifestHash,
          source.provenance.planningContractId,
          source.provenance.planningContractVersion,
          source.provenance.phaseOutputContractId,
          source.provenance.phaseOutputContractVersion,
          source.payloadSha256,
          source.preplanPayload,
          source.repairEvidenceJson(),
          source.createdAt,
        )
        if (it.executeUpdate() != 1) staleMigration()
      }
    } catch (error: SQLException) {
      writeFailure(error)
    }
  }

  override fun migrateSubtaskPlan(
    source: GoalSubtaskPlanCheckpoint,
    target: GoalSubtaskPlanCheckpoint,
  ) {
    requireMigrationTransaction()
    if (target.provenance.copy(phaseOutputContractVersion = source.provenance.phaseOutputContractVersion) !=
      source.provenance || subtaskPlan.findSubtaskPlan(
        source.identity,
        source.subtaskId,
        source.governedSubSpecPath,
      ) != source ||
      target.copy(
        provenance = source.provenance,
        payloadSha256 = source.payloadSha256,
        planPayload = source.planPayload,
      ) != source
    ) {
      staleMigration()
    }
    try {
      connection.prepareStatement(
        """UPDATE goal_subtask_plans SET phase_output_contract_version = ?, payload_sha256 = ?,
        plan_payload_json = ? WHERE parent_goal_workflow_id = ? AND normalized_issue_key = ?
        AND repository_identity = ? AND subtask_id = ? AND manifest_order = ? AND governed_sub_spec_path = ?
        AND sub_spec_hash = ? AND preparation_status = ? AND contract_version = ?
        AND parent_spec_hash = ? AND decomposition_manifest_hash = ? AND planning_contract_id = ?
        AND planning_contract_version = ? AND phase_output_contract_id = ?
        AND phase_output_contract_version = ? AND payload_sha256 = ? AND plan_payload_json = ?
        AND repair_evidence_json IS ? AND created_at = ?""",
      ).use {
        it.bindAll(
          target.provenance.phaseOutputContractVersion,
          target.payloadSha256,
          target.planPayload,
          source.identity.parentGoalWorkflowId,
          source.identity.normalizedIssueKey,
          source.identity.repositoryIdentity,
          source.subtaskId,
          source.manifestOrder,
          source.governedSubSpecPath,
          source.subSpecHash,
          source.preparationStatus.wireValue,
          source.contractVersion,
          source.provenance.parentSpecHash,
          source.provenance.decompositionManifestHash,
          source.provenance.planningContractId,
          source.provenance.planningContractVersion,
          source.provenance.phaseOutputContractId,
          source.provenance.phaseOutputContractVersion,
          source.payloadSha256,
          source.planPayload,
          source.repairEvidenceJson(),
          source.createdAt,
        )
        if (it.executeUpdate() != 1) staleMigration()
      }
    } catch (error: SQLException) {
      writeFailure(error)
    }
  }

  private fun requireMigrationTransaction() {
    if (!transactionActive) {
      throw SkillBillRuntimeException(
        FeatureTaskRuntimeMigrationFailureCode.WRITE_FAILURE,
        "Planning migration requires its owning immediate transaction. Durable state was preserved.",
      )
    }
  }

  private fun staleMigration(): Nothing =
    throw SkillBillRuntimeException(
      FeatureTaskRuntimeMigrationFailureCode.STALE_SOURCE,
      "Planning migration source changed. Retry admission without resetting durable state.",
    )

  private fun writeFailure(cause: SQLException): Nothing =
    throw SkillBillRuntimeException(
      FeatureTaskRuntimeMigrationFailureCode.WRITE_FAILURE,
      "Planning migration publication failed. Roll back the owning transaction and retry.",
      cause,
    )

  override fun markPrepared(record: GoalPlanningPreparationRecord) {
    preparationRecord.markPrepared(record)
  }

  override fun deleteByGoal(parentGoalWorkflowId: String): Int {
    val plans = subtaskPlan.deleteAllByGoal(parentGoalWorkflowId)
    val shared = sharedPreplan.deleteAllByGoal(parentGoalWorkflowId)
    return plans + shared + preparationRecord.deletePreparedByGoal(parentGoalWorkflowId)
  }
}
