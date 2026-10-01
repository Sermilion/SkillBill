package skillbill.engine.goalrunner.execution.support

import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.engine.goalrunner.persist.DurableChildRecoveryClass
import skillbill.engine.goalrunner.persist.classifyDurableChild
import skillbill.engine.goalrunner.persist.recommendedDurableChildRecoveryCommand
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.GoalRunnerLaunchFacts
import skillbill.goalrunner.model.GoalRunnerLivenessSnapshot
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

fun reAttemptCauseFor(
  reason: GoalRunnerStopReason,
  childLoopIterations: Map<String, Int>,
): String? {
  val hasRegeneration =
    childLoopIterations.keys.any {
      FeatureTaskRuntimePhaseWorkflowDefinition.isRegenerationLoopId(it)
    }
  return when {
    reason == GoalRunnerStopReason.RECONCILED_RESUMABLE && hasRegeneration -> "regeneration"
    reason == GoalRunnerStopReason.RECONCILED_RESUMABLE -> "crash_resume"
    childLoopIterations.isNotEmpty() -> "backward_edge"
    else -> null
  }
}

fun causingLoopEntryFor(childLoopIterations: Map<String, Int>): String? =
  childLoopIterations.entries
    .sortedWith(compareBy({ loopReAttemptPriority(it.key) }, { it.key }))
    .firstOrNull()
    ?.let { (loopId, edgeIteration) -> "$loopId:$edgeIteration" }

fun loopReAttemptPriority(loopId: String): Int =
  if (FeatureTaskRuntimePhaseWorkflowDefinition.isRegenerationLoopId(loopId)) 0 else 1

fun GoalRunnerStopReason.toLedgerAction(): GoalAttemptLedgerAction =
  when (this) {
    GoalRunnerStopReason.TIMEOUT -> GoalAttemptLedgerAction.TIMEOUT
    GoalRunnerStopReason.INTERRUPTED -> GoalAttemptLedgerAction.INTERRUPTION
    GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME -> GoalAttemptLedgerAction.RETRY
    GoalRunnerStopReason.FAILED,
    GoalRunnerStopReason.BLOCKED,
    GoalRunnerStopReason.POLICY_BLOCKED,
    GoalRunnerStopReason.PULL_REQUEST_FAILED,
    GoalRunnerStopReason.DEPENDENCIES_BLOCKED,
    GoalRunnerStopReason.RECONCILED_RESUMABLE,
    GoalRunnerStopReason.AWAITING_OPERATOR_DECISION,
    GoalRunnerStopReason.PAUSED,
    -> GoalAttemptLedgerAction.FINAL_RECONCILED_OUTCOME
  }

fun GoalRunnerStopReason.toDiagnosticClass(): String =
  when (this) {
    GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME -> "no_terminal_workflow_state"
    GoalRunnerStopReason.FAILED -> "malformed_result_json"
    GoalRunnerStopReason.TIMEOUT,
    GoalRunnerStopReason.INTERRUPTED,
    GoalRunnerStopReason.BLOCKED,
    -> "child_process_failed"
    GoalRunnerStopReason.POLICY_BLOCKED,
    GoalRunnerStopReason.PULL_REQUEST_FAILED,
    GoalRunnerStopReason.DEPENDENCIES_BLOCKED,
    GoalRunnerStopReason.RECONCILED_RESUMABLE,
    GoalRunnerStopReason.AWAITING_OPERATOR_DECISION,
    GoalRunnerStopReason.PAUSED,
    -> name.lowercase()
  }

fun confirmedAliveKillDiagnosticClass(liveness: GoalRunnerLivenessSnapshot?): String? =
  if (liveness?.aliveAtKill == true) GoalRunnerLaunchFacts.DIAGNOSTIC_CLASS_CONFIRMED_ALIVE_KILL else null

fun GoalRunnerStopReason.nextSafeAction(): String =
  when (this) {
    GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME,
    GoalRunnerStopReason.TIMEOUT,
    GoalRunnerStopReason.INTERRUPTED,
    GoalRunnerStopReason.RECONCILED_RESUMABLE,
    GoalRunnerStopReason.AWAITING_OPERATOR_DECISION,
    GoalRunnerStopReason.PAUSED,
    -> "resume_from_last_resumable_step"
    GoalRunnerStopReason.FAILED -> "inspect_child_output_then_resume"
    GoalRunnerStopReason.BLOCKED,
    GoalRunnerStopReason.POLICY_BLOCKED,
    GoalRunnerStopReason.PULL_REQUEST_FAILED,
    GoalRunnerStopReason.DEPENDENCIES_BLOCKED,
    -> "inspect_blocked_reason"
  }

fun recoverySafeAction(
  issueKey: String,
  subtaskId: Int,
  progress: GoalRunnerWorkflowProgress?,
  fallback: String,
  subtaskStatus: DecompositionStatus?,
): String =
  when (classifyDurableChild(progress)) {
    DurableChildRecoveryClass.RESUMABLE -> "resume_from_last_resumable_step"
    DurableChildRecoveryClass.INCOMPATIBLE_TERMINAL ->
      recommendedDurableChildRecoveryCommand(issueKey, subtaskId, subtaskStatus, progress)
    DurableChildRecoveryClass.ABSENT,
    DurableChildRecoveryClass.ACTIVE,
    -> fallback
  }
