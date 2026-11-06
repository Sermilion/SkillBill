package skillbill.ports.workflow.goalworktree.model

sealed interface GoalBranchCheckout {
  data object CheckedOut : GoalBranchCheckout

  data class SkippedUncommittedChanges(val detail: String) : GoalBranchCheckout

  data class Failed(val detail: String) : GoalBranchCheckout
}
