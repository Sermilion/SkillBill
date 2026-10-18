package skillbill.infrastructure.sqlite.workflow.goalrunner.subtask

import skillbill.error.shellcontent.invalidGoalPlanningPreparationSchemaError
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.decodeState
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.hydratedEnvelopeFailure
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.hydratedProvenanceFailure
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.isSha256
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.normalizedIdentityFailure
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.optionalRepairEvidence
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.rejectLegacy
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.requireColumn
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.requireNonNegativeInt
import skillbill.infrastructure.sqlite.workflow.goalrunner.planning.requirePositiveInt
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationConflict
import skillbill.ports.goalrunner.model.GoalPlanningPreparationState
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.GoalSubtaskPlanListResult
import skillbill.ports.goalrunner.model.GoalSubtaskPlanLookupResult
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import java.sql.Connection
import java.sql.ResultSet

internal class GoalSubtaskPlanSql(
  private val connection: Connection,
) {
  fun findSubtaskPlan(
    expectedIdentity: GoalPlanningIdentity,
    subtaskId: Int,
    governedSubSpecPath: String,
  ): GoalSubtaskPlanLookupResult {
    connection.rejectLegacy(expectedIdentity.parentGoalWorkflowId)?.let {
      return GoalSubtaskPlanLookupResult.Conflicted(it)
    }
    return connection.prepareStatement(
      "SELECT * FROM goal_subtask_plans WHERE parent_goal_workflow_id = ? AND subtask_id = ?",
    ).use { s ->
      s.bindAll(expectedIdentity.parentGoalWorkflowId, subtaskId)
      s.executeQuery().use { r ->
        if (!r.next()) {
          GoalSubtaskPlanLookupResult.Found(null)
        } else {
          r.toPlan(expectedIdentity, governedSubSpecPath)
        }
      }
    }
  }

  fun listSubtaskPlansOrdered(
    expectedIdentity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
  ): GoalSubtaskPlanListResult {
    connection.rejectLegacy(expectedIdentity.parentGoalWorkflowId)?.let {
      return GoalSubtaskPlanListResult.Conflicted(it)
    }
    return connection.prepareStatement(
      "SELECT * FROM goal_subtask_plans WHERE parent_goal_workflow_id = ? ORDER BY manifest_order, subtask_id",
    ).use { s ->
      val descriptors = uniqueDescriptorsBySubtaskId(expectedIdentity.parentGoalWorkflowId, orderedDescriptors)
      s.bindAll(expectedIdentity.parentGoalWorkflowId)
      s.executeQuery().use rows@{ r ->
        val plans = mutableListOf<GoalSubtaskPlanCheckpoint>()
        while (r.next()) {
          when (val plan = readOrderedPlan(r, expectedIdentity, descriptors)) {
            is GoalSubtaskPlanLookupResult.Conflicted -> return@rows GoalSubtaskPlanListResult.Conflicted(plan.conflict)
            is GoalSubtaskPlanLookupResult.Found -> plans += requireNotNull(plan.plan)
          }
        }
        GoalSubtaskPlanListResult.Found(plans)
      }
    }
  }

  private fun readOrderedPlan(
    rows: ResultSet,
    expectedIdentity: GoalPlanningIdentity,
    descriptors: Map<Int, GovernedGoalSubtaskDescriptor>,
  ): GoalSubtaskPlanLookupResult {
    val subtaskId = rows.getInt("subtask_id")
    val descriptor =
      descriptors[subtaskId]
        ?: return GoalSubtaskPlanLookupResult.Conflicted(
          GoalPlanningPreparationConflict(
            expectedIdentity.parentGoalWorkflowId,
            subtaskId,
            "stored plan is not present in the expected governed subtask descriptors",
            null,
          ),
        )
    return when (val plan = rows.toPlan(expectedIdentity, descriptor.governedSubSpecPath)) {
      is GoalSubtaskPlanLookupResult.Conflicted -> plan
      is GoalSubtaskPlanLookupResult.Found -> {
        val stored = requireNotNull(plan.plan)
        if (stored.manifestOrder != descriptor.manifestOrder || stored.subSpecHash != descriptor.subSpecHash) {
          GoalSubtaskPlanLookupResult.Conflicted(
            GoalPlanningPreparationConflict(
              expectedIdentity.parentGoalWorkflowId,
              subtaskId,
              "stored manifest order or governed sub-spec hash differs from the expected descriptor",
              null,
            ),
          )
        } else {
          plan
        }
      }
    }
  }

  fun deleteAllByGoal(parentGoalWorkflowId: String): Int =
    connection.prepareStatement("DELETE FROM goal_subtask_plans WHERE parent_goal_workflow_id = ?").use {
      it.bindAll(parentGoalWorkflowId)
      it.executeUpdate()
    }
}

private fun uniqueDescriptorsBySubtaskId(
  parentGoalWorkflowId: String,
  orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
): Map<Int, GovernedGoalSubtaskDescriptor> {
  val descriptors = orderedDescriptors.associateBy { it.subtaskId }
  if (descriptors.size != orderedDescriptors.size) {
    throw invalidGoalPlanningPreparationSchemaError(
      parentGoalWorkflowId,
      "ordered_descriptors",
      "subtask ids must be unique",
    )
  }
  return descriptors
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
    throw invalidGoalPlanningPreparationSchemaError(label, failure.first, failure.second)
  }
}

private fun ResultSet.toPlan(
  expected: GoalPlanningIdentity,
  expectedPath: String,
): GoalSubtaskPlanLookupResult {
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
    return GoalSubtaskPlanLookupResult.Conflicted(
      GoalPlanningPreparationConflict(
        identity.parentGoalWorkflowId,
        subtaskId,
        "stored identity or governed sub-spec differs from expected descriptor",
        null,
      ),
    )
  }
  val status = decodeState(label, requireColumn(this, label, "preparation_status"))
  if (status != GoalPlanningPreparationState.PREPARED) {
    throw invalidGoalPlanningPreparationSchemaError(
      label,
      "preparation_status",
      "normalized subtask plan must be prepared",
    )
  }
  return GoalSubtaskPlanLookupResult.Found(
    GoalSubtaskPlanCheckpoint(
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
    ).also(::requireHydratedSubtaskPlan),
  )
}
