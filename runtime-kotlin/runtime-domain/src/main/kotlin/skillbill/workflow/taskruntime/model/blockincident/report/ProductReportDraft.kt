package skillbill.workflow.taskruntime.model.blockincident.report

data class ProductReportRuntimeFacts(
  val runtimeVersion: String?,
  val runtimeBuild: String?,
  val osFamily: String?,
  val providerId: String?,
  val phaseStrategyId: String?,
  val modelId: String?,
)

sealed interface ProductReportSanitization {
  val fingerprint: String
  val fieldOutcomes: Map<String, ProductReportFieldOutcome>
}

data class ProductReportDraft(
  val title: String,
  val body: String,
  override val fingerprint: String,
  override val fieldOutcomes: Map<String, ProductReportFieldOutcome>,
) : ProductReportSanitization

data class ProductReportCreatePayload(
  val body: String,
  val fieldOutcomes: Map<String, ProductReportFieldOutcome>,
)

data class ProductReportRejected(
  val reason: ProductReportFailure = ProductReportFailure.RESIDUAL_SECRET,
  override val fingerprint: String,
  override val fieldOutcomes: Map<String, ProductReportFieldOutcome>,
) : ProductReportSanitization
