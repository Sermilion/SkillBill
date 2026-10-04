package skillbill.infrastructure.sqlite.workflow.goalrunner.planning

import skillbill.error.shellcontent.invalidGoalPlanningPreparationSchemaError
import skillbill.ports.goalrunner.model.GoalPlanningPreparationProvenance
import skillbill.ports.goalrunner.model.GoalPlanningPreparationRecord
import java.sql.ResultSet

internal fun ResultSet.toPreparedRecord(): GoalPlanningPreparationRecord {
  val parentGoalWorkflowId = getString("parent_goal_workflow_id")
  val subtaskId = getInt("subtask_id")
  val label = "$parentGoalWorkflowId#$subtaskId"
  val contractVersion = requireColumn(this, label, "contract_version")
  if (contractVersion != "0.1") {
    throw invalidGoalPlanningPreparationSchemaError(
      sourceLabel = label,
      fieldPath = "contract_version",
      reason = incompatibleLoadedVersionReason(contractVersion),
    )
  }
  return GoalPlanningPreparationRecord(
    parentGoalWorkflowId = parentGoalWorkflowId,
    normalizedIssueKey = requireColumn(this, label, "normalized_issue_key"),
    repositoryIdentity = requireColumn(this, label, "repository_identity"),
    subtaskId = subtaskId,
    governedSubSpecPath = requireColumn(this, label, "governed_sub_spec_path"),
    preparationStatus = decodeState(label, getString("preparation_status")),
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
    contractVersion = contractVersion,
  )
}

internal fun selectRecordSql(): String =
  """
  ${selectRecordColumns()}
  FROM goal_planning_preparations
  WHERE parent_goal_workflow_id = ? AND subtask_id = ?
  """.trimIndent()

internal fun selectRecordColumns(): String =
  """
  SELECT parent_goal_workflow_id, normalized_issue_key, repository_identity, subtask_id,
         governed_sub_spec_path, preparation_status, contract_version, parent_spec_hash,
         sub_spec_hash, decomposition_manifest_hash, phase_output_contract_id,
         phase_output_contract_version, preplan_payload_json, plan_payload_json,
         preplan_repair_evidence_json, plan_repair_evidence_json,
         created_at, updated_at
  """.trimIndent()
