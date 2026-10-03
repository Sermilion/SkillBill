package skillbill.engine.goalrunner.planning.recovery

import skillbill.engine.recovery.staleChildPlanningRecoveryCommand
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.FeatureTaskRuntimeMigrationFailureCode
import skillbill.error.shellcontent.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.shellcontent.InvalidGoalPlanningPreparationSchemaError

internal enum class GoalPlanningRecoveryKind {
  HARD_RESET,
  SCOPED_REPLAN,
  BLOCKED,
}

fun goalPlanningHardResetRemedy(issueKey: String): String = "skill-bill goal reset $issueKey --hard --yes"

internal fun classifyGoalPlanningRecovery(cause: Throwable?): GoalPlanningRecoveryKind {
  var current = cause
  while (current != null) {
    if ((current as? SkillBillRuntimeException)?.code is FeatureTaskRuntimeMigrationFailureCode) {
      return GoalPlanningRecoveryKind.BLOCKED
    }
    if (current is InvalidGoalPlanningPreparationSchemaError ||
      current is InvalidFeatureTaskRuntimePhaseOutputSchemaError
    ) {
      return GoalPlanningRecoveryKind.BLOCKED
    }
    current = current.cause
  }
  return GoalPlanningRecoveryKind.SCOPED_REPLAN
}

internal fun classifyGoalPlanningRecovery(
  error: IncompatibleGoalPlanningPreparationRecoveryError,
): GoalPlanningRecoveryKind = classifyGoalPlanningRecovery(error.cause)

fun goalPlanningChildImportConflictBlockedReason(
  issueKey: String,
  subtaskId: Int,
  error: IncompatibleGoalPlanningPreparationRecoveryError,
): String {
  val kind = classifyGoalPlanningRecovery(error)
  val detail = error.reason.ifBlank { error.message.orEmpty() }
  return when (kind) {
    GoalPlanningRecoveryKind.HARD_RESET ->
      "Goal-subtask planning is incompatible with the current runtime. Keep the workflow and checkpoints " +
        "intact and repair the incompatible record before resuming. Planning failure: $detail"
    GoalPlanningRecoveryKind.SCOPED_REPLAN ->
      "Goal-subtask planning import conflicts with the stored shared preplan or subtask plan. " +
        "This occurs when a shared preplan was regenerated after the child was hydrated, " +
        "making the previously-imported planning bytes stale. " +
        "Recover this subtask's child without discarding sibling planning or completed commits: " +
        "'${staleChildPlanningRecoveryCommand(issueKey, subtaskId)}'. " +
        "Planning failure: $detail"
    GoalPlanningRecoveryKind.BLOCKED ->
      "Stored goal planning failed contract validation. Keep the workflow and its checkpoints intact, " +
        "then retry after a runtime with migration support is available. Planning failure: $detail"
  }
}
