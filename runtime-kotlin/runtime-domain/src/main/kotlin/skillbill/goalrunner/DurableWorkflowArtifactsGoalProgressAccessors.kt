package skillbill.goalrunner

import skillbill.contracts.JsonCodec
import skillbill.error.shellcontent.invalidGoalProgressEventSchemaError
import skillbill.goalrunner.ledger.declaredProgressEventFrom
import skillbill.goalrunner.ledger.decodeDeclaredGoalProgressEvent
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.GOAL_PROGRESS_RUN_HISTORY_ARTIFACT_KEY
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import java.time.Instant

internal fun DurableWorkflowArtifacts.goalProgressLatestEvent(): GoalProgressEvent? = declaredProgressEventFrom(this)

internal fun DurableWorkflowArtifacts.goalProgressHistory(): List<GoalProgressEvent> {
  if (!containsKey(GOAL_PROGRESS_RUN_HISTORY_ARTIFACT_KEY)) return emptyList()
  val raw =
    JsonCodec.anyToStringAnyMapList(this[GOAL_PROGRESS_RUN_HISTORY_ARTIFACT_KEY])
      ?: throw invalidGoalProgressEventSchemaError(
        GOAL_PROGRESS_RUN_HISTORY_ARTIFACT_KEY,
        "<root>",
        "must be an array.",
      )
  return raw.mapIndexed { index, event ->
    event.decodeDeclaredGoalProgressEvent("$GOAL_PROGRESS_RUN_HISTORY_ARTIFACT_KEY[$index]")
  }
}

fun DurableWorkflowArtifacts.goalProgressCurrentStepEnteredAt(): Instant? =
  runCatching { goalProgressHistory() }.getOrNull()?.let(::currentStepEnteredAt)

internal fun currentStepEnteredAt(history: List<GoalProgressEvent>): Instant? {
  val ordered = history.sortedBy(GoalProgressEvent::sequenceNumber)
  val currentStep = ordered.lastOrNull()?.reportedStep() ?: return null
  val previousStepIndex = ordered.indexOfLast { it.reportedStep() != currentStep }
  if (previousStepIndex < 0) return null
  return ordered[previousStepIndex + 1].timestamp
}

private fun GoalProgressEvent.reportedStep(): String = stepId ?: workflowPhase
