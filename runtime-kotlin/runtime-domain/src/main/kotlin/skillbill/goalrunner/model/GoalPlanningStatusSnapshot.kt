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
  return planningStatusSnapshot(
    GoalPlanningStatusSummary(orderedSubtaskIds.size, plannedIds.size, missing.take(GOAL_PLANNING_WAVE_CAP)),
    shared,
    blockedSubtaskId,
    blockedReason,
  )
}

fun planningStatusSnapshot(
  summary: GoalPlanningStatusSummary,
  shared: Boolean,
  blockedSubtaskId: Int?,
  blockedReason: String?,
): GoalPlanningStatusSnapshot {
  val state = planningState(shared, summary.preparedCount, summary.firstMissingIds, blockedReason)
  val wave = if (state.isPlanningInFlight()) summary.firstMissingIds.take(GOAL_PLANNING_WAVE_CAP) else emptyList()
  val resumeAt = wave.minOrNull() ?: summary.firstMissingIds.firstOrNull()
  return GoalPlanningStatusSnapshot(
    state = state,
    sharedPreplanPrepared = shared,
    plannedSubtaskCount = summary.preparedCount,
    totalSubtaskCount = summary.totalCount,
    currentPlanningSubtaskId = blockedSubtaskId ?: resumeAt,
    planningWaveSubtaskIds = wave,
    reason = planningReason(state, resumeAt, blockedReason),
  )
}

private fun planningState(
  shared: Boolean,
  plannedCount: Int,
  missing: List<Int>,
  blockedReason: String?,
): GoalPlanningStatusState =
  when {
    blockedReason != null -> GoalPlanningStatusState.BLOCKED
    !shared -> GoalPlanningStatusState.NOT_STARTED
    missing.isEmpty() -> GoalPlanningStatusState.PREPARED
    plannedCount == 0 -> GoalPlanningStatusState.PREPLANNED
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
