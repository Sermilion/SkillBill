package skillbill.goalrunner.model

import skillbill.contracts.workflow.identity.status.GOAL_PLANNING_WAVE_CAP

fun planningStatusSnapshot(
  orderedSubtaskIds: List<Int>,
  plannedIds: List<Int>,
  shared: Boolean,
  blockedSubtaskId: Int?,
  blockedReason: String?,
): GoalPlanningStatusSnapshot {
  val missing = orderedSubtaskIds.filterNot { it in plannedIds }
  val state = planningState(shared, plannedIds, missing, blockedReason)
  val wave = if (state.isPlanningInFlight()) missing.take(GOAL_PLANNING_WAVE_CAP) else emptyList()
  val resumeAt = wave.minOrNull() ?: missing.firstOrNull()
  return GoalPlanningStatusSnapshot(
    state = state,
    sharedPreplanPrepared = shared,
    plannedSubtaskCount = plannedIds.size,
    totalSubtaskCount = orderedSubtaskIds.size,
    currentPlanningSubtaskId = blockedSubtaskId ?: resumeAt,
    planningWaveSubtaskIds = wave,
    reason = planningReason(state, resumeAt, blockedReason),
  )
}

private fun planningState(
  shared: Boolean,
  plannedIds: List<Int>,
  missing: List<Int>,
  blockedReason: String?,
): GoalPlanningStatusState =
  when {
    blockedReason != null -> GoalPlanningStatusState.BLOCKED
    !shared -> GoalPlanningStatusState.NOT_STARTED
    missing.isEmpty() -> GoalPlanningStatusState.PREPARED
    plannedIds.isEmpty() -> GoalPlanningStatusState.PREPLANNED
    else -> GoalPlanningStatusState.PARTIALLY_PLANNED
  }

private fun GoalPlanningStatusState.isPlanningInFlight(): Boolean =
  this == GoalPlanningStatusState.PREPLANNED || this == GoalPlanningStatusState.PARTIALLY_PLANNED

private fun planningReason(
  state: GoalPlanningStatusState,
  resumeAt: Int?,
  blockedReason: String?,
): String? =
  when (state) {
    GoalPlanningStatusState.NOT_STARTED -> GoalPlanningStatusReasons.NOT_STARTED
    GoalPlanningStatusState.PREPLANNED -> GoalPlanningStatusReasons.preplannedResume(requireNotNull(resumeAt))
    GoalPlanningStatusState.PARTIALLY_PLANNED ->
      GoalPlanningStatusReasons.partiallyPlannedResume(requireNotNull(resumeAt))
    GoalPlanningStatusState.BLOCKED -> blockedReason
    GoalPlanningStatusState.PREPARED -> null
  }
