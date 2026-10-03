package skillbill.infrastructure.sqlite.workflow.goalrunner.shared
import skillbill.contracts.workflow.goal.GOAL_SHARED_PREPLAN_DISCARDED_PAYLOAD
import skillbill.error.shellcontent.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.shellcontent.InvalidGoalPlanningPreparationSchemaError
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.core.ops.inNestedWriteTransaction
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.decodeState
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.hydratedEnvelopeFailure
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.hydratedProvenanceFailure
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.normalizedEnvelopeFailure
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.normalizedIdentityFailure
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.normalizedProvenanceFailure
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.optionalRepairEvidence
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.rejectLegacy
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.repairEvidenceJson
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.requireColumn
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.requireParentGoalWorkflowId
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.translateSqlFailure
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationState
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet

internal const val INVALIDATED_SHARED_PREPLAN_PAYLOAD = GOAL_SHARED_PREPLAN_DISCARDED_PAYLOAD

internal val INVALIDATED_SHARED_PREPLAN_PAYLOAD_SHA256: String =
  MessageDigest.getInstance("SHA-256")
    .digest(INVALIDATED_SHARED_PREPLAN_PAYLOAD.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

internal class GoalSharedPreplanSql(
  private val connection: Connection,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun checkpointSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint) {
    requireNormalizedSharedPreplan(checkpoint)
    connection.inNestedWriteTransaction(diagnostics) {
      val inserted = connection.insertSharedPreplanRow(checkpoint)
      if (!inserted) {
        val stored =
          translateSqlFailure(checkpoint.identity.parentGoalWorkflowId, 0) {
            findSharedPreplan(checkpoint.identity)
          }
        if (stored != checkpoint.copy(createdAt = stored?.createdAt.orEmpty())) {
          throw IncompatibleGoalPlanningPreparationRecoveryError(
            checkpoint.identity.parentGoalWorkflowId,
            0,
            "shared preplan checkpoint is immutable",
          )
        }
      }
    }
  }

  fun replaceSharedPreplan(
    checkpoint: SharedGoalPreplanCheckpoint,
    expectedPayloadSha256: String,
    cascadePlanSubtaskIds: List<Int>,
  ) {
    requireNormalizedSharedPreplan(checkpoint)
    require(expectedPayloadSha256.isNotBlank()) { "expectedPayloadSha256 is required." }
    connection.inNestedWriteTransaction(diagnostics) {
      val updated =
        connection.prepareStatement(
          """UPDATE goal_shared_preplans SET normalized_issue_key = ?, repository_identity = ?,
        preparation_status = ?, contract_version = ?, parent_spec_hash = ?, decomposition_manifest_hash = ?,
        planning_contract_id = ?, planning_contract_version = ?, phase_output_contract_id = ?,
        phase_output_contract_version = ?, payload_sha256 = ?, preplan_payload_json = ?, repair_evidence_json = ?
        WHERE parent_goal_workflow_id = ? AND payload_sha256 = ?""",
        ).use { s ->
          val values =
            listOf(
              checkpoint.identity.normalizedIssueKey, checkpoint.identity.repositoryIdentity,
              checkpoint.preparationStatus.wireValue, checkpoint.contractVersion,
              checkpoint.provenance.parentSpecHash, checkpoint.provenance.decompositionManifestHash,
              checkpoint.provenance.planningContractId, checkpoint.provenance.planningContractVersion,
              checkpoint.provenance.phaseOutputContractId, checkpoint.provenance.phaseOutputContractVersion,
              checkpoint.payloadSha256, checkpoint.preplanPayload, checkpoint.repairEvidenceJson(),
              checkpoint.identity.parentGoalWorkflowId,
              expectedPayloadSha256,
            )
          s.bindAll(values)
          s.executeUpdate() > 0
        }
      if (!updated) {
        throw IncompatibleGoalPlanningPreparationRecoveryError(
          checkpoint.identity.parentGoalWorkflowId,
          0,
          "shared preplan changed after it was validated for regeneration",
        )
      }
      connection.cascadeSiblingPlanRows(
        checkpoint.identity.parentGoalWorkflowId,
        cascadePlanSubtaskIds,
      )
      connection.restampSubtaskPlanProvenance(
        checkpoint.identity.parentGoalWorkflowId,
        checkpoint.provenance,
      )
    }
  }

  fun advanceSharedPreplanProvenance(
    identity: GoalPlanningIdentity,
    expectedPayloadSha256: String,
    provenance: GoalPlanningContractProvenance,
  ) {
    require(expectedPayloadSha256.isNotBlank()) { "expectedPayloadSha256 is required." }
    normalizedIdentityFailure(identity)?.let { (field, reason) ->
      throw InvalidGoalPlanningPreparationSchemaError(identity.parentGoalWorkflowId, field, reason)
    }
    connection.inNestedWriteTransaction(diagnostics) {
      val updated =
        connection.prepareStatement(
          """UPDATE goal_shared_preplans SET parent_spec_hash = ?, decomposition_manifest_hash = ?,
        planning_contract_id = ?, planning_contract_version = ?, phase_output_contract_id = ?,
        phase_output_contract_version = ?
        WHERE parent_goal_workflow_id = ? AND payload_sha256 = ?""",
        ).use { s ->
          listOf(
            provenance.parentSpecHash,
            provenance.decompositionManifestHash,
            provenance.planningContractId,
            provenance.planningContractVersion,
            provenance.phaseOutputContractId,
            provenance.phaseOutputContractVersion,
            identity.parentGoalWorkflowId,
            expectedPayloadSha256,
          ).also { s.bindAll(it) }
          s.executeUpdate() > 0
        }
      if (!updated) {
        throw IncompatibleGoalPlanningPreparationRecoveryError(
          identity.parentGoalWorkflowId,
          0,
          "shared preplan changed after it was validated for provenance advance",
        )
      }
      connection.restampSubtaskPlanProvenance(identity.parentGoalWorkflowId, provenance)
    }
  }

  fun cascadeSiblingPlansAfterSharedPreplanRefresh(
    parentGoalWorkflowId: String,
    cascadePlanSubtaskIds: List<Int>,
  ): List<Int> {
    requireParentGoalWorkflowId(parentGoalWorkflowId)
    return connection.cascadeSiblingPlanRows(parentGoalWorkflowId, cascadePlanSubtaskIds)
  }

  fun findSharedPreplan(expectedIdentity: GoalPlanningIdentity): SharedGoalPreplanCheckpoint? {
    connection.rejectLegacy(expectedIdentity.parentGoalWorkflowId)
    return connection.prepareStatement(
      "SELECT * FROM goal_shared_preplans WHERE parent_goal_workflow_id = ?",
    ).use { s ->
      s.bindAll(expectedIdentity.parentGoalWorkflowId)
      s.executeQuery().use { r -> if (!r.next()) null else r.toShared(expectedIdentity) }
    }
  }

  fun deleteSharedPreplan(
    identity: GoalPlanningIdentity,
    expectedPayloadSha256: String,
  ): Int {
    require(expectedPayloadSha256.isNotBlank()) { "expectedPayloadSha256 is required." }
    normalizedIdentityFailure(identity)?.let { (field, reason) ->
      throw InvalidGoalPlanningPreparationSchemaError(identity.parentGoalWorkflowId, field, reason)
    }
    val deleted =
      connection.prepareStatement(
        "DELETE FROM goal_shared_preplans WHERE parent_goal_workflow_id = ? AND payload_sha256 = ?",
      ).use { statement ->
        statement.bindAll(identity.parentGoalWorkflowId, expectedPayloadSha256)
        statement.executeUpdate()
      }
    if (deleted == 0) {
      throw IncompatibleGoalPlanningPreparationRecoveryError(
        identity.parentGoalWorkflowId,
        0,
        "shared preplan changed after it was observed for discard",
      )
    }
    return deleted
  }

  fun invalidateSharedPreplan(
    identity: GoalPlanningIdentity,
    expectedPayloadSha256: String,
  ): Int {
    require(expectedPayloadSha256.isNotBlank()) { "expectedPayloadSha256 is required." }
    normalizedIdentityFailure(identity)?.let { (field, reason) ->
      throw InvalidGoalPlanningPreparationSchemaError(identity.parentGoalWorkflowId, field, reason)
    }
    val updated =
      connection.prepareStatement(
        """UPDATE goal_shared_preplans SET payload_sha256 = ?, preplan_payload_json = ?, repair_evidence_json = NULL
      WHERE parent_goal_workflow_id = ? AND payload_sha256 = ?""",
      ).use { statement ->
        statement.bindAll(
          INVALIDATED_SHARED_PREPLAN_PAYLOAD_SHA256,
          INVALIDATED_SHARED_PREPLAN_PAYLOAD,
          identity.parentGoalWorkflowId,
          expectedPayloadSha256,
        )
        statement.executeUpdate()
      }
    if (updated == 0) {
      throw IncompatibleGoalPlanningPreparationRecoveryError(
        identity.parentGoalWorkflowId,
        0,
        "shared preplan changed after it was observed for discard",
      )
    }
    return updated
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
}

internal fun Connection.insertSharedPreplanRow(checkpoint: SharedGoalPreplanCheckpoint): Boolean =
  prepareStatement(
    """INSERT INTO goal_shared_preplans (parent_goal_workflow_id, normalized_issue_key, repository_identity,
  preparation_status, contract_version, parent_spec_hash, decomposition_manifest_hash, planning_contract_id,
  planning_contract_version, phase_output_contract_id, phase_output_contract_version, payload_sha256,
  preplan_payload_json, repair_evidence_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  ON CONFLICT(parent_goal_workflow_id) DO NOTHING""",
  ).use { s ->
    val values =
      listOf(
        checkpoint.identity.parentGoalWorkflowId, checkpoint.identity.normalizedIssueKey,
        checkpoint.identity.repositoryIdentity, checkpoint.preparationStatus.wireValue, checkpoint.contractVersion,
        checkpoint.provenance.parentSpecHash, checkpoint.provenance.decompositionManifestHash,
        checkpoint.provenance.planningContractId, checkpoint.provenance.planningContractVersion,
        checkpoint.provenance.phaseOutputContractId, checkpoint.provenance.phaseOutputContractVersion,
        checkpoint.payloadSha256, checkpoint.preplanPayload, checkpoint.repairEvidenceJson(),
      )
    s.bindAll(values)
    s.executeUpdate() > 0
  }

internal fun Connection.cascadeSiblingPlanRows(
  parentGoalWorkflowId: String,
  cascadePlanSubtaskIds: List<Int>,
): List<Int> {
  cascadePlanSubtaskIds.forEach { subtaskId ->
    prepareStatement(
      "DELETE FROM goal_subtask_plans WHERE parent_goal_workflow_id = ? AND subtask_id = ?",
    ).use { statement ->
      statement.bindAll(parentGoalWorkflowId, subtaskId)
      statement.executeUpdate()
    }
  }
  return cascadePlanSubtaskIds
}

internal fun Connection.restampSubtaskPlanProvenance(
  parentGoalWorkflowId: String,
  provenance: GoalPlanningContractProvenance,
) {
  prepareStatement(
    """UPDATE goal_subtask_plans SET parent_spec_hash = ?, decomposition_manifest_hash = ?,
    planning_contract_id = ?, planning_contract_version = ?, phase_output_contract_id = ?,
    phase_output_contract_version = ?
    WHERE parent_goal_workflow_id = ?""",
  ).use { s ->
    listOf(
      provenance.parentSpecHash,
      provenance.decompositionManifestHash,
      provenance.planningContractId,
      provenance.planningContractVersion,
      provenance.phaseOutputContractId,
      provenance.phaseOutputContractVersion,
      parentGoalWorkflowId,
    ).also { s.bindAll(it) }
    s.executeUpdate()
  }
}

private fun requireNormalizedSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint) {
  val label = checkpoint.identity.parentGoalWorkflowId
  val failure =
    normalizedIdentityFailure(checkpoint.identity)
      ?: normalizedProvenanceFailure(checkpoint.provenance)
      ?: normalizedEnvelopeFailure(
        checkpoint.contractVersion,
        checkpoint.preparationStatus,
        checkpoint.payloadSha256,
        checkpoint.preplanPayload,
      )
  if (failure != null) {
    throw InvalidGoalPlanningPreparationSchemaError(label, failure.first, failure.second)
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
    throw InvalidGoalPlanningPreparationSchemaError(label, failure.first, failure.second)
  }
}

private fun ResultSet.toShared(expected: GoalPlanningIdentity): SharedGoalPreplanCheckpoint {
  val label = expected.parentGoalWorkflowId
  val identity =
    GoalPlanningIdentity(
      requireColumn(this, label, "parent_goal_workflow_id"),
      requireColumn(this, label, "normalized_issue_key"),
      requireColumn(this, label, "repository_identity"),
    )
  if (identity != expected) {
    throw IncompatibleGoalPlanningPreparationRecoveryError(
      identity.parentGoalWorkflowId,
      0,
      "stored goal or repository identity differs from expected identity",
    )
  }
  val status = decodeState(label, requireColumn(this, label, "preparation_status"))
  if (status != GoalPlanningPreparationState.PREPARED) {
    throw InvalidGoalPlanningPreparationSchemaError(
      label,
      "preparation_status",
      "normalized shared preplan must be prepared",
    )
  }
  return SharedGoalPreplanCheckpoint(
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
  ).also(::requireHydratedSharedPreplan)
}
