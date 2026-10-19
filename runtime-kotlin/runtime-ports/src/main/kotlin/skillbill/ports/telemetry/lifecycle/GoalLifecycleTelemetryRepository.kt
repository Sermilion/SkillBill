package skillbill.ports.telemetry.lifecycle

import skillbill.ports.telemetry.lifecycle.model.GoalChildSubtaskIdentity
import skillbill.ports.telemetry.lifecycle.model.GoalStartedPreparation
import skillbill.ports.telemetry.lifecycle.model.GoalStartedSupersession
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

  fun prepareGoalStarted(record: GoalStartedRecord): GoalStartedPreparation

  fun persistedChildSubtaskStatuses(
    issueKey: String,
    children: List<GoalChildSubtaskIdentity>,
  ): Map<Int, String>

  fun reconcileGoalStarted(
    record: GoalStartedRecord,
    inserted: Boolean,
    candidates: List<GoalStartedSupersession>,
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
