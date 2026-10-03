package skillbill.infrastructure.sqlite.workflow.goalrunner.planning

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_ID
import skillbill.contracts.workflow.goal.GOAL_PLANNING_PREPARATION_CONTRACT_VERSION
import skillbill.contracts.workflow.goal.GOAL_PLANNING_PREPARATION_SCHEMA_ID
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.FeatureTaskRuntimeMigrationFailureCode
import skillbill.error.shellcontent.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.shellcontent.InvalidGoalPlanningPreparationSchemaError
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationState
import java.sql.Connection
import java.sql.SQLException

internal object GoalPlanningPreparationSqlNormalize

internal inline fun <T> translateSqlFailure(
  workflowId: String,
  subtaskId: Int,
  block: () -> T,
): T =
  try {
    block()
  } catch (failure: SQLException) {
    throw IncompatibleGoalPlanningPreparationRecoveryError(
      workflowId,
      subtaskId,
      "SQLite rejected the immutable planning checkpoint: ${failure.message.orEmpty()}",
      failure,
    )
  }

internal fun Connection.rejectLegacy(workflowId: String) {
  prepareStatement(
    "SELECT 1 FROM goal_planning_preparations WHERE parent_goal_workflow_id = ? LIMIT 1",
  ).use { s ->
    s.bindAll(workflowId)
    s.executeQuery().use {
      if (it.next()) {
        throw SkillBillRuntimeException(
          FeatureTaskRuntimeMigrationFailureCode.SOURCE_UNSUPPORTED,
          "Legacy 0.1 paired planning has no supported automatic conversion. Preserve the original records and " +
            "use a compatible runtime or an explicitly reviewed migration.",
        )
      }
    }
  }
}

internal fun requireParentGoalWorkflowId(parentGoalWorkflowId: String) {
  if (parentGoalWorkflowId.isBlank()) {
    throw InvalidGoalPlanningPreparationSchemaError(
      parentGoalWorkflowId,
      "parent_goal_workflow_id",
      "parent_goal_workflow_id is required",
    )
  }
}

internal fun requirePositiveSubtaskId(
  parentGoalWorkflowId: String,
  subtaskId: Int,
) {
  if (subtaskId < 1) {
    throw InvalidGoalPlanningPreparationSchemaError(
      "$parentGoalWorkflowId#$subtaskId",
      "subtask_id",
      "subtask_id must be a positive integer",
    )
  }
}

internal fun normalizedIdentityFailure(identity: GoalPlanningIdentity): Pair<String, String>? =
  when {
    identity.parentGoalWorkflowId.isBlank() ->
      "identity.parent_goal_workflow_id" to "parent_goal_workflow_id is required"
    identity.normalizedIssueKey.isBlank() -> "identity.normalized_issue_key" to "normalized_issue_key is required"
    identity.repositoryIdentity.isBlank() -> "identity.repository_identity" to "repository_identity is required"
    else -> null
  }

internal fun normalizedProvenanceFailure(provenance: GoalPlanningContractProvenance): Pair<String, String>? =
  when {
    !provenance.parentSpecHash.isSha256() ->
      "provenance.parent_spec_hash" to "parent_spec_hash must be a lowercase SHA-256"
    !provenance.decompositionManifestHash.isSha256() ->
      "provenance.decomposition_manifest_hash" to "decomposition_manifest_hash must be a lowercase SHA-256"
    provenance.planningContractId != GOAL_PLANNING_PREPARATION_SCHEMA_ID ->
      "provenance.planning_contract_id" to "planning_contract_id is incompatible"
    provenance.planningContractVersion != GOAL_PLANNING_PREPARATION_CONTRACT_VERSION ->
      "provenance.planning_contract_version" to "planning_contract_version is incompatible"
    provenance.phaseOutputContractId != FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_ID ->
      "provenance.phase_output_contract_id" to "phase_output_contract_id is incompatible"
    provenance.phaseOutputContractVersion != FEATURE_TASK_RUNTIME_CONTRACT_VERSION ->
      "provenance.phase_output_contract_version" to
        "phase_output_contract_version requires supported migration before checkpointing; preserve durable state"
    else -> null
  }

internal fun normalizedEnvelopeFailure(
  contractVersion: String,
  status: GoalPlanningPreparationState,
  payloadSha256: String,
  payload: String,
): Pair<String, String>? =
  when {
    contractVersion != GOAL_PLANNING_PREPARATION_CONTRACT_VERSION ->
      "contract_version" to "contract_version is incompatible"
    status != GoalPlanningPreparationState.PREPARED ->
      "preparation_status" to "preparation_status must be prepared"
    !payloadSha256.isSha256() -> "payload_sha256" to "payload_sha256 must be a lowercase SHA-256"
    payload.isBlank() -> "payload" to "payload is required"
    else -> null
  }

internal fun hydratedProvenanceFailure(provenance: GoalPlanningContractProvenance): Pair<String, String>? =
  when {
    !provenance.parentSpecHash.isSha256() ->
      "provenance.parent_spec_hash" to "parent_spec_hash must be a lowercase SHA-256"
    !provenance.decompositionManifestHash.isSha256() ->
      "provenance.decomposition_manifest_hash" to "decomposition_manifest_hash must be a lowercase SHA-256"
    else -> null
  }

internal fun hydratedEnvelopeFailure(
  status: GoalPlanningPreparationState,
  payloadSha256: String,
  payload: String,
): Pair<String, String>? =
  when {
    status != GoalPlanningPreparationState.PREPARED ->
      "preparation_status" to "preparation_status must be prepared"
    !payloadSha256.isSha256() -> "payload_sha256" to "payload_sha256 must be a lowercase SHA-256"
    payload.isBlank() -> "payload" to "payload is required"
    else -> null
  }

internal fun String.isSha256(): Boolean = length == SHA256_HEX_LENGTH && all { it in '0'..'9' || it in 'a'..'f' }

internal const val FIRST_COLUMN_INDEX: Int = 1
internal const val SECOND_COLUMN_INDEX: Int = 2
internal const val THIRD_COLUMN_INDEX: Int = 3
internal const val FOURTH_COLUMN_INDEX: Int = 4
internal const val SHA256_HEX_LENGTH: Int = 64
