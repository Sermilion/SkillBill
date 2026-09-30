package skillbill.ports.taskruntime

interface FeatureTaskRuntimeExecutionPlanValidator {
  fun canonicalize(
    encoded: ByteArray,
    sourceLabel: String,
  ): ByteArray
}
