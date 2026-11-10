package skillbill.workflow.taskruntime.model.audit

import skillbill.workflow.model.repair.FocusedRepairStrategy

sealed interface AcceptanceRepairStep {
  data object Discover : AcceptanceRepairStep

  data object ReadOnlyAudit : AcceptanceRepairStep

  data class Plan(val criterionRef: String) : AcceptanceRepairStep

  data class Repair(val criterionRef: String) : AcceptanceRepairStep

  data class Verify(val criterionRef: String) : AcceptanceRepairStep

  data class Blocked(val criterionRef: String) : AcceptanceRepairStep

  data object Accepted : AcceptanceRepairStep
}

object AcceptanceRepairDecision {
  fun auditKind(state: AcceptanceCriterionRepairState): AcceptanceRepairStep = next(state.clearPendingClarification())

  fun discoveryCriteria(state: AcceptanceCriterionRepairState): List<AcceptanceCriterionRepairCriterion> =
    state.criteria.filter { criterion ->
      criterion.sourceDisposition == AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED &&
        criterion.gaps.none { it.status == AcceptanceCriterionGapStatus.OPEN }
    }.sortedBy { it.ordinal }

  fun discoveryRefs(state: AcceptanceCriterionRepairState): Set<String> =
    discoveryCriteria(state).map { it.canonicalRef }.toSet()

  fun next(state: AcceptanceCriterionRepairState): AcceptanceRepairStep =
    when {
      state.stage == AcceptanceCriterionRepairStage.ACCEPTED -> AcceptanceRepairStep.Accepted
      state.stage == AcceptanceCriterionRepairStage.INTERACTION_TRACE_REQUIRED -> AcceptanceRepairStep.ReadOnlyAudit
      state.pendingClarificationQuestion != null -> AcceptanceRepairStep.ReadOnlyAudit
      state.stage == AcceptanceCriterionRepairStage.BUDGET_BLOCKED ->
        AcceptanceRepairStep.Blocked(checkNotNull(state.currentCriterionRef))
      state.stage == AcceptanceCriterionRepairStage.REPAIR_RESERVED ->
        AcceptanceRepairStep.Repair(checkNotNull(state.currentCriterionRef))
      state.stage == AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION ->
        AcceptanceRepairStep.Verify(checkNotNull(state.currentCriterionRef))
      state.reservation == null && discoveryCriteria(state).isNotEmpty() -> AcceptanceRepairStep.Discover
      state.stage == AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED -> AcceptanceRepairStep.ReadOnlyAudit
      state.stage == AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED && state.currentCriterionRef != null ->
        plannedStep(state)
      else -> AcceptanceRepairStep.Accepted
    }

  private fun plannedStep(state: AcceptanceCriterionRepairState): AcceptanceRepairStep =
    state.criteria.singleOrNull { it.canonicalRef == state.currentCriterionRef }?.let { criterion ->
      val charged = state.chargedAttempts.getOrDefault(criterion.canonicalRef, 0)
      when {
        charged >= FocusedRepairStrategy.MAX_UNSUCCESSFUL_OUTCOMES ->
          AcceptanceRepairStep.Blocked(criterion.canonicalRef)
        criterion.planGeneration == 0 || criterion.escalated && criterion.planGeneration < 2 ->
          AcceptanceRepairStep.Plan(criterion.canonicalRef)
        else -> AcceptanceRepairStep.Repair(criterion.canonicalRef)
      }
    } ?: AcceptanceRepairStep.Accepted
}
