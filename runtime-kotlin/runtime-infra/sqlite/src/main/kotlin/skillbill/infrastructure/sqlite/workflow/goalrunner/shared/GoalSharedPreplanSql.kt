package skillbill.infrastructure.sqlite.workflow.goalrunner.shared
import skillbill.error.shellcontent.invalidGoalPlanningPreparationSchemaError
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.INVALIDATED_SHARED_PREPLAN_PAYLOAD
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.decodeState
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.hydratedEnvelopeFailure
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.hydratedProvenanceFailure
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.normalizedIdentityFailure
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.optionalRepairEvidence
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.rejectLegacy
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.repairEvidenceJsonValue
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.requireColumn
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.requireParentGoalWorkflowId
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.tableHasColumn
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationConflict
import skillbill.ports.goalrunner.model.GoalPlanningPreparationState
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.goalrunner.model.SharedGoalPreplanLookupResult
import skillbill.text.sha256HexUtf8
import java.sql.Connection
import java.sql.ResultSet

internal class GoalSharedPreplanSql(
  private val connection: Connection,
) {
  fun findSharedPreplan(expectedIdentity: GoalPlanningIdentity): SharedGoalPreplanLookupResult {
    connection.rejectLegacy(expectedIdentity.parentGoalWorkflowId)?.let {
      return SharedGoalPreplanLookupResult.Conflicted(it)
    }
    return connection.prepareStatement(
      "SELECT * FROM goal_shared_preplans WHERE parent_goal_workflow_id = ?",
    ).use { s ->
      s.bindAll(expectedIdentity.parentGoalWorkflowId)
      s.executeQuery().use { r ->
        if (!r.next()) {
          SharedGoalPreplanLookupResult.Found(null)
        } else {
          r.toShared(expectedIdentity)
        }
      }
    }
  }

  fun sharedPreplanPayloadSha256(parentGoalWorkflowId: String): String? {
    requireParentGoalWorkflowId(parentGoalWorkflowId)
    return connection.prepareStatement(
      "SELECT payload_sha256, preplan_payload_json FROM goal_shared_preplans WHERE parent_goal_workflow_id = ?",
    ).use { statement ->
      statement.bindAll(parentGoalWorkflowId)
      statement.executeQuery().use { result ->
        if (!result.next()) {
          null
        } else if (result.getString(2) == INVALIDATED_SHARED_PREPLAN_PAYLOAD) {
          null
        } else {
          result.getString(1)
        }
      }
    }
  }

  fun deleteAllByGoal(parentGoalWorkflowId: String): Int =
    connection.prepareStatement("DELETE FROM goal_shared_preplans WHERE parent_goal_workflow_id = ?").use {
      it.bindAll(parentGoalWorkflowId)
      it.executeUpdate()
    }

  fun insertOrReplace(checkpoint: SharedGoalPreplanCheckpoint) {
    requireHydratedSharedPreplan(checkpoint)
    if (sha256HexUtf8(checkpoint.preplanPayload) != checkpoint.payloadSha256) {
      throw invalidGoalPlanningPreparationSchemaError(
        checkpoint.identity.parentGoalWorkflowId,
        "payload_sha256",
        "payload_sha256 does not match the exact UTF-8 payload bytes",
      )
    }
    val repairJson = repairEvidenceJsonValue(checkpoint.repairEvidence)
    val hasRepairColumn = connection.tableHasColumn("goal_shared_preplans", "repair_evidence_json")
    val repairClause = if (hasRepairColumn) ", repair_evidence_json" else ""
    val repairPlaceholder = if (hasRepairColumn) ", ?" else ""
    val createdAt = checkpoint.createdAt.takeIf(String::isNotBlank)
    connection.prepareStatement(
      """
      INSERT OR REPLACE INTO goal_shared_preplans (
        parent_goal_workflow_id, normalized_issue_key, repository_identity, preparation_status,
        contract_version, parent_spec_hash, decomposition_manifest_hash, planning_contract_id,
        planning_contract_version, phase_output_contract_id, phase_output_contract_version,
        payload_sha256, preplan_payload_json, created_at$repairClause
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP)$repairPlaceholder)
      """.trimIndent(),
    ).use { statement ->
      var index = 1
      statement.setString(index++, checkpoint.identity.parentGoalWorkflowId)
      statement.setString(index++, checkpoint.identity.normalizedIssueKey)
      statement.setString(index++, checkpoint.identity.repositoryIdentity)
      statement.setString(index++, checkpoint.preparationStatus.wireValue)
      statement.setString(index++, checkpoint.contractVersion)
      statement.setString(index++, checkpoint.provenance.parentSpecHash)
      statement.setString(index++, checkpoint.provenance.decompositionManifestHash)
      statement.setString(index++, checkpoint.provenance.planningContractId)
      statement.setString(index++, checkpoint.provenance.planningContractVersion)
      statement.setString(index++, checkpoint.provenance.phaseOutputContractId)
      statement.setString(index++, checkpoint.provenance.phaseOutputContractVersion)
      statement.setString(index++, checkpoint.payloadSha256)
      statement.setString(index++, checkpoint.preplanPayload)
      statement.setString(index++, createdAt)
      if (hasRepairColumn) {
        if (repairJson == null) {
          statement.setObject(index++, null)
        } else {
          statement.setString(index++, repairJson)
        }
      }
      statement.executeUpdate()
    }
  }

  fun listForExport(parentGoalWorkflowId: String): List<SharedGoalPreplanCheckpoint> {
    return connection.prepareStatement(
      "SELECT * FROM goal_shared_preplans WHERE parent_goal_workflow_id = ?",
    ).use { statement ->
      statement.bindAll(parentGoalWorkflowId)
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) {
            add(rows.toExportShared(parentGoalWorkflowId))
          }
        }
      }
    }
  }
}

