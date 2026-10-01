package skillbill.infrastructure.sqlite.telemetry.lifecycle.session

import skillbill.infrastructure.sqlite.telemetry.goal.GoalFinishedSaveOutcome
import skillbill.infrastructure.sqlite.telemetry.goal.GoalIssueIdentity
import skillbill.infrastructure.sqlite.telemetry.goal.GoalIssueSegmentStart
import skillbill.infrastructure.sqlite.telemetry.goal.GoalStartedSaveOutcome
import skillbill.infrastructure.sqlite.telemetry.goal.emitGoalFinished
import skillbill.infrastructure.sqlite.telemetry.goal.emitGoalIssueFinished
import skillbill.infrastructure.sqlite.telemetry.goal.emitGoalStarted
import skillbill.infrastructure.sqlite.telemetry.goal.emitGoalSubtaskFinished
import skillbill.infrastructure.sqlite.telemetry.goal.recordGoalIssueSegmentEnd
import skillbill.infrastructure.sqlite.telemetry.goal.recordGoalIssueSegmentStarted
import skillbill.infrastructure.sqlite.telemetry.goal.saveGoalFinished
import skillbill.infrastructure.sqlite.telemetry.goal.saveGoalIssueFinished
import skillbill.infrastructure.sqlite.telemetry.goal.saveGoalStarted
import skillbill.infrastructure.sqlite.telemetry.goal.saveGoalSubtaskFinished
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.telemetry.lifecycle.GoalLifecycleTelemetryRepository
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalIssueFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import java.sql.Connection

internal class LifecycleTelemetryGoalSessionAdapter(
  private val connection: Connection,
  private val runtimeVersion: String,
  private val diagnostics: RuntimeDiagnostics,
) : GoalLifecycleTelemetryRepository {
  override fun goalStarted(
    record: GoalStartedRecord,
    level: String,
  ) {
    val outcome = saveGoalStarted(connection, record)
    if (outcome == GoalStartedSaveOutcome.INSERTED) {
      record.parentWorkflowId
        ?.takeIf(String::isNotBlank)?.let { parentWorkflowId ->
          recordGoalIssueSegmentStarted(
            connection = connection,
            segment =
              GoalIssueSegmentStart(
                parentWorkflowId = parentWorkflowId,
                issueKey = record.issueKey,
                workflowId = record.workflowId,
                startedAt = record.startedAt,
                resumed = record.resumed,
                mode = record.mode,
              ),
          )
        }
    }
    emitGoalStarted(connection, runtimeVersion, record.workflowId, level)
  }

  override fun goalSubtaskFinished(
    record: GoalSubtaskFinishedRecord,
    level: String,
  ) {
    saveGoalSubtaskFinished(connection, record)
    emitGoalSubtaskFinished(connection, runtimeVersion, record, level, diagnostics)
  }

  override fun goalFinished(
    record: GoalFinishedRecord,
    level: String,
  ) {
    val outcome = saveGoalFinished(connection, record)
    if (outcome == GoalFinishedSaveOutcome.FIRST_TERMINAL && record.status != "completed") {
      record.parentWorkflowId?.takeIf(String::isNotBlank)?.let { parentWorkflowId ->
        recordGoalIssueSegmentEnd(
          connection,
          parentWorkflowId,
          record.issueKey,
          record.workflowId,
          record.status,
        )
      }
    }
    emitGoalFinished(connection, runtimeVersion, record.workflowId, level)
  }

  override fun goalIssueFinished(
    record: GoalIssueFinishedRecord,
    level: String,
  ) {
    if (saveGoalIssueFinished(connection, record, diagnostics).persisted) {
      val goal = GoalIssueIdentity(record.parentWorkflowId, record.issueKey)
      emitGoalIssueFinished(connection, runtimeVersion, goal, level, diagnostics)
    }
  }
}
