package skillbill.ports.blockincident.model

import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportCommentState
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportFailure
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportFieldOutcome
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportStatus
import java.time.Instant

data class ProductReportIssue(val number: Long, val url: String)

data class ProductReportCreateOrLinkResult(
  val report: ProductReportRecord,
  val reportInserted: Boolean,
  val linkInserted: Boolean,
)

data class ProductReportLink(
  val occurrenceToken: String,
  val commentState: ProductReportCommentState,
  val includedRecoveryAttemptIds: Set<String> = emptySet(),
  val pendingComment: ProductReportPendingComment? = null,
)

data class ProductReportTransition(
  val reportId: String,
  val expectedStatus: ProductReportStatus,
  val newStatus: ProductReportStatus,
  val failure: ProductReportFailure?,
  val issue: ProductReportIssue?,
  val attempts: Int,
  val nextAttemptAt: Instant?,
  val now: Instant,
  val attemptOpen: Boolean? = null,
  val lastRemoteFailure: ProductReportFailure? = null,
  val createUnresolved: Boolean? = null,
)

data class ProductReportRecord(
  val reportId: String,
  val fingerprint: String,
  val status: ProductReportStatus,
  val failure: ProductReportFailure?,
  val attempts: Int,
  val nextAttemptAt: Instant?,
  val issue: ProductReportIssue?,
  val body: String,
  val bodySha256: String,
  val redactionOutcomes: Map<String, ProductReportFieldOutcome>,
  val createdAt: Instant,
  val updatedAt: Instant,
  val links: List<ProductReportLink>,
  val createPayload: String? = null,
  val createPayloadSha256: String? = null,
  val createIntent: Boolean = false,
  val createUnresolved: Boolean = false,
  val attemptOpen: Boolean = false,
  val lastRemoteFailure: ProductReportFailure? = null,
)
