package skillbill.workflow.taskruntime.model.skeleton

import skillbill.error.featuretask.UnknownPhaseStepError
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePhaseIds

enum class PhaseSlot(val wireValue: String, val steps: List<String>) {
  PREPLAN("preplan", listOf(FeatureTaskRuntimePhaseIds.PREPLAN)),
  PLAN("plan", listOf(FeatureTaskRuntimePhaseIds.PLAN)),
  IMPLEMENTATION("implementation", listOf(FeatureTaskRuntimePhaseIds.IMPLEMENT, FeatureTaskRuntimePhaseIds.SIMPLIFY)),
  AUDIT(
    "audit",
    listOf(
      FeatureTaskRuntimePhaseIds.AUDIT_PLAN_FIX,
      FeatureTaskRuntimePhaseIds.AUDIT_IMPLEMENT_FIX,
      FeatureTaskRuntimePhaseIds.AUDIT,
    ),
  ),
  CODE_REVIEW(
    "code_review",
    listOf(
      FeatureTaskRuntimePhaseIds.REVIEW,
      FeatureTaskRuntimePhaseIds.VERIFY_FINDINGS,
      FeatureTaskRuntimePhaseIds.IMPLEMENT_FIX,
    ),
  ),
  QUALITY_GATE("quality_gate", listOf(FeatureTaskRuntimePhaseIds.BUILD, FeatureTaskRuntimePhaseIds.VALIDATE)),
  WRITE_HISTORY("write_history", listOf(FeatureTaskRuntimePhaseIds.WRITE_HISTORY)),
  COMMIT_PUSH("commit_push", listOf(FeatureTaskRuntimePhaseIds.COMMIT_PUSH)),
  PULL_REQUEST("pull_request", listOf(FeatureTaskRuntimePhaseIds.PR)),
  ;

  companion object {
    fun slotForStep(stepId: String): PhaseSlot =
      entries.firstOrNull { stepId in it.steps } ?: throw UnknownPhaseStepError(stepId)
  }
}