private fun requireHydratedSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint) {
  val label = checkpoint.identity.parentGoalWorkflowId
  val failure =
    normalizedIdentityFailure(checkpoint.identity)
      ?: hydratedProvenanceFailure(checkpoint.provenance)
      ?: hydratedEnvelopeFailure(
        checkpoint.preparationStatus,
        checkpoint.payloadSha256,
        checkpoint.preplanPayload,
      )
  if (failure != null) {
    throw invalidGoalPlanningPreparationSchemaError(label, failure.first, failure.second)
  }
}

private fun ResultSet.toExportShared(parentGoalWorkflowId: String): SharedGoalPreplanCheckpoint {
  val identity =
    GoalPlanningIdentity(
      requireColumn(this, parentGoalWorkflowId, "parent_goal_workflow_id"),
      requireColumn(this, parentGoalWorkflowId, "normalized_issue_key"),
      requireColumn(this, parentGoalWorkflowId, "repository_identity"),
    )
  val status = decodeState(parentGoalWorkflowId, requireColumn(this, parentGoalWorkflowId, "preparation_status"))
  return SharedGoalPreplanCheckpoint(
    identity = identity,
    preparationStatus = status,
    provenance =
      GoalPlanningContractProvenance(
        requireColumn(this, parentGoalWorkflowId, "parent_spec_hash"),
        requireColumn(this, parentGoalWorkflowId, "decomposition_manifest_hash"),
        requireColumn(this, parentGoalWorkflowId, "planning_contract_id"),
        requireColumn(this, parentGoalWorkflowId, "planning_contract_version"),
        requireColumn(this, parentGoalWorkflowId, "phase_output_contract_id"),
        requireColumn(this, parentGoalWorkflowId, "phase_output_contract_version"),
      ),
    payloadSha256 = requireColumn(this, parentGoalWorkflowId, "payload_sha256"),
    preplanPayload = requireColumn(this, parentGoalWorkflowId, "preplan_payload_json"),
    repairEvidence = optionalRepairEvidence(this, parentGoalWorkflowId, "repair_evidence_json"),
    createdAt = getString("created_at").orEmpty(),
    contractVersion = requireColumn(this, parentGoalWorkflowId, "contract_version"),
  )
}

private fun ResultSet.toShared(expected: GoalPlanningIdentity): SharedGoalPreplanLookupResult {
  val label = expected.parentGoalWorkflowId
  val identity =
    GoalPlanningIdentity(
      requireColumn(this, label, "parent_goal_workflow_id"),
      requireColumn(this, label, "normalized_issue_key"),
      requireColumn(this, label, "repository_identity"),
    )
  if (identity != expected) {
    return SharedGoalPreplanLookupResult.Conflicted(
      GoalPlanningPreparationConflict(
        identity.parentGoalWorkflowId,
        0,
        "stored goal or repository identity differs from expected identity",
        null,
      ),
    )
  }
  val status = decodeState(label, requireColumn(this, label, "preparation_status"))
  if (status != GoalPlanningPreparationState.PREPARED) {
    throw invalidGoalPlanningPreparationSchemaError(
      label,
      "preparation_status",
      "normalized shared preplan must be prepared",
    )
  }
  return SharedGoalPreplanLookupResult.Found(
    SharedGoalPreplanCheckpoint(
      identity = identity,
      preparationStatus = status,
      provenance =
        GoalPlanningContractProvenance(
          requireColumn(this, label, "parent_spec_hash"),
          requireColumn(this, label, "decomposition_manifest_hash"),
          requireColumn(this, label, "planning_contract_id"),
          requireColumn(this, label, "planning_contract_version"),
          requireColumn(this, label, "phase_output_contract_id"),
          requireColumn(this, label, "phase_output_contract_version"),
        ),
      payloadSha256 = requireColumn(this, label, "payload_sha256"),
      preplanPayload = requireColumn(this, label, "preplan_payload_json"),
      repairEvidence = optionalRepairEvidence(this, label, "repair_evidence_json"),
      createdAt = requireColumn(this, label, "created_at"),
      contractVersion = requireColumn(this, label, "contract_version"),
    ).also(::requireHydratedSharedPreplan),
  )
}
