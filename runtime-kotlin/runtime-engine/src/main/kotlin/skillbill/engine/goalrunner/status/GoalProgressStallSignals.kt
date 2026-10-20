package skillbill.engine.goalrunner.status

import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.engine.goalrunner.monitoring.isCiWaitingOperation
import skillbill.goalrunner.ledger.parseInstantOrNull
import skillbill.idestatus.model.WorktreeEditSummary
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import java.time.Instant

internal data class GoalProgressStallSignals(
  val progress: GoalRunnerWorkflowProgress?,
  val agentActivityStamps: List<Instant>,
  val latestWorktreeEdit: WorktreeEditSummary?,
) {
  fun latestProgressAt(): Instant? =
    (
      agentActivityStamps +
        listOfNotNull(
          progress?.latestDurableProgressEvent?.timestamp?.let(::parseInstantOrNull),
          progress?.latestDeclaredProgressEvent?.takeIf(::reportsProgress)?.timestamp,
          latestWorktreeEdit?.recordedAt,
          progress?.currentStepEnteredAt,
        )
    ).maxOrNull()

  private fun reportsProgress(event: GoalProgressEvent): Boolean =
    event.eventKind != GoalProgressEventKind.OPERATION_HEARTBEAT || isCiWaitingOperation(event.operationName)
}
