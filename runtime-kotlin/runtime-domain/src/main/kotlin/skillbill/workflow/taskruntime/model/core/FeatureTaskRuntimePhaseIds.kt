package skillbill.workflow.taskruntime.model.core

internal object FeatureTaskRuntimePhaseIds {
  const val INTAKE: String = "intake"
  const val PREPLAN: String = "preplan"
  const val PLAN: String = "plan"
  const val PLAN_SUBTASKS: String = "plan_subtasks"
  const val IMPLEMENT: String = "implement"
  const val SIMPLIFY: String = "simplify"
  const val IMPLEMENT_FIX: String = "implement_fix"
  const val REVIEW: String = "review"
  const val BUILD: String = "build"
  const val VERIFY_FINDINGS: String = "verify_findings"
  const val AUDIT_PLAN_FIX: String = "audit_plan_fix"
  const val AUDIT_IMPLEMENT_FIX: String = "audit_implement_fix"
  const val AUDIT: String = "audit"
  const val VALIDATE: String = "validate"
  const val WRITE_HISTORY: String = "write_history"
  const val COMMIT_PUSH: String = "commit_push"
  const val PR: String = "pr"
  const val MONITOR: String = "monitor"
  const val MONITOR_FIX: String = "monitor_fix"
  const val PRESENT_FINDINGS: String = "present_findings"

  private const val PLAN_SUBTASK_UNIT_SEPARATOR: String = ":"

  fun planSubtaskUnitRecordId(subtaskId: Int): String = "$PLAN_SUBTASKS$PLAN_SUBTASK_UNIT_SEPARATOR$subtaskId"

  fun planSubtaskUnitIdOf(key: String): Int? =
    key.takeIf { it.startsWith("$PLAN_SUBTASKS$PLAN_SUBTASK_UNIT_SEPARATOR") }
      ?.substringAfter(PLAN_SUBTASK_UNIT_SEPARATOR)
      ?.toIntOrNull()
      ?.takeIf { it > 0 }

  val all: List<String> =
    listOf(
      INTAKE,
      PREPLAN,
      PLAN,
      PLAN_SUBTASKS,
      IMPLEMENT,
      SIMPLIFY,
      AUDIT_PLAN_FIX,
      AUDIT_IMPLEMENT_FIX,
      AUDIT,
      REVIEW,
      VERIFY_FINDINGS,
      IMPLEMENT_FIX,
      BUILD,
      VALIDATE,
      WRITE_HISTORY,
      COMMIT_PUSH,
      PR,
      MONITOR,
      MONITOR_FIX,
      PRESENT_FINDINGS,
    )
}
