package skillbill.ports.telemetry.lifecycle

import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalIssueFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import java.time.Instant

interface GoalLifecycleTelemetryRepository {
  fun cancelGoalIssue(
    parentWorkflowId: String,
    issueKey: String,
    canceledAt: Instant,
  ): Boolean

  fun restartCanceledGoalIssue(
    parentWorkflowId: String,
    issueKey: String,
    restartedAt: Instant,
  ): Boolean

  fun goalStarted(
    record: GoalStartedRecord,
    level: String,
  )

  fun goalSubtaskFinished(
    record: GoalSubtaskFinishedRecord,
    level: String,
  )

  fun goalFinished(
    record: GoalFinishedRecord,
    level: String,
  )

  fun goalIssueFinished(
    record: GoalIssueFinishedRecord,
    level: String,
  )
}
