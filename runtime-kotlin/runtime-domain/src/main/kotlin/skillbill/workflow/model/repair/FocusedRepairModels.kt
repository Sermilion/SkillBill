package skillbill.workflow.model.repair

data class RepairTargetProgress(
  val ref: String,
  val unsuccessfulOutcomes: Int,
  val pendingIndependentCheck: Boolean = false,
  val evidenceMissing: Boolean = false,
  val escalationRequired: Boolean = false,
  val escalatedOpportunityOwed: Boolean = false,
)

enum class FocusedRepairStage { DISCOVERY, PLANNING, IMPLEMENTATION, VERIFICATION }

sealed interface FocusedRepairAction {
  data object Discover : FocusedRepairAction

  data class Plan(val targetRef: String, val escalationRequired: Boolean = false) : FocusedRepairAction

  data class Implement(val targetRef: String, val escalationRequired: Boolean = false) : FocusedRepairAction

  data class Verify(val targetRef: String) : FocusedRepairAction

  data class Blocked(val targetRef: String) : FocusedRepairAction

  data object Accepted : FocusedRepairAction
}
