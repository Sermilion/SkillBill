package skillbill.ports.blockincident.model

data class ProductReportPendingComment(
  val body: String,
  val bodySha256: String,
  val terminalRecoveryAttemptIds: Set<String>,
)
