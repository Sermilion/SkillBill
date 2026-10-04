package skillbill.workflow.taskruntime.model.skeleton

import skillbill.workflow.model.ValidationDepth

data class ResolvedFeatureTaskRuntimeExecutionSettings(
  val validationDepth: ValidationDepth,
  val phaseTimeoutMillis: Long?,
) {
  init {
    require(phaseTimeoutMillis == null || phaseTimeoutMillis >= 0)
  }
}
