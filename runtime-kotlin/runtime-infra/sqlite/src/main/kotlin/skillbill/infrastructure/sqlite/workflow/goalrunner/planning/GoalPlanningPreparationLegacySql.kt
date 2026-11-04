package skillbill.infrastructure.sqlite.workflow.goalrunner.planning

import skillbill.error.shellcontent.invalidGoalPlanningPreparationSchemaError
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.goalrunner.model.GoalPlanningPreparationProvenance
import skillbill.ports.goalrunner.model.GoalPlanningPreparationRecord
import java.sql.Connection
import java.sql.ResultSet

internal class GoalPlanningPreparationLegacySql(
  private val connection: Connection,
) {
  fun insertOrReplace(record: GoalPlanningPreparationRecord) {
    requireLegacyHydrated(record)
    val preplanRepair = repairEvidenceJsonValue(record.preplanRepairEvidence)
    val planRepair = repairEvidenceJsonValue(record.planRepairEvidence)
    val hasPreplanRepair = connection.tableHasColumn("goal_planning_preparations", "preplan_repair_evidence_json")
    val hasPlanRepair = connection.tableHasColumn("goal_planning_preparations", "plan_repair_evidence_json")
    val repairColumns =
      buildString {
        if (hasPreplanRepair) append(", preplan_repair_evidence_json")
        if (hasPlanRepair) append(", plan_repair_evidence_json")
      }
    val repairPlaceholders =
      buildString {
        if (hasPreplanRepair) append(", ?")
        if (hasPlanRepair) append(", ?")
      }
    val createdAt = record.createdAt.takeIf(String::isNotBlank)
    val updatedAt = record.updatedAt.takeIf(String::isNotBlank)
    connection.prepareStatement(
      """
      INSERT OR REPLACE INTO goal_planning_preparations (
        parent_goal_workflow_id, normalized_issue_key, repository_identity, subtask_id, governed_sub_spec_path,
        preparation_status, contract_version, parent_spec_hash, sub_spec_hash, decomposition_manifest_hash,
        phase_output_contract_id, phase_output_contract_version, preplan_payload_json, plan_payload_json,
        created_at, updated_at$repairColumns
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP),
        COALESCE(?, CURRENT_TIMESTAMP)$repairPlaceholders)
      """.trimIndent(),
    ).use { statement ->
      var index = 1
      statement.setString(index++, record.parentGoalWorkflowId)
      statement.setString(index++, record.normalizedIssueKey)
      statement.setString(index++, record.repositoryIdentity)
      statement.setInt(index++, record.subtaskId)
      statement.setString(index++, record.governedSubSpecPath)
      statement.setString(index++, record.preparationStatus.wireValue)
      statement.setString(index++, record.contractVersion)
      statement.setString(index++, record.provenance.parentSpecHash)
      statement.setString(index++, record.provenance.subSpecHash)
      statement.setString(index++, record.provenance.decompositionManifestHash)
      statement.setString(index++, record.provenance.phaseOutputContractId)
      statement.setString(index++, record.provenance.phaseOutputContractVersion)
      statement.setString(index++, record.preplanPayload)
      statement.setString(index++, record.planPayload)
      statement.setString(index++, createdAt)
      statement.setString(index++, updatedAt)
      if (hasPreplanRepair) {
        if (preplanRepair == null) {
          statement.setObject(index++, null)
        } else {
          statement.setString(index++, preplanRepair)
        }
      }
      if (hasPlanRepair) {
        if (planRepair == null) {
          statement.setObject(index++, null)
        } else {
          statement.setString(index++, planRepair)
        }
      }
      statement.executeUpdate()
    }
  }

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

private fun requireLegacyHydrated(record: GoalPlanningPreparationRecord) {
  val label = "${record.parentGoalWorkflowId}#${record.subtaskId}"
  if (record.parentGoalWorkflowId.isBlank()) {
    throw invalidGoalPlanningPreparationSchemaError(
      label,
      "parent_goal_workflow_id",
      "parent_goal_workflow_id is required",
    )
  }
  if (record.governedSubSpecPath.isBlank()) {
    throw invalidGoalPlanningPreparationSchemaError(
      label,
      "governed_sub_spec_path",
      "governed_sub_spec_path is required",
    )
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
