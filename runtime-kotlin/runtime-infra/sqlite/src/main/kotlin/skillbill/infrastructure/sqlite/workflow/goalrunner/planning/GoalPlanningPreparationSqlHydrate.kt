package skillbill.infrastructure.sqlite.workflow.goalrunner.planning

import skillbill.contracts.JsonCodec
import skillbill.error.shellcontent.invalidGoalPlanningPreparationSchemaError
import skillbill.infrastructure.sqlite.workflow.featuretask.encodeWorkflowArtifact
import skillbill.ports.goalrunner.model.GoalPlanningPreparationState
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.workflow.taskruntime.artifact.decodePhaseOutputRepairEvidenceFromArtifact
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairEvidence
import java.sql.ResultSet

internal fun SharedGoalPreplanCheckpoint.repairEvidenceJson(): String? =
  repairEvidence?.let {
    JsonCodec.mapToJsonString(JsonCodec.anyToStringAnyMap(it.encodeWorkflowArtifact()) ?: emptyMap())
  }

internal fun GoalSubtaskPlanCheckpoint.repairEvidenceJson(): String? =
  repairEvidence?.let {
    JsonCodec.mapToJsonString(JsonCodec.anyToStringAnyMap(it.encodeWorkflowArtifact()) ?: emptyMap())
  }

internal fun incompatibleLoadedVersionReason(loaded: String): String = "loaded contract_version '$loaded' is not '0.1'."

internal fun statusLabel(rows: ResultSet): String =
  "${rows.getString("parent_goal_workflow_id")}#${rows.getInt("subtask_id")}"

internal fun requireColumn(
  rows: ResultSet,
  label: String,
  column: String,
): String =
  rows.getString(column) ?: throw invalidGoalPlanningPreparationSchemaError(
    sourceLabel = label,
    fieldPath = column,
    reason = "$column is required but was null on hydrate.",
  )

internal fun optionalRepairEvidence(
  rows: ResultSet,
  label: String,
  column: String,
): FeatureTaskRuntimePhaseOutputRepairEvidence? {
  val raw = rows.getString(column) ?: return null
  return try {
    val decoded =
      JsonCodec.parseObjectOrNull(raw)
        ?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
        ?: throw IllegalArgumentException("repair evidence must be a JSON object")
    decodePhaseOutputRepairEvidenceFromArtifact(decoded)
  } catch (_: Exception) {
    throw invalidGoalPlanningPreparationSchemaError(
      sourceLabel = label,
      fieldPath = column,
      reason = "repair evidence is malformed",
    )
  }
}

internal fun decodeState(
  label: String,
  value: String?,
): GoalPlanningPreparationState =
  GoalPlanningPreparationState.entries.singleOrNull { it.wireValue == value }
    ?: throw invalidGoalPlanningPreparationSchemaError(
      sourceLabel = label,
      fieldPath = "preparation_status",
      reason = "preparation_status '${value.orEmpty()}' is not supported.",
    )

internal fun requirePositiveInt(
  rows: ResultSet,
  label: String,
  column: String,
): Int =
  rows.getInt(column).also {
    if (rows.wasNull() || it < 1) {
      throw invalidGoalPlanningPreparationSchemaError(
        label,
        column,
        "$column must be a positive integer on hydrate",
      )
    }
  }

internal fun requireNonNegativeInt(
  rows: ResultSet,
  label: String,
  column: String,
): Int =
  rows.getInt(column).also {
    if (rows.wasNull() || it < 0) {
      throw invalidGoalPlanningPreparationSchemaError(
        label,
        column,
        "$column must be a non-negative integer on hydrate",
      )
    }
  }
