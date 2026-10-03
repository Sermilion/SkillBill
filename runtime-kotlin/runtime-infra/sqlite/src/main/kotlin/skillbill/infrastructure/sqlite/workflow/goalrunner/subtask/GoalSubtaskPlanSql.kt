package skillbill.infrastructure.sqlite.workflow.goalrunner.subtask
import skillbill.error.shellcontent.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.shellcontent.InvalidGoalPlanningPreparationSchemaError
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.core.ops.inNestedWriteTransaction
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.decodeState
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.hydratedEnvelopeFailure
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.hydratedProvenanceFailure
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.isSha256
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.normalizedEnvelopeFailure
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.normalizedIdentityFailure
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.normalizedProvenanceFailure
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.optionalRepairEvidence
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.rejectLegacy
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.repairEvidenceJson
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.requireColumn
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.requireNonNegativeInt
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.requireParentGoalWorkflowId
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.requirePositiveInt
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.requirePositiveSubtaskId
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.translateSqlFailure
import skillbill.infrastructure.sqlite.workflow.goalrunner.shared.GoalSharedPreplanSql
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationState
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import java.sql.Connection
import java.sql.ResultSet

internal class GoalSubtaskPlanSql(
  private val connection: Connection,
  private val sharedPreplan: GoalSharedPreplanSql,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun listSubtaskPlansForMigration(identity: GoalPlanningIdentity): List<GoalSubtaskPlanCheckpoint> =
    connection.prepareStatement(
      "SELECT * FROM goal_subtask_plans WHERE parent_goal_workflow_id = ? ORDER BY manifest_order, subtask_id",
    ).use { statement ->
      connection.rejectLegacy(identity.parentGoalWorkflowId)
      statement.bindAll(identity.parentGoalWorkflowId)
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) add(rows.toPlan(identity, rows.getString("governed_sub_spec_path")))
        }
      }
    }

  fun checkpointSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) {
    requireNormalizedSubtaskPlan(checkpoint)
    connection.inNestedWriteTransaction(diagnostics) {
      requireGoverningSharedPreplan(checkpoint)
      val inserted = connection.insertSubtaskPlanRow(checkpoint)
      val stored = findSubtaskPlan(checkpoint.identity, checkpoint.subtaskId, checkpoint.governedSubSpecPath)
      requireImmutableSubtaskPlanInsert(checkpoint, inserted, stored)
    }
  }

  fun replaceSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) {
    requireNormalizedSubtaskPlan(checkpoint)
    connection.inNestedWriteTransaction(diagnostics) {
      requireGoverningSharedPreplan(checkpoint)
      connection.prepareStatement(
        "DELETE FROM goal_subtask_plans WHERE parent_goal_workflow_id = ? AND subtask_id = ?",
      ).use { s ->
        s.bindAll(checkpoint.identity.parentGoalWorkflowId, checkpoint.subtaskId)
        s.executeUpdate()
      }
      connection.insertSubtaskPlanRow(checkpoint)
    }
  }

  fun deleteSubtaskPlan(
    parentGoalWorkflowId: String,
    subtaskId: Int,
  ): Int {
    requireParentGoalWorkflowId(parentGoalWorkflowId)
    requirePositiveSubtaskId(parentGoalWorkflowId, subtaskId)
    return connection.prepareStatement(
      "DELETE FROM goal_subtask_plans WHERE parent_goal_workflow_id = ? AND subtask_id = ?",
    ).use { statement ->
      statement.bindAll(parentGoalWorkflowId, subtaskId)
      statement.executeUpdate()
    }
  }

  fun findSubtaskPlan(
    expectedIdentity: GoalPlanningIdentity,
    subtaskId: Int,
    governedSubSpecPath: String,
  ): GoalSubtaskPlanCheckpoint? {
    connection.rejectLegacy(expectedIdentity.parentGoalWorkflowId)
    return connection.prepareStatement(
      "SELECT * FROM goal_subtask_plans WHERE parent_goal_workflow_id = ? AND subtask_id = ?",
    ).use { s ->
      s.bindAll(expectedIdentity.parentGoalWorkflowId, subtaskId)
      s.executeQuery().use { r -> if (!r.next()) null else r.toPlan(expectedIdentity, governedSubSpecPath) }
    }
  }

  fun listSubtaskPlansOrdered(
    expectedIdentity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
  ): List<GoalSubtaskPlanCheckpoint> {
    connection.rejectLegacy(expectedIdentity.parentGoalWorkflowId)
    return connection.prepareStatement(
      "SELECT * FROM goal_subtask_plans WHERE parent_goal_workflow_id = ? ORDER BY manifest_order, subtask_id",
    ).use { s ->
      val descriptors = uniqueDescriptorsBySubtaskId(expectedIdentity.parentGoalWorkflowId, orderedDescriptors)
      s.bindAll(expectedIdentity.parentGoalWorkflowId)
      s.executeQuery().use { r ->
        buildList {
          while (r.next()) {
            val subtaskId = r.getInt("subtask_id")
            val descriptor = requireStoredDescriptor(expectedIdentity.parentGoalWorkflowId, subtaskId, descriptors)
            val plan = r.toPlan(expectedIdentity, descriptor.governedSubSpecPath)
            requirePlanMatchesDescriptor(expectedIdentity.parentGoalWorkflowId, subtaskId, plan, descriptor)
            add(plan)
          }
        }
      }
    }
  }

  fun deleteAllByGoal(parentGoalWorkflowId: String): Int =
    connection.prepareStatement("DELETE FROM goal_subtask_plans WHERE parent_goal_workflow_id = ?").use {
      it.bindAll(parentGoalWorkflowId)
      it.executeUpdate()
    }

  private fun requireGoverningSharedPreplan(checkpoint: GoalSubtaskPlanCheckpoint) {
    val shared =
      translateSqlFailure(checkpoint.identity.parentGoalWorkflowId, 0) {
        sharedPreplan.findSharedPreplan(checkpoint.identity)
      } ?: throw InvalidGoalPlanningPreparationSchemaError(
        "${checkpoint.identity.parentGoalWorkflowId}#${checkpoint.subtaskId}",
        "parent_goal_workflow_id",
        "shared preplan must be checkpointed first",
      )
    if (shared.provenance != checkpoint.provenance) {
      throw IncompatibleGoalPlanningPreparationRecoveryError(
        checkpoint.identity.parentGoalWorkflowId,
        checkpoint.subtaskId,
        "subtask plan provenance must exactly match the governing shared preplan",
      )
    }
  }

  private fun requireImmutableSubtaskPlanInsert(
    checkpoint: GoalSubtaskPlanCheckpoint,
    inserted: Boolean,
    stored: GoalSubtaskPlanCheckpoint?,
  ) {
    if (!inserted && stored != checkpoint.copy(createdAt = stored?.createdAt.orEmpty())) {
      throw IncompatibleGoalPlanningPreparationRecoveryError(
        checkpoint.identity.parentGoalWorkflowId,
        checkpoint.subtaskId,
        "subtask plan checkpoint is immutable",
      )
    }
  }
}

