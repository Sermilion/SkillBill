package skillbill.ports.work.model

data class WorkGoalParentCandidate(
  val workflowId: String,
  val manifestStatus: String,
  val hasUnsettledSubtasks: Boolean,
)
