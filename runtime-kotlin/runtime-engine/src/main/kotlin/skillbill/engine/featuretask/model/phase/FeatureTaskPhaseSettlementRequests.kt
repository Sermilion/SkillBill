package skillbill.engine.featuretask.model.phase

data class FeatureTaskPhaseSettlementCompleteRequest(
  val workflowId: String,
  val phaseId: String,
  val attempt: Int,
  val value: String,
  val prompt: String? = null,
  val summary: String? = null,
  val verdict: String? = null,
)

data class FeatureTaskPhaseSettlementBlockRequest(
  val workflowId: String,
  val phaseId: String,
  val attempt: Int,
  val reason: String,
  val failureDisposition: String = "needs_user_action",
  val verdict: String? = null,
)
