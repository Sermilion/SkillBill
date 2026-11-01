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
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.requireColumn
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.requireParentGoalWorkflowId
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationConflict
import skillbill.ports.goalrunner.model.GoalPlanningPreparationState
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.goalrunner.model.SharedGoalPreplanLookupResult
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
