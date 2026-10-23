package skillbill.ports.blockincident

import skillbill.ports.blockincident.model.ProductReportCreateOrLinkResult
import skillbill.ports.blockincident.model.ProductReportLink
import skillbill.ports.blockincident.model.ProductReportPendingComment
import skillbill.ports.blockincident.model.ProductReportRecord
import skillbill.ports.blockincident.model.ProductReportTransition
import skillbill.text.sha256HexUtf8
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportCommentState
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportDraft
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportFailure
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportSanitization
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportSanitizer
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportStatus
import java.time.Instant
import java.util.UUID

class InMemoryProductReportRepository : ProductReportRepository {
  private val reports = linkedMapOf<String, ProductReportRecord>()

  @Synchronized
  override fun createOrLink(
    sanitization: ProductReportSanitization,
    occurrenceToken: String,
    now: Instant,
    includedRecoveryAttemptIds: Set<String>,
  ): ProductReportCreateOrLinkResult {
    val existing = reports.values.firstOrNull { it.fingerprint == sanitization.fingerprint }
    if (existing != null) {
      if (existing.links.any { it.occurrenceToken == occurrenceToken }) {
        return ProductReportCreateOrLinkResult(existing, reportInserted = false, linkInserted = false)
      }
      val links = existing.links + ProductReportLink(occurrenceToken, ProductReportCommentState.PENDING)
      val linked = existing.copy(links = links).also { reports[it.reportId] = it }
      return ProductReportCreateOrLinkResult(linked, reportInserted = false, linkInserted = true)
    }
    val draft = sanitization as? ProductReportDraft
    val reportId = "bir_${UUID.randomUUID()}"
    val createPayload = draft?.let { ProductReportSanitizer.createPayload(it, reportId) }
    val rejected = draft == null || createPayload == null
    val body = if (rejected) "[withheld: residual secret]" else requireNotNull(draft).body
    val report =
      ProductReportRecord(
        reportId = reportId,
        fingerprint = sanitization.fingerprint,
        status = if (rejected) ProductReportStatus.LOCAL_ONLY else ProductReportStatus.PENDING,
        failure = if (rejected) ProductReportFailure.RESIDUAL_SECRET else null,
        attempts = 0,
        nextAttemptAt = if (rejected) null else now,
        issue = null,
        body = body,
        bodySha256 = sha256HexUtf8(body),
        redactionOutcomes = createPayload?.fieldOutcomes ?: sanitization.fieldOutcomes,
        createdAt = now,
        updatedAt = now,
        links =
          listOf(
            ProductReportLink(occurrenceToken, ProductReportCommentState.NOT_REQUIRED, includedRecoveryAttemptIds),
          ),
        createPayload = createPayload?.body,
        createPayloadSha256 = createPayload?.body?.let(::sha256HexUtf8),
      )
    reports[report.reportId] = report
    return ProductReportCreateOrLinkResult(report, reportInserted = true, linkInserted = true)
  }

  @Synchronized
  override fun due(
    now: Instant,
    limit: Int,
    credentialAvailable: Boolean,
  ): List<ProductReportRecord> =
    dueIds(now, limit).mapNotNull(::byId).filter {
      credentialAvailable || it.status != ProductReportStatus.LOCAL_ONLY ||
        it.failure != ProductReportFailure.MISSING_AUTH
    }

  @Synchronized
  override fun dueIds(
    now: Instant,
    limit: Int,
  ): List<String> {
    require(limit > 0)
    return reports.values.filter { record ->
      (
        record.status in setOf(ProductReportStatus.PENDING, ProductReportStatus.AMBIGUOUS) &&
          record.nextAttemptAt?.let { it <= now } == true
      ) ||
        (record.status == ProductReportStatus.LOCAL_ONLY && record.failure == ProductReportFailure.MISSING_AUTH) ||
        (
          record.status in setOf(ProductReportStatus.DELIVERED, ProductReportStatus.LINKED_EXISTING) &&
            record.links.any {
              it.commentState == ProductReportCommentState.PENDING ||
                record.status == ProductReportStatus.LINKED_EXISTING &&
                it.commentState == ProductReportCommentState.NOT_REQUIRED
            }
        ) ||
        record.status == ProductReportStatus.SUBMITTING
    }.take(limit).map { it.reportId }
  }

  @Synchronized
  override fun byId(reportId: String): ProductReportRecord? = reports[reportId]

