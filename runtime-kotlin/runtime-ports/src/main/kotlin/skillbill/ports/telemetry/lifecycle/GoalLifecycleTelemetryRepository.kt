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

  fun prepareGoalStarted(record: GoalStartedRecord): GoalStartedPreparation =
    GoalStartedPreparation(inserted = false, candidates = emptyList(), ownershipEvidence = emptyList())

  fun persistedChildSubtaskStatuses(
    issueKey: String,
    children: List<GoalChildSubtaskIdentity>,
  ): Map<Int, String> = emptyMap()

  fun reconcileGoalStarted(
    record: GoalStartedRecord,
    inserted: Boolean,
    candidates: List<GoalStartedSupersession>,
    level: String,
  ) {
    if (inserted) goalStarted(record, level)
  }

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

data class GoalStartedPreparation(
  val inserted: Boolean,
  val candidates: List<GoalStartedCandidate>,
  val ownershipEvidence: List<GoalStartedOwnershipEvidence>,
)

data class GoalStartedCandidate(
  val workflowId: String,
  val parentWorkflowId: String?,
  val startedAt: String,
  val latestSegmentParentWorkflowId: String?,
  val mode: String,
)

data class GoalStartedOwnershipEvidence(
  val workflowId: String,
  val parentWorkflowId: String?,
  val latestSegmentParentWorkflowId: String?,
  val status: String?,
  val finishedAt: String?,
)

data class GoalStartedSupersession(
  val candidate: GoalStartedCandidate,
  val eligible: Boolean,
  val complete: Int,
  val blocked: Int,
  val skipped: Int,
  val diagnosticReason: String? = null,
  val confirmedNonLiveOwner: Boolean = false,
)

data class GoalChildSubtaskIdentity(val subtaskId: Int, val childWorkflowId: String)
