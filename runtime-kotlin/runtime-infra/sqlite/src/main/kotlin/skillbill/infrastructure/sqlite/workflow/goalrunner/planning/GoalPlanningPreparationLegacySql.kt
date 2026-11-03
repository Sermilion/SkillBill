package skillbill.infrastructure.sqlite.workflow.goalrunner.planning

import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.goalrunner.model.GoalPlanningPreparationProvenance
import skillbill.ports.goalrunner.model.GoalPlanningPreparationRecord
import java.sql.Connection
import java.sql.ResultSet

internal class GoalPlanningPreparationLegacySql(
  private val connection: Connection,
) {
  fun listForExport(parentGoalWorkflowId: String): List<GoalPlanningPreparationRecord> =
    connection.prepareStatement(
      "SELECT * FROM goal_planning_preparations WHERE parent_goal_workflow_id = ? ORDER BY subtask_id",
    ).use { statement ->
      statement.bindAll(parentGoalWorkflowId)
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) {
            add(rows.toLegacyExportRecord())
          }
        }
      }
    }
}

private fun ResultSet.toLegacyExportRecord(): GoalPlanningPreparationRecord {
  val parentGoalWorkflowId =
    requireColumn(this, "goal_planning_preparations", "parent_goal_workflow_id")
  val subtaskId = requirePositiveInt(this, parentGoalWorkflowId, "subtask_id")
  val label = "$parentGoalWorkflowId#$subtaskId"
  return GoalPlanningPreparationRecord(
    parentGoalWorkflowId = parentGoalWorkflowId,
    normalizedIssueKey = requireColumn(this, label, "normalized_issue_key"),
    repositoryIdentity = requireColumn(this, label, "repository_identity"),
    subtaskId = subtaskId,
    governedSubSpecPath = requireColumn(this, label, "governed_sub_spec_path"),
    preparationStatus = decodeState(label, requireColumn(this, label, "preparation_status")),
    provenance =
      GoalPlanningPreparationProvenance(
        parentSpecHash = requireColumn(this, label, "parent_spec_hash"),
        subSpecHash = requireColumn(this, label, "sub_spec_hash"),
        decompositionManifestHash = requireColumn(this, label, "decomposition_manifest_hash"),
        phaseOutputContractId = requireColumn(this, label, "phase_output_contract_id"),
        phaseOutputContractVersion = requireColumn(this, label, "phase_output_contract_version"),
      ),
    preplanPayload = requireColumn(this, label, "preplan_payload_json"),
    planPayload = requireColumn(this, label, "plan_payload_json"),
    preplanRepairEvidence = optionalRepairEvidence(this, label, "preplan_repair_evidence_json"),
    planRepairEvidence = optionalRepairEvidence(this, label, "plan_repair_evidence_json"),
    createdAt = getString("created_at").orEmpty(),
    updatedAt = getString("updated_at").orEmpty(),
    contractVersion = requireColumn(this, label, "contract_version"),
  )
}
