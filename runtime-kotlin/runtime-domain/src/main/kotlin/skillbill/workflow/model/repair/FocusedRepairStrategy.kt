package skillbill.workflow.model.repair

object FocusedRepairStrategy {
  const val MAX_UNSUCCESSFUL_OUTCOMES: Int = 3

  fun next(
    targets: List<RepairTargetProgress>,
    stage: FocusedRepairStage,
  ): FocusedRepairAction {
    val current = targets.firstOrNull()
    return when {
      stage == FocusedRepairStage.DISCOVERY -> FocusedRepairAction.Discover
      current == null -> FocusedRepairAction.Accepted
      stage == FocusedRepairStage.VERIFICATION -> FocusedRepairAction.Verify(current.ref)
      exhausted(current.unsuccessfulOutcomes) -> FocusedRepairAction.Blocked(current.ref)
      stage == FocusedRepairStage.PLANNING -> FocusedRepairAction.Plan(current.ref)
      else -> FocusedRepairAction.Implement(current.ref)
    }
  }

  fun exhausted(unsuccessfulOutcomes: Int): Boolean = unsuccessfulOutcomes >= MAX_UNSUCCESSFUL_OUTCOMES

  fun recordUnsuccessfulOutcome(previous: Int): Int? = (previous + 1).takeIf { it in 1..MAX_UNSUCCESSFUL_OUTCOMES }
}
