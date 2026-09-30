package skillbill.ports.taskruntime.model

import skillbill.ports.taskruntime.FeatureTaskRuntimeExecutionPlanValidator

data class ValidatedFeatureTaskRuntimeExecutionPlan private constructor(
  private val canonicalJson: String,
) {
  fun encoded(): ByteArray = canonicalJson.toByteArray(Charsets.UTF_8)

  companion object {
    fun read(
      encoded: ByteArray,
      validator: FeatureTaskRuntimeExecutionPlanValidator,
    ): ValidatedFeatureTaskRuntimeExecutionPlan =
      ValidatedFeatureTaskRuntimeExecutionPlan(
        validator.canonicalize(encoded.copyOf(), "workflow creation").toString(Charsets.UTF_8),
      )
  }
}