private fun uniqueDescriptorsBySubtaskId(
  parentGoalWorkflowId: String,
  orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
): Map<Int, GovernedGoalSubtaskDescriptor> {
  val descriptors = orderedDescriptors.associateBy { it.subtaskId }
  if (descriptors.size != orderedDescriptors.size) {
    throw InvalidGoalPlanningPreparationSchemaError(
      parentGoalWorkflowId,
      "ordered_descriptors",
      "subtask ids must be unique",
    )
  }
  return descriptors
}

private fun requireStoredDescriptor(
  parentGoalWorkflowId: String,
  subtaskId: Int,
  descriptors: Map<Int, GovernedGoalSubtaskDescriptor>,
): GovernedGoalSubtaskDescriptor =
  descriptors[subtaskId] ?: throw IncompatibleGoalPlanningPreparationRecoveryError(
    parentGoalWorkflowId,
    subtaskId,
    "stored plan is not present in the expected governed subtask descriptors",
  )

private fun requirePlanMatchesDescriptor(
  parentGoalWorkflowId: String,
  subtaskId: Int,
  plan: GoalSubtaskPlanCheckpoint,
  descriptor: GovernedGoalSubtaskDescriptor,
) {
  if (plan.manifestOrder != descriptor.manifestOrder || plan.subSpecHash != descriptor.subSpecHash) {
    throw IncompatibleGoalPlanningPreparationRecoveryError(
      parentGoalWorkflowId,
      subtaskId,
      "stored manifest order or governed sub-spec hash differs from the expected descriptor",
    )
  }
}

internal fun Connection.insertSubtaskPlanRow(checkpoint: GoalSubtaskPlanCheckpoint): Boolean =
  prepareStatement(
    """INSERT INTO goal_subtask_plans
  (parent_goal_workflow_id, normalized_issue_key, repository_identity, subtask_id,
  manifest_order, governed_sub_spec_path, sub_spec_hash, preparation_status, contract_version, parent_spec_hash,
  decomposition_manifest_hash, planning_contract_id, planning_contract_version, phase_output_contract_id,
  phase_output_contract_version, payload_sha256, plan_payload_json, repair_evidence_json)
  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  ON CONFLICT(parent_goal_workflow_id, subtask_id) DO NOTHING""",
  ).use { s ->
    val values: List<Any?> =
      listOf(
        checkpoint.identity.parentGoalWorkflowId, checkpoint.identity.normalizedIssueKey,
        checkpoint.identity.repositoryIdentity, checkpoint.subtaskId, checkpoint.manifestOrder,
        checkpoint.governedSubSpecPath, checkpoint.subSpecHash, checkpoint.preparationStatus.wireValue,
        checkpoint.contractVersion,
        checkpoint.provenance.parentSpecHash,
        checkpoint.provenance.decompositionManifestHash,
        checkpoint.provenance.planningContractId, checkpoint.provenance.planningContractVersion,
        checkpoint.provenance.phaseOutputContractId, checkpoint.provenance.phaseOutputContractVersion,
        checkpoint.payloadSha256, checkpoint.planPayload, checkpoint.repairEvidenceJson(),
      )
    s.bindAll(values)
    s.executeUpdate() > 0
  }

