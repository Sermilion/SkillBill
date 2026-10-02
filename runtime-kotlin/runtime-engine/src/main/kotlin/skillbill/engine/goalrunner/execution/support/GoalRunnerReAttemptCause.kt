package skillbill.engine.goalrunner.execution.support

import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.engine.recovery.DurableChildRecoveryClass
import skillbill.engine.recovery.classifyDurableChild
import skillbill.engine.recovery.recommendedDurableChildRecoveryCommand
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

fun confirmedAliveKillDiagnosticClass(liveness: GoalRunnerLivenessSnapshot?): String? =
  if (liveness?.aliveAtKill == true) GoalRunnerLaunchFacts.DIAGNOSTIC_CLASS_CONFIRMED_ALIVE_KILL else null

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
