package skillbill.workflow.taskruntime.model.core

internal object FeatureTaskRuntimePhaseIds {
  const val PREPLAN: String = "preplan"
  const val PLAN: String = "plan"
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

  val all: List<String> =
    listOf(
      PREPLAN,
      PLAN,
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
    )
}