private fun requireNormalizedSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) {
  val label = "${checkpoint.identity.parentGoalWorkflowId}#${checkpoint.subtaskId}"
  val failure =
    normalizedIdentityFailure(checkpoint.identity)
      ?: normalizedProvenanceFailure(checkpoint.provenance)
      ?: normalizedEnvelopeFailure(
        checkpoint.contractVersion,
        checkpoint.preparationStatus,
        checkpoint.payloadSha256,
        checkpoint.planPayload,
      )
      ?: when {
        checkpoint.subtaskId < 1 -> "subtask_id" to "subtask_id must be a positive integer"
        checkpoint.manifestOrder < 0 -> "manifest_order" to "manifest_order must be non-negative"
        checkpoint.governedSubSpecPath.isBlank() ->
          "governed_sub_spec_path" to "governed_sub_spec_path is required"
        !checkpoint.subSpecHash.isSha256() -> "sub_spec_hash" to "sub_spec_hash must be a lowercase SHA-256"
        else -> null
      }
  if (failure != null) {
    throw InvalidGoalPlanningPreparationSchemaError(label, failure.first, failure.second)
  }
}

private fun requireHydratedSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) {
  val label = "${checkpoint.identity.parentGoalWorkflowId}#${checkpoint.subtaskId}"
  val failure =
    normalizedIdentityFailure(checkpoint.identity)
      ?: hydratedProvenanceFailure(checkpoint.provenance)
      ?: hydratedEnvelopeFailure(
        checkpoint.preparationStatus,
        checkpoint.payloadSha256,
        checkpoint.planPayload,
      )
      ?: when {
        checkpoint.subtaskId < 1 -> "subtask_id" to "subtask_id must be a positive integer"
        checkpoint.manifestOrder < 0 -> "manifest_order" to "manifest_order must be non-negative"
        checkpoint.governedSubSpecPath.isBlank() ->
          "governed_sub_spec_path" to "governed_sub_spec_path is required"
        !checkpoint.subSpecHash.isSha256() -> "sub_spec_hash" to "sub_spec_hash must be a lowercase SHA-256"
        else -> null
      }
  if (failure != null) {
    throw InvalidGoalPlanningPreparationSchemaError(label, failure.first, failure.second)
  }
}

private fun ResultSet.toPlan(
  expected: GoalPlanningIdentity,
  expectedPath: String,
): GoalSubtaskPlanCheckpoint {
  val subtaskId = requirePositiveInt(this, expected.parentGoalWorkflowId, "subtask_id")
  val label = "${expected.parentGoalWorkflowId}#$subtaskId"
  val identity =
    GoalPlanningIdentity(
      requireColumn(this, label, "parent_goal_workflow_id"),
      requireColumn(this, label, "normalized_issue_key"),
      requireColumn(this, label, "repository_identity"),
    )
  val path = requireColumn(this, label, "governed_sub_spec_path")
  if (identity != expected || path != expectedPath) {
    throw IncompatibleGoalPlanningPreparationRecoveryError(
      identity.parentGoalWorkflowId,
      subtaskId,
      "stored identity or governed sub-spec differs from expected descriptor",
    )
  }
  val status = decodeState(label, requireColumn(this, label, "preparation_status"))
  if (status != GoalPlanningPreparationState.PREPARED) {
    throw InvalidGoalPlanningPreparationSchemaError(
      label,
      "preparation_status",
      "normalized subtask plan must be prepared",
    )
  }
  return GoalSubtaskPlanCheckpoint(
    identity = identity, subtaskId = subtaskId, manifestOrder = requireNonNegativeInt(this, label, "manifest_order"),
    governedSubSpecPath = path, subSpecHash = requireColumn(this, label, "sub_spec_hash"),
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
    planPayload = requireColumn(this, label, "plan_payload_json"),
    repairEvidence = optionalRepairEvidence(this, label, "repair_evidence_json"),
    createdAt = requireColumn(this, label, "created_at"),
    contractVersion = requireColumn(this, label, "contract_version"),
  ).also(::requireHydratedSubtaskPlan)
}
