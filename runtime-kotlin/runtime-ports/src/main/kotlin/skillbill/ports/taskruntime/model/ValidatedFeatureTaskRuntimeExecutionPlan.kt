package skillbill.ports.taskruntime.model

import skillbill.ports.taskruntime.FeatureTaskRuntimeExecutionPlanValidator
import java.util.Collections

class ValidatedFeatureTaskRuntimeExecutionPlan private constructor(
  val artifactValue: Map<String, Any?>,
) {
  companion object {
    fun read(
      encoded: ByteArray,
      validator: FeatureTaskRuntimeExecutionPlanValidator,
    ): ValidatedFeatureTaskRuntimeExecutionPlan =
      ValidatedFeatureTaskRuntimeExecutionPlan(freezeMap(validator.read(encoded.copyOf(), "workflow creation")))

    private fun freezeMap(value: Map<String, Any?>): Map<String, Any?> =
      Collections.unmodifiableMap(value.mapValues { freeze(it.value) })

    private fun freeze(value: Any?): Any? = when (value) {
      is Map<*, *> -> Collections.unmodifiableMap(value.mapValues { freeze(it.value) })
      is List<*> -> Collections.unmodifiableList(value.map(::freeze))
      else -> value
    }
  }
}
