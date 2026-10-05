package skillbill.workflow.taskruntime.model.skeleton

import skillbill.workflow.model.ValidationDepth

data class ResolvedFeatureTaskRuntimeExecutionSettings(
  val validationDepth: ValidationDepth,
  val phaseTimeoutMillis: Long?,
) {
  init {
    val reason = violation(phaseTimeoutMillis)
    require(reason == null) { reason.orEmpty() }
  }

  companion object {
    fun violation(phaseTimeoutMillis: Long?): String? =
      if (phaseTimeoutMillis == null || phaseTimeoutMillis >= 0) null else "Failed requirement."
  }
}
