package skillbill.ports.blockincident

import skillbill.ports.blockincident.model.ProductReportCreateOrLinkResult
import skillbill.ports.blockincident.model.ProductReportPendingComment
import skillbill.ports.blockincident.model.ProductReportTransition
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportCommentState
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportSanitization
import java.time.Instant

/** Persists sanitized product reports and their incident links. */
interface ProductReportRepository : ProductReportLookup {
  /** Creates a report or links [occurrenceToken] to the report with the same fingerprint. */
  fun createOrLink(
    sanitization: ProductReportSanitization,
    occurrenceToken: String,
    now: Instant,
    includedRecoveryAttemptIds: Set<String> = emptySet(),
  ): ProductReportCreateOrLinkResult

  /** Stores the exact bounded payload and origin marker before a create request can be sent. */
  fun recordCreateIntent(
    reportId: String,
    payload: String,
    digest: String,
    now: Instant,
  ): Boolean

  /** Promotes a delivered link when committed recovery history adds evidence absent from its body. */
  fun pendingRecoveryComments(
    occurrenceToken: String,
    terminalAttemptIds: Set<String>,
    now: Instant,
  ): Int

  /** Applies a status transition only when the stored status equals [expectedStatus]. */
  fun transition(change: ProductReportTransition): Boolean

  /** Promotes a NOT_REQUIRED link to PENDING when issue settlement or recovery evidence requires a comment. */
  fun markComment(
    reportId: String,
    occurrenceToken: String,
    expected: ProductReportCommentState,
    new: ProductReportCommentState,
  ): Boolean

  /** Stores the sanitized target body and represented terminal recovery ids while the link remains pending. */
  fun savePendingComment(
    reportId: String,
    occurrenceToken: String,
    target: ProductReportPendingComment,
  ): Boolean

  /** Acknowledges only the exact saved body when current terminal recovery evidence is covered. */
  fun acknowledgePendingComment(
    reportId: String,
    occurrenceToken: String,
    target: ProductReportPendingComment,
    currentTerminalRecoveryAttemptIds: Set<String>,
  ): Boolean
}
