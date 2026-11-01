package skillbill.ports.taskruntime.executionplan

interface FeatureTaskRuntimeExecutionPlanValidator {
  fun canonicalize(
    encoded: ByteArray,
    sourceLabel: String,
  ): ByteArray
}
