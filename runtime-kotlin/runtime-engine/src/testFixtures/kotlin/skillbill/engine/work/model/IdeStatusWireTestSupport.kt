package skillbill.engine.work.model

import skillbill.contracts.SharedPayloadKeys
import skillbill.ports.idestatus.model.IdeStatusCurrentModel
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.ports.idestatus.model.IdeStatusCurrentSubtask
import skillbill.ports.idestatus.model.IdeStatusPlanning
import skillbill.ports.idestatus.model.IdeStatusProblem
import skillbill.ports.idestatus.model.IdeStatusSnapshot

fun IdeStatusSnapshot.toStatusWireMap(): Map<String, Any?> =
  buildMap {
    put(SharedPayloadKeys.CONTRACT_VERSION, contractVersion)
    put("repository_identity", repositoryIdentity)
    issueKey?.takeIf(String::isNotBlank)?.let { put(SharedPayloadKeys.ISSUE_KEY, it) }
    workflowId?.takeIf(String::isNotBlank)?.let { put(SharedPayloadKeys.WORKFLOW_ID, it) }
    workflowFamily?.let { put("workflow_family", it.wireValue) }
    put("lifecycle_state", lifecycleState.wireValue)
    put("current_step", mapOf("id" to currentStep.id, "label" to currentStep.label))
    progress?.let { put("progress", mapOf("completed" to it.completed, "total" to it.total)) }
    startedAt?.let { put("started_at", it.toString()) }
    currentSubtask?.let { put("current_subtask", it.toWireMap()) }
    currentModel?.let { put("current_model", it.toWireMap()) }
    planning?.let { put("planning", it.toWireMap()) }
    currentPhaseExecution?.let { put("current_phase_execution", it.toWireMap()) }
    putPauseFields(this@toStatusWireMap)
    putActivityFields(this@toStatusWireMap)
    put("updated_at", updatedAt.toString())
    put("freshness", freshness.wireValue)
    put(SharedPayloadKeys.SUMMARY, summary)
    problem?.let { put("problem", it.toWireMap()) }
  }

private fun IdeStatusCurrentSubtask.toWireMap(): Map<String, Any?> =
  buildMap {
    put("id", id)
    startedAt?.let { put("started_at", it.toString()) }
    activeDurationMs?.let { put("active_duration_ms", it) }
    activeDurationAsOf?.let { put("active_duration_as_of", it.toString()) }
  }

private fun IdeStatusCurrentModel.toWireMap(): Map<String, Any?> =
  buildMap {
    put("model", model)
    effort?.let { put("effort", it) }
    phaseId?.let { put(SharedPayloadKeys.PHASE_ID, it) }
  }

private fun IdeStatusPlanning.toWireMap(): Map<String, Any?> =
  buildMap {
    put("state", state.wireValue)
    put("shared_preplan_prepared", sharedPreplanPrepared)
    put("planned_subtask_count", plannedSubtaskCount)
    put("total_subtask_count", totalSubtaskCount)
    currentPlanningSubtaskId?.let { put("current_planning_subtask_id", it) }
    planningWaveSubtaskIds.takeIf { it.isNotEmpty() }?.let { put("planning_wave_subtask_ids", it) }
    reason?.let { put("reason", it) }
  }

private fun IdeStatusCurrentPhaseExecution.toWireMap(): Map<String, Any?> =
  buildMap {
    put(SharedPayloadKeys.PHASE_ID, phaseId)
    put("kind", kind.wireValue)
    put("count", count)
    total?.let { put("total", it) }
  }

private fun MutableMap<String, Any?>.putPauseFields(snapshot: IdeStatusSnapshot) {
  snapshot.pauseRequested?.takeIf { it }?.let { put("pause_requested", true) }
  snapshot.pausedAt?.let { put("paused_at", it.toString()) }
  snapshot.pauseReason?.let { reason ->
    put("pause_reason", mapOf("code" to reason.code.wireValue).plusIfNotNull("label", reason.label))
  }
}

private fun MutableMap<String, Any?>.putActivityFields(snapshot: IdeStatusSnapshot) {
  snapshot.activeDurationMs?.let { put("active_duration_ms", it) }
  snapshot.activeDurationAsOf?.let { put("active_duration_as_of", it.toString()) }
  val activityAt = snapshot.lastAgentActivityAt
  val activityLabel = snapshot.lastAgentActivityLabel
  if (activityAt != null && activityLabel != null) {
    put("last_agent_activity_at", activityAt.toString())
    put("last_agent_activity_label", activityLabel.wireValue)
  }
}

private fun IdeStatusProblem.toWireMap(): Map<String, Any?> =
  mapOf("code" to code.wireValue, "message" to message).plusIfNotNull(
    "details",
    details?.asWirePayload()?.toPayload(),
  )

private fun Map<String, Any?>.plusIfNotNull(
  key: String,
  value: Any?,
): Map<String, Any?> = if (value == null) this else this + (key to value)
