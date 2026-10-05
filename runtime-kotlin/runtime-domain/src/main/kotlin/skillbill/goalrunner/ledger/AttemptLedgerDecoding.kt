package skillbill.goalrunner.ledger

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.scaffold.wire.optionalString
import skillbill.contracts.workflow.payload.WorkflowTimestampPayloadKeys
import skillbill.error.shellcontent.invalidGoalProgressEventSchemaError
import skillbill.goalrunner.model.GoalRunnerProgressEvent
import skillbill.workflow.engine.model.GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
import skillbill.workflow.model.goalobservability.asGoalWorkflowArtifactMap
import skillbill.workflow.model.persistence.artifact.DurableArtifactMapReader
import skillbill.workflow.model.persistence.artifact.toStringKeyedArtifactMap
import skillbill.workflow.time.parsePersistedInstantOrNull

fun progressEventFrom(artifacts: Any): GoalRunnerProgressEvent? {
  val wire = artifacts.asGoalWorkflowArtifactMap("goal progress event artifacts")
  return (wire["progress_event"] as? Map<*, *>)
    ?.toGoalRunnerProgressEventOrNull()
}

fun declaredProgressEventFrom(artifacts: Any): GoalProgressEvent? {
  val wire = artifacts.asGoalWorkflowArtifactMap("goal declared progress event artifacts")
  return when (val raw = wire[GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY]) {
    null -> null
    is Map<*, *> -> raw.decodeDeclaredGoalProgressEvent(GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY)
    else -> throw invalidGoalProgressEventSchemaError(
      GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY,
      "<root>",
      "must be an object.",
    )
  }
}

fun Map<*, *>.decodeDeclaredGoalProgressEvent(sourceLabel: String): GoalProgressEvent {
  val artifact =
    toStringKeyedArtifactMap {
      invalidDeclaredGoalProgressEvent(sourceLabel, "<root>", it)
    }
  val reader =
    DurableArtifactMapReader(
      artifact,
    ) { detail ->
      invalidDeclaredGoalProgressEvent(sourceLabel, "<root>", detail)
    }
  val eventKind = requiredProgressEventKind(reader, sourceLabel)
  val workflowId = reader.requiredString("workflow_id")
  val workflowPhase = reader.requiredString("workflow_phase")
  val timestamp =
    (artifact[WorkflowTimestampPayloadKeys.TIMESTAMP] as? String)
      ?.takeIf(String::isNotBlank)
      ?.let(::parsePersistedInstantOrNull)
      ?: throw invalidGoalProgressEventSchemaError(sourceLabel, "timestamp", "must be an RFC 3339 instant.")
  val sequenceNumber =
    reader.requiredInt("sequence_number").also { value ->
      if (value < 0) {
        invalidDeclaredGoalProgressEvent(sourceLabel, "sequence_number", "must be non-negative.")
      }
    }
  val outcome = optionalProgressOutcome(reader, sourceLabel)
  val processAlive = reader.optionalBoolean("process_alive") ?: false
  val stepId = reader.optionalString(SharedPayloadKeys.STEP_ID)
  val operationName = reader.optionalString("operation_name")
  val operationKind = reader.optionalString("operation_kind")
  val expectedLong = reader.optionalBoolean("expected_long") ?: false
  GoalProgressEvent.violation(eventKind, workflowId, workflowPhase, sequenceNumber, operationName)?.let { reason ->
    invalidDeclaredGoalProgressEvent(sourceLabel, "<root>", reason)
  }
  return GoalProgressEvent(
      eventKind = eventKind,
      workflowId = workflowId,
      workflowPhase = workflowPhase,
      processAlive = processAlive,
      sequenceNumber = sequenceNumber,
      timestamp = timestamp,
      stepId = stepId,
      operationName = operationName,
      operationKind = operationKind,
      expectedLong = expectedLong,
      outcome = outcome,
    )
}

private fun invalidDeclaredGoalProgressEvent(
  sourceLabel: String,
  field: String,
  detail: String,
): Nothing = throw invalidGoalProgressEventSchemaError(sourceLabel, field, detail)

private fun requiredProgressEventKind(
  reader: DurableArtifactMapReader,
  sourceLabel: String,
): GoalProgressEventKind {
  val wire = reader.requiredString("event_kind")
  return GoalProgressEventKind.entries.firstOrNull { it.wireValue == wire }
    ?: throw invalidGoalProgressEventSchemaError(sourceLabel, "event_kind", "unrecognized value '$wire'.")
}

private fun optionalProgressOutcome(
  reader: DurableArtifactMapReader,
  sourceLabel: String,
): GoalProgressOutcome {
  val outcomeWire = reader.optionalString("outcome") ?: return GoalProgressOutcome.NONE
  return GoalProgressOutcome.entries.firstOrNull { it.wireValue == outcomeWire }
    ?: throw invalidGoalProgressEventSchemaError(sourceLabel, "outcome", "unrecognized value '$outcomeWire'.")
}