  @Synchronized
  override fun recordCreateIntent(
    reportId: String,
    payload: String,
    digest: String,
    now: Instant,
  ): Boolean {
    val current = reports[reportId]?.takeIf { it.status == ProductReportStatus.SUBMITTING } ?: return false
    if (current.createIntent &&
      (current.createPayload != payload || current.createPayloadSha256 != digest)
    ) {
      return false
    }
    reports[reportId] =
      current.copy(
        createPayload = payload,
        createPayloadSha256 = digest,
        createIntent = true,
        createUnresolved = true,
      )
    return true
  }

  @Synchronized
  override fun pendingRecoveryComments(
    occurrenceToken: String,
    terminalAttemptIds: Set<String>,
    now: Instant,
  ): Int {
    val report = forIncident(occurrenceToken) ?: return 0
    if (report.status !in setOf(ProductReportStatus.DELIVERED, ProductReportStatus.LINKED_EXISTING)) return 0
    val index = report.links.indexOfFirst { it.occurrenceToken == occurrenceToken }
    if (index < 0) return 0
    val link = report.links[index]
    if (link.commentState != ProductReportCommentState.NOT_REQUIRED ||
      terminalAttemptIds.all { it in link.includedRecoveryAttemptIds }
    ) {
      return 0
    }
    reports[report.reportId] =
      report.copy(
        links =
          report.links.toMutableList().also {
            it[index] = link.copy(commentState = ProductReportCommentState.PENDING)
          },
      )
    return 1
  }

  @Synchronized
  override fun transition(change: ProductReportTransition): Boolean {
    val current = reports[change.reportId]?.takeIf { it.status == change.expectedStatus } ?: return false
    reports[change.reportId] =
      current.copy(
        status = change.newStatus,
        failure = change.failure,
        issue = change.issue ?: current.issue,
        attempts = change.attempts,
        nextAttemptAt = change.nextAttemptAt,
        updatedAt = change.now,
        attemptOpen = change.attemptOpen ?: current.attemptOpen,
        lastRemoteFailure = change.lastRemoteFailure ?: current.lastRemoteFailure,
        createUnresolved = change.createUnresolved ?: current.createUnresolved,
      )
    return true
  }

  @Synchronized
  override fun markComment(
    reportId: String,
    occurrenceToken: String,
    expected: ProductReportCommentState,
    new: ProductReportCommentState,
  ): Boolean {
    if (expected != ProductReportCommentState.NOT_REQUIRED || new != ProductReportCommentState.PENDING) return false
    val current = reports[reportId] ?: return false
    val index = current.links.indexOfFirst { it.occurrenceToken == occurrenceToken && it.commentState == expected }
    if (index < 0) return false
    reports[reportId] =
      current.copy(
        links =
          current.links.toMutableList().also {
            it[index] = it[index].copy(commentState = new)
          },
      )
    return true
  }

  @Synchronized
  override fun savePendingComment(
    reportId: String,
    occurrenceToken: String,
    target: ProductReportPendingComment,
  ): Boolean {
    if (sha256HexUtf8(target.body) != target.bodySha256) return false
    val current = reports[reportId] ?: return false
    val index =
      current.links.indexOfFirst {
        it.occurrenceToken == occurrenceToken && it.commentState == ProductReportCommentState.PENDING
      }
    if (index < 0) return false
    reports[reportId] =
      current.copy(
        links =
          current.links.toMutableList().also {
            it[index] = it[index].copy(pendingComment = target)
          },
      )
    return true
  }

  @Synchronized
  override fun acknowledgePendingComment(
    reportId: String,
    occurrenceToken: String,
    target: ProductReportPendingComment,
    currentTerminalRecoveryAttemptIds: Set<String>,
  ): Boolean {
    val current = reports[reportId] ?: return false
    val index =
      current.links.indexOfFirst {
        it.occurrenceToken == occurrenceToken && it.commentState == ProductReportCommentState.PENDING &&
          it.pendingComment == target
      }
    if (index < 0 || !target.terminalRecoveryAttemptIds.containsAll(currentTerminalRecoveryAttemptIds) ||
      sha256HexUtf8(target.body) != target.bodySha256
    ) {
      return false
    }
    reports[reportId] =
      current.copy(
        links =
          current.links.toMutableList().also {
            it[index] = it[index].copy(commentState = ProductReportCommentState.POSTED)
          },
      )
    return true
  }

  @Synchronized
  override fun forIncident(token: String): ProductReportRecord? =
    reports.values.firstOrNull { report -> report.links.any { it.occurrenceToken == token } }

  @Synchronized
  override fun reportIdForIncident(token: String): String? =
    reports.values.firstOrNull { report -> report.links.any { it.occurrenceToken == token } }?.reportId
}
