package skillbill.engine.goalrunner.planning.remedies

import skillbill.application.rethrowIfCooperativeCancellationOrInterruption
import skillbill.engine.goalrunner.planning.recovery.GoalPlanningProvenanceRecoverability
import skillbill.engine.goalrunner.planning.recovery.GoalPlanningRecoveryKind
import skillbill.engine.goalrunner.planning.recovery.classifyGoalPlanningRecovery
import skillbill.engine.goalrunner.planning.recovery.goalPlanningHardResetRemedy
import skillbill.error.shellcontent.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.goalrunner.model.GoalPlanningStatusReasons
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus

fun goalPlanningIncludeSharedPreplanRemedy(
  issueKey: String,
  subtaskId: Int,
): String = "skill-bill goal replan $issueKey --subtask $subtaskId --include-shared-preplan"

fun goalPlanningRemedySubtaskId(subtasks: List<DecompositionSubtask>): Int? =
  subtasks.firstOrNull {
    it.status.decompositionStatus() !in setOf(DecompositionStatus.COMPLETE, DecompositionStatus.SKIPPED)
  }?.id

private fun recoverySuffix(
  issueKey: String,
  subtaskId: Int?,
  kind: GoalPlanningRecoveryKind,
): String =
  when (kind) {
    GoalPlanningRecoveryKind.HARD_RESET ->
      "Recover with: ${goalPlanningHardResetRemedy(issueKey)}"
    GoalPlanningRecoveryKind.SCOPED_REPLAN ->
      if (subtaskId == null) {
        "No subtask is replannable, so reset the goal before planning can be repaired."
      } else {
        "Recover with: ${goalPlanningIncludeSharedPreplanRemedy(issueKey, subtaskId)}"
      }
    GoalPlanningRecoveryKind.BLOCKED ->
      "Keep the workflow and its checkpoints intact, then retry after migration support is available."
  }

internal fun goalPlanningIncompatibleProvenanceStopReason(
  issueKey: String,
  subtaskId: Int?,
  kind: GoalPlanningRecoveryKind,
): String =
  when (kind) {
    GoalPlanningRecoveryKind.HARD_RESET ->
      "Goal planning uses a contract unsupported by this runtime. Keep the workflow and checkpoints intact, " +
        "then retry with a compatible runtime or an explicitly reviewed migration."
    GoalPlanningRecoveryKind.SCOPED_REPLAN ->
      "Goal planning shared preplan provenance is incompatible with the current governed inputs. " +
        recoverySuffix(issueKey, subtaskId, kind)
    GoalPlanningRecoveryKind.BLOCKED ->
      "Goal planning preparation failed contract validation. " + recoverySuffix(issueKey, subtaskId, kind)
  }

fun goalPlanningMissingSharedContextPacketStopReason(
  issueKey: String,
  subtaskId: Int?,
): String =
  "Goal planning shared preplan does not contain a valid shared context packet. " +
    recoverySuffix(issueKey, subtaskId, GoalPlanningRecoveryKind.SCOPED_REPLAN)

fun goalPlanningPreparationStateReadStopReason(
  error: Throwable,
  issueKey: String,
  subtaskId: Int?,
): String {
  val recovery =
    error as? IncompatibleGoalPlanningPreparationRecoveryError
      ?: return "Goal planning preparation state could not be read: ${error.message.orEmpty()}"
  return goalPlanningPreparationStateReadStopReason(
    recovery.reason,
    recovery.subtaskId,
    issueKey,
    subtaskId,
    classifyGoalPlanningRecovery(recovery),
  )
}

internal fun goalPlanningPreparationStateReadStopReason(
  reason: String,
  recordedSubtaskId: Int,
  issueKey: String,
  subtaskId: Int?,
  kind: GoalPlanningRecoveryKind,
): String {
  val remedySubtaskId = subtaskId?.takeIf { it > 0 } ?: recordedSubtaskId.takeIf { it > 0 }
  return "Goal planning preparation state could not be read: $reason. " +
    recoverySuffix(issueKey, remedySubtaskId, kind)
}

internal fun goalPlanningNonResumableStatusReason(
  issueKey: String,
  subtaskId: Int?,
  kind: GoalPlanningRecoveryKind,
): String =
  "Saved planning is not resumable until provenance is repaired. " +
    recoverySuffix(issueKey, subtaskId, kind)

internal fun statusRecoverabilityOrRefuse(
  classify: () -> GoalPlanningProvenanceRecoverability,
): GoalPlanningProvenanceRecoverability =
  runCatching(classify).getOrElse { error ->
    error.rethrowIfCooperativeCancellationOrInterruption()
    GoalPlanningProvenanceRecoverability.Irrecoverable(
      (error as? IncompatibleGoalPlanningPreparationRecoveryError)?.let(::classifyGoalPlanningRecovery)
        ?: classifyGoalPlanningRecovery(error),
    )
  }

internal fun alignPlanningStatusWithLaunchRecoverability(
  snapshot: GoalPlanningStatusSnapshot,
  recoverability: GoalPlanningProvenanceRecoverability,
  issueKey: String,
  remedySubtaskId: Int?,
): GoalPlanningStatusSnapshot {
  if (recoverability !is GoalPlanningProvenanceRecoverability.Irrecoverable) return snapshot
  if (
    snapshot.state != GoalPlanningStatusState.PREPLANNED &&
    snapshot.state != GoalPlanningStatusState.PARTIALLY_PLANNED
  ) {
    return snapshot
  }
  if (!GoalPlanningStatusReasons.claimsResume(snapshot.reason)) return snapshot
  return snapshot.copy(
    planningWaveSubtaskIds = emptyList(),
    reason = goalPlanningNonResumableStatusReason(issueKey, remedySubtaskId, recoverability.recoveryKind),
  )
}
