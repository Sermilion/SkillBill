package skillbill.ports.workflow.model

import skillbill.contracts.workflow.payload.WorkflowTimestampPayloadKeys
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.time.parsePersistedInstant

private val timestampArtifactFamilies =
  setOf(
    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS,
    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_LEDGER,
    DurableWorkflowArtifactFamily.GOAL_ATTEMPT_LEDGER,
    DurableWorkflowArtifactFamily.GOAL_OBSERVABILITY_LATEST_EVENT,
    DurableWorkflowArtifactFamily.GOAL_OBSERVABILITY_RUN_HISTORY,
    DurableWorkflowArtifactFamily.GOAL_PROGRESS_LATEST_EVENT,
    DurableWorkflowArtifactFamily.GOAL_PROGRESS_RUN_HISTORY,
  )
private val timestampArtifactKeys = timestampArtifactFamilies.mapTo(hashSetOf()) { it.label() }
private val timestampFieldKeys =
  setOf(
    WorkflowTimestampPayloadKeys.TIMESTAMP,
    WorkflowTimestampPayloadKeys.FIRST_STARTED_AT,
    WorkflowWirePayloadKeys.STARTED_AT,
    WorkflowWirePayloadKeys.FINISHED_AT,
  )

internal fun preserveArtifactTimestampText(
  artifacts: Map<String, Any?>,
  source: Map<String, Any?>?,
): Map<String, Any?> =
  artifacts.mapValues { (key, value) ->
    if (key in timestampArtifactKeys) {
      val family = timestampArtifactFamilies.first { it.label() == key }
      preserveTimestampFields(value, source?.let(family::value))
    } else {
      value
    }
  }

private fun preserveTimestampFields(
  value: Any?,
  source: Any?,
): Any? =
  when {
    value is Map<*, *> && source is Map<*, *> ->
      value.entries.associateTo(linkedMapOf()) { (key, item) ->
        key to
          if (key in timestampFieldKeys && item is String && source[key] is String) {
            preserveTimestampText(item, source[key] as String)
          } else {
            preserveTimestampFields(item, source[key])
          }
      }
    value is List<*> && source is List<*> ->
      value.mapIndexed { index, item ->
        preserveTimestampFields(item, source.getOrNull(index))
      }
    else -> value
  }

private fun preserveTimestampText(
  value: String,
  source: String,
): String {
  return try {
    if (parsePersistedInstant(value) == parsePersistedInstant(source)) source else value
  } catch (error: IllegalArgumentException) {
    throw invalidWorkflowStateSchemaError("Workflow artifact contains an invalid timestamp.", error)
  }
}
