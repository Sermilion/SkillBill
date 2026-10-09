package skillbill.ports.workflow.goalstate.model

sealed interface GoalRuntimeDirectoryDeletion {
  data object Deleted : GoalRuntimeDirectoryDeletion

  data object Absent : GoalRuntimeDirectoryDeletion

  data class Failed(val reason: String) : GoalRuntimeDirectoryDeletion
}
