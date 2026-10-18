package skillbill.infrastructure.sqlite

import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import java.sql.Connection

fun Connection.seedLegacySharedPreplan(checkpoint: SharedGoalPreplanCheckpoint) {
  prepareStatement(
    """INSERT INTO goal_shared_preplans (parent_goal_workflow_id, normalized_issue_key, repository_identity,
    preparation_status, contract_version, parent_spec_hash, decomposition_manifest_hash, planning_contract_id,
    planning_contract_version, phase_output_contract_id, phase_output_contract_version, payload_sha256,
    preplan_payload_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
  ).use { statement ->
    listOf(
      checkpoint.identity.parentGoalWorkflowId,
      checkpoint.identity.normalizedIssueKey,
      checkpoint.identity.repositoryIdentity,
      checkpoint.preparationStatus.wireValue,
      checkpoint.contractVersion,
      checkpoint.provenance.parentSpecHash,
      checkpoint.provenance.decompositionManifestHash,
      checkpoint.provenance.planningContractId,
      checkpoint.provenance.planningContractVersion,
      checkpoint.provenance.phaseOutputContractId,
      checkpoint.provenance.phaseOutputContractVersion,
      checkpoint.payloadSha256,
      checkpoint.preplanPayload,
    ).forEachIndexed { index, value -> statement.setString(index + 1, value) }
    statement.executeUpdate()
  }
}

fun Connection.seedLegacySubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) {
  prepareStatement(
    """INSERT INTO goal_subtask_plans (parent_goal_workflow_id, normalized_issue_key, repository_identity,
    subtask_id, manifest_order, governed_sub_spec_path, sub_spec_hash, preparation_status, contract_version,
    parent_spec_hash, decomposition_manifest_hash, planning_contract_id, planning_contract_version,
    phase_output_contract_id, phase_output_contract_version, payload_sha256, plan_payload_json)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
  ).use { statement ->
    listOf(
      checkpoint.identity.parentGoalWorkflowId,
      checkpoint.identity.normalizedIssueKey,
      checkpoint.identity.repositoryIdentity,
      checkpoint.subtaskId,
      checkpoint.manifestOrder,
      checkpoint.governedSubSpecPath,
      checkpoint.subSpecHash,
      checkpoint.preparationStatus.wireValue,
      checkpoint.contractVersion,
      checkpoint.provenance.parentSpecHash,
      checkpoint.provenance.decompositionManifestHash,
      checkpoint.provenance.planningContractId,
      checkpoint.provenance.planningContractVersion,
      checkpoint.provenance.phaseOutputContractId,
      checkpoint.provenance.phaseOutputContractVersion,
      checkpoint.payloadSha256,
      checkpoint.planPayload,
    ).forEachIndexed { index, value -> statement.setObject(index + 1, value) }
    statement.executeUpdate()
  }
}
