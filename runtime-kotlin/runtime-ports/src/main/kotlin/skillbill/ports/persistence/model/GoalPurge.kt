package skillbill.ports.persistence.model

data class GoalPurgeTarget(
  val parentWorkflowIds: Set<String>,
  val workflowIds: Set<String>,
) {
  val isEmpty: Boolean get() = parentWorkflowIds.isEmpty() && workflowIds.isEmpty()
}

data class GoalPurgeTableCounts(
  val byTable: Map<String, Int> = emptyMap(),
)
