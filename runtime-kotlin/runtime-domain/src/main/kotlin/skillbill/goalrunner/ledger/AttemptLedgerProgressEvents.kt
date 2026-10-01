package skillbill.goalrunner.ledger

import skillbill.contracts.SharedPayloadKeys
import skillbill.error.shellcontent.InvalidGoalProgressEventSchemaError
import skillbill.goalrunner.model.GoalObservabilityProgressEvent
import skillbill.goalrunner.model.GoalRunnerProgressEvent
import skillbill.workflow.model.goalobservability.GoalObservabilityEvent
import skillbill.workflow.model.persistence.artifact.asExactIntOrNull

fun Map<*, *>.toGoalRunnerProgressEventOrNull(): GoalRunnerProgressEvent? {
  val stepId = this[SharedPayloadKeys.STEP_ID]?.toString()?.takeIf(String::isNotBlank)
  val kind = this["kind"]?.toString()?.takeIf(String::isNotBlank)
  val timestamp = this["timestamp"]?.toString()?.takeIf(String::isNotBlank)
  return if (stepId != null && kind != null && timestamp != null) {
    GoalRunnerProgressEvent(
      stepId = stepId,
      attemptCount = requiredLegacyProgressInt("attempt_count"),
      kind = kind,
      message = this["message"]?.toString().orEmpty(),
      sequence = requiredLegacyProgressInt("sequence"),
      timestamp = timestamp,
    )
  } else {
    null
  }
}

private fun Map<*, *>.requiredLegacyProgressInt(key: String): Int {
  val value = this[key] ?: return 0
  return value.asExactIntOrNull()
    ?: throw InvalidGoalProgressEventSchemaError(
      "progress_event",
      key,
      "must be an integer.",
    )
}

fun GoalRunnerProgressEvent.summary(): String =
  buildString {
    append("durable_progress step=")
    append(stepId)
    append(" attempt=")
    append(attemptCount)
    append(" kind=")
    append(kind)
    append(" sequence=")
    append(sequence)
    append(" at=")
    append(timestamp)
    if (message.isNotBlank()) {
      append(" message=")
      append(message)
    }
  }

fun GoalObservabilityEvent.toProgressEvent(): GoalObservabilityProgressEvent =
  GoalObservabilityProgressEvent(
    issueKey = issueKey,
    subtaskId = subtaskId,
    workflowPhase = workflowPhase,
    workerRole = workerRole,
    livenessClass = livenessClass,
    activitySummary = activitySummary,
    sequenceNumber = sequenceNumber,
    timestamp = timestamp.toString(),
  )
