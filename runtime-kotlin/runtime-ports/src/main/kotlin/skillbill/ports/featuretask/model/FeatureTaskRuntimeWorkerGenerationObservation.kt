package skillbill.ports.featuretask.model

data class FeatureTaskRuntimeWorkerGenerationObservation(
  val workflowId: String,
  val generation: Long,
  val phaseId: String,
  val phaseAttempt: Int,
  val admittedAt: String,
)
