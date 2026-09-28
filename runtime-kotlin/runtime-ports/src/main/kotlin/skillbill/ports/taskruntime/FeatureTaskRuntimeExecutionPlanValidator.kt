package skillbill.ports.taskruntime

interface FeatureTaskRuntimeExecutionPlanValidator {
  fun read(
    encoded: ByteArray,
    sourceLabel: String,
  ): Map<String, Any?>

  fun write(
    payload: Map<String, Any?>,
    sourceLabel: String,
  ): ByteArray

  fun validate(
    payload: Map<String, Any?>,
    sourceLabel: String,
  )
}
