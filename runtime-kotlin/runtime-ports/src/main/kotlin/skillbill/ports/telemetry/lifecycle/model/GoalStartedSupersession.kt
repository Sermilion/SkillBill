package skillbill.ports.telemetry.lifecycle.model

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
