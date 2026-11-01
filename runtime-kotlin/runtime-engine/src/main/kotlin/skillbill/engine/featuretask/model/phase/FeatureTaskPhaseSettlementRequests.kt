package skillbill.engine.featuretask.model.phase

public data class FeatureTaskPhaseSettlementCompleteRequest(
  public val workflowId: String,
  public val phaseId: String,
  public val attempt: Int,
  public val value: String,
  public val prompt: String? = null,
  public val summary: String? = null,
  public val verdict: String? = null,
  public val noChange: Map<String, Any?>? = null,
)

public data class FeatureTaskPhaseSettlementBlockRequest(
  public val workflowId: String,
  public val phaseId: String,
  public val attempt: Int,
  public val reason: String,
  public val failureDisposition: String = "needs_user_action",
  public val verdict: String? = null,
  public val validationEvidence: Map<String, Any?>? = null,
)
