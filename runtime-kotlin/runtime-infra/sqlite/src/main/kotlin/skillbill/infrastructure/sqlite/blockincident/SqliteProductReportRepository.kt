package skillbill.infrastructure.sqlite.blockincident

import skillbill.contracts.workflow.identity.blockincident.BlockIncidentRecoveryKeys
import skillbill.ports.blockincident.ProductReportRepository
import skillbill.ports.blockincident.model.ProductReportCreateOrLinkResult
import skillbill.ports.blockincident.model.ProductReportIssue
import skillbill.ports.blockincident.model.ProductReportLink
import skillbill.ports.blockincident.model.ProductReportPendingComment
import skillbill.ports.blockincident.model.ProductReportRecord
import skillbill.ports.blockincident.model.ProductReportTransition
import skillbill.text.sha256HexUtf8
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportCommentState
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportDraft
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportFailure
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportFieldOutcome
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportSanitization
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportSanitizer
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportStatus
import java.sql.Connection
import java.time.Instant
import java.util.UUID

internal class SqliteProductReportRepository(private val connection: Connection) : ProductReportRepository {
  override fun createOrLink(
    sanitization: ProductReportSanitization,
    occurrenceToken: String,
    now: Instant,
    includedRecoveryAttemptIds: Set<String>,
  ): ProductReportCreateOrLinkResult {
    val draft = sanitization as? ProductReportDraft
    val reportId = "bir_${UUID.randomUUID()}"
    val createPayload = draft?.let { ProductReportSanitizer.createPayload(it, reportId) }
    val rejected = draft == null || createPayload == null
    val body = if (rejected) "[withheld: residual secret]" else requireNotNull(draft).body
    val status = if (rejected) ProductReportStatus.LOCAL_ONLY else ProductReportStatus.PENDING
    var reportInserted = false
    var linkInserted = false
    connection.prepareStatement(
      """
      INSERT INTO block_incident_reports (
        report_id, fingerprint, status, failure_reason, attempts, next_attempt_at,
        body, body_sha256, redaction_outcomes, created_at, updated_at,
        create_payload, create_payload_sha256, create_intent
      ) VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, 0)
      ON CONFLICT(fingerprint) DO NOTHING
      """.trimIndent(),
    ).use { statement ->
      var parameterIndex = 0
      statement.setString(++parameterIndex, reportId)
      statement.setString(++parameterIndex, sanitization.fingerprint)
      statement.setString(++parameterIndex, status.wireValue)
      statement.setString(++parameterIndex, if (rejected) ProductReportFailure.RESIDUAL_SECRET.wireValue else null)
      statement.setString(++parameterIndex, if (rejected) null else now.toString())
      statement.setString(++parameterIndex, body)
      statement.setString(++parameterIndex, sha256HexUtf8(body))
      statement.setString(++parameterIndex, encodeOutcomes(createPayload?.fieldOutcomes ?: sanitization.fieldOutcomes))
      statement.setString(++parameterIndex, now.toString())
      statement.setString(++parameterIndex, now.toString())
      statement.setString(++parameterIndex, createPayload?.body)
      statement.setString(++parameterIndex, createPayload?.body?.let(::sha256HexUtf8))
      reportInserted = statement.executeUpdate() == 1
      val storedId =
        connection.prepareStatement("SELECT report_id FROM block_incident_reports WHERE fingerprint = ?").use { read ->
          read.setString(1, sanitization.fingerprint)
          read.executeQuery().use { rows ->
            check(rows.next())
            rows.getString(1)
          }
        }
      connection.prepareStatement(
        "INSERT INTO block_incident_report_links " +
          "(report_id, occurrence_token, comment_state, included_recovery_attempts) VALUES (?, ?, ?, ?) " +
          "ON CONFLICT(report_id, occurrence_token) DO NOTHING",
      ).use { link ->
        var parameterIndex = 0
        link.setString(++parameterIndex, storedId)
        link.setString(++parameterIndex, occurrenceToken)
        link.setString(
          ++parameterIndex,
          if (reportInserted) {
            ProductReportCommentState.NOT_REQUIRED.wireValue
          } else {
            ProductReportCommentState.PENDING.wireValue
          },
        )
        link.setString(
          ++parameterIndex,
          if (reportInserted) includedRecoveryAttemptIds.sorted().joinToString("|") else null,
        )
        linkInserted = link.executeUpdate() == 1
      }
      return ProductReportCreateOrLinkResult(readRecord(storedId), reportInserted, linkInserted)
    }
  }

  override fun due(
    now: Instant,
    limit: Int,
    credentialAvailable: Boolean,
  ): List<ProductReportRecord> {
    return dueIds(now, limit).mapNotNull(::byId).filter {
      credentialAvailable || it.status != ProductReportStatus.LOCAL_ONLY ||
        it.failure != ProductReportFailure.MISSING_AUTH
    }
  }

  override fun dueIds(
    now: Instant,
    limit: Int,
  ): List<String> {
    require(limit > 0)
    val ids =
      connection.prepareStatement(
        """
        SELECT DISTINCT r.report_id
        FROM block_incident_reports r
        LEFT JOIN block_incident_report_links l ON l.report_id = r.report_id
        WHERE ((r.status IN (?, ?) AND r.next_attempt_at <= ?)
          OR (r.status = ? AND r.failure_reason = ?)
          OR (r.status IN (?, ?) AND (l.comment_state = ? OR (l.comment_state = ? AND EXISTS (
            SELECT 1 FROM block_incident_recovery_attempts a
            WHERE a.occurrence_token = l.occurrence_token AND a.status IN (?, ?)
              AND instr('|' || COALESCE(l.included_recovery_attempts, '') || '|', '|' || a.attempt_id || '|') = 0
          ))))
          OR (r.status = ? AND l.comment_state = ?)
          OR (r.status = ?))
        ORDER BY r.created_at
        LIMIT ?
        """.trimIndent(),
      ).use { statement ->
        var parameterIndex = 0
        statement.setString(++parameterIndex, ProductReportStatus.PENDING.wireValue)
        statement.setString(++parameterIndex, ProductReportStatus.AMBIGUOUS.wireValue)
        statement.setString(++parameterIndex, now.toString())
        statement.setString(++parameterIndex, ProductReportStatus.LOCAL_ONLY.wireValue)
        statement.setString(++parameterIndex, ProductReportFailure.MISSING_AUTH.wireValue)
        statement.setString(++parameterIndex, ProductReportStatus.DELIVERED.wireValue)
        statement.setString(++parameterIndex, ProductReportStatus.LINKED_EXISTING.wireValue)
        statement.setString(++parameterIndex, ProductReportCommentState.PENDING.wireValue)
        statement.setString(++parameterIndex, ProductReportCommentState.NOT_REQUIRED.wireValue)
        statement.setString(++parameterIndex, BlockIncidentRecoveryKeys.STATUS_FAILED)
        statement.setString(++parameterIndex, BlockIncidentRecoveryKeys.STATUS_RESUMED)
        statement.setString(++parameterIndex, ProductReportStatus.LINKED_EXISTING.wireValue)
        statement.setString(++parameterIndex, ProductReportCommentState.NOT_REQUIRED.wireValue)
        statement.setString(++parameterIndex, ProductReportStatus.SUBMITTING.wireValue)
        statement.setInt(++parameterIndex, limit)
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
      }
    return ids
  }

  override fun byId(reportId: String): ProductReportRecord? =
    connection.prepareStatement("SELECT 1 FROM block_incident_reports WHERE report_id = ?").use { statement ->
      var parameterIndex = 0
      statement.setString(++parameterIndex, reportId)
      statement.executeQuery().use { rows -> if (rows.next()) readRecord(reportId) else null }
    }

  override fun recordCreateIntent(
    reportId: String,
    payload: String,
    digest: String,
    now: Instant,
  ): Boolean =
    connection.prepareStatement(
      "UPDATE block_incident_reports SET create_payload = ?, create_payload_sha256 = ?, create_intent = 1, " +
        "create_unresolved = 1, updated_at = ? WHERE report_id = ? AND status = ? AND " +
        "(create_intent = 0 OR (create_payload = ? AND create_payload_sha256 = ?))",
    ).use { statement ->
      var parameterIndex = 0
      statement.setString(++parameterIndex, payload)
      statement.setString(++parameterIndex, digest)
      statement.setString(++parameterIndex, now.toString())
      statement.setString(++parameterIndex, reportId)
      statement.setString(++parameterIndex, ProductReportStatus.SUBMITTING.wireValue)
      statement.setString(++parameterIndex, payload)
      statement.setString(++parameterIndex, digest)
      statement.executeUpdate() == 1
    }

  override fun pendingRecoveryComments(
    occurrenceToken: String,
    terminalAttemptIds: Set<String>,
    now: Instant,
  ): Int {
    val link =
      connection.prepareStatement(
        "SELECT report_id, included_recovery_attempts, comment_state FROM block_incident_report_links " +
          "WHERE occurrence_token = ?",
      ).use { statement ->
        var parameterIndex = 0
        statement.setString(++parameterIndex, occurrenceToken)
        statement.executeQuery().use { rows ->
          if (!rows.next()) null else Triple(rows.getString(1), rows.getString(2), rows.getString("comment_state"))
        }
      } ?: return 0
    if (link.third != ProductReportCommentState.NOT_REQUIRED.wireValue) return 0
    val included = link.second.orEmpty().split('|').filter(String::isNotBlank).toSet()
    if (terminalAttemptIds.all { it in included }) return 0
    return connection.prepareStatement(
      "UPDATE block_incident_report_links SET comment_state = ? WHERE report_id = ? AND occurrence_token = ? " +
        "AND comment_state = ? AND EXISTS (SELECT 1 FROM block_incident_reports WHERE report_id = ? " +
        "AND status IN (?, ?))",
    ).use { statement ->
      var parameterIndex = 0
      statement.setString(++parameterIndex, ProductReportCommentState.PENDING.wireValue)
      statement.setString(++parameterIndex, link.first)
      statement.setString(++parameterIndex, occurrenceToken)
      statement.setString(++parameterIndex, ProductReportCommentState.NOT_REQUIRED.wireValue)
      statement.setString(++parameterIndex, link.first)
      statement.setString(++parameterIndex, ProductReportStatus.DELIVERED.wireValue)
      statement.setString(++parameterIndex, ProductReportStatus.LINKED_EXISTING.wireValue)
      statement.executeUpdate()
    }
  }

  override fun transition(change: ProductReportTransition): Boolean =
    connection.prepareStatement(
      """
      UPDATE block_incident_reports
      SET status = ?, failure_reason = ?, issue_number = COALESCE(?, issue_number),
          issue_url = COALESCE(?, issue_url), attempts = ?, next_attempt_at = ?, updated_at = ?,
          attempt_open = COALESCE(?, attempt_open), last_remote_failure = COALESCE(?, last_remote_failure),
          create_unresolved = COALESCE(?, create_unresolved)
      WHERE report_id = ? AND status = ?
      """.trimIndent(),
    ).use { statement ->
      var parameterIndex = 0
      statement.setString(++parameterIndex, change.newStatus.wireValue)
      statement.setString(++parameterIndex, change.failure?.wireValue)
      statement.setObject(++parameterIndex, change.issue?.number)
      statement.setString(++parameterIndex, change.issue?.url)
      statement.setInt(++parameterIndex, change.attempts)
      statement.setString(++parameterIndex, change.nextAttemptAt?.toString())
      statement.setString(++parameterIndex, change.now.toString())
      statement.setObject(++parameterIndex, change.attemptOpen?.let { if (it) 1 else 0 })
      statement.setString(++parameterIndex, change.lastRemoteFailure?.wireValue)
      statement.setObject(++parameterIndex, change.createUnresolved?.let { if (it) 1 else 0 })
      statement.setString(++parameterIndex, change.reportId)
      statement.setString(++parameterIndex, change.expectedStatus.wireValue)
      statement.executeUpdate() == 1
    }

  override fun markComment(
    reportId: String,
    occurrenceToken: String,
    expected: ProductReportCommentState,
    new: ProductReportCommentState,
  ): Boolean {
    if (expected != ProductReportCommentState.NOT_REQUIRED || new != ProductReportCommentState.PENDING) return false
    return connection.prepareStatement(
      "UPDATE block_incident_report_links SET comment_state = ? " +
        "WHERE report_id = ? AND occurrence_token = ? AND comment_state = ?",
    ).use { statement ->
      var parameterIndex = 0
      statement.setString(++parameterIndex, new.wireValue)
      statement.setString(++parameterIndex, reportId)
      statement.setString(++parameterIndex, occurrenceToken)
      statement.setString(++parameterIndex, expected.wireValue)
      statement.executeUpdate() == 1
    }
  }

  override fun savePendingComment(
    reportId: String,
    occurrenceToken: String,
    target: ProductReportPendingComment,
  ): Boolean {
    if (sha256HexUtf8(target.body) != target.bodySha256) return false
    return connection.prepareStatement(
      "UPDATE block_incident_report_links SET pending_comment_body = ?, pending_comment_sha256 = ?, " +
        "pending_comment_recovery_attempts = ? WHERE report_id = ? AND occurrence_token = ? AND comment_state = ?",
    ).use { statement ->
      var parameterIndex = 0
      statement.setString(++parameterIndex, target.body)
      statement.setString(++parameterIndex, target.bodySha256)
      statement.setString(++parameterIndex, target.terminalRecoveryAttemptIds.sorted().joinToString("|"))
      statement.setString(++parameterIndex, reportId)
      statement.setString(++parameterIndex, occurrenceToken)
      statement.setString(++parameterIndex, ProductReportCommentState.PENDING.wireValue)
      statement.executeUpdate() == 1
    }
  }

  override fun acknowledgePendingComment(
    reportId: String,
    occurrenceToken: String,
    target: ProductReportPendingComment,
    currentTerminalRecoveryAttemptIds: Set<String>,
  ): Boolean {
    if (sha256HexUtf8(target.body) != target.bodySha256 ||
      !target.terminalRecoveryAttemptIds.containsAll(currentTerminalRecoveryAttemptIds)
    ) {
      return false
    }
    return connection.prepareStatement(
      "UPDATE block_incident_report_links SET comment_state = ? " +
        "WHERE report_id = ? AND occurrence_token = ? AND comment_state = ? " +
        "AND pending_comment_body = ? AND pending_comment_sha256 = ? " +
        "AND pending_comment_recovery_attempts = ?",
    ).use { statement ->
      var parameterIndex = 0
      statement.setString(++parameterIndex, ProductReportCommentState.POSTED.wireValue)
      statement.setString(++parameterIndex, reportId)
      statement.setString(++parameterIndex, occurrenceToken)
      statement.setString(++parameterIndex, ProductReportCommentState.PENDING.wireValue)
      statement.setString(++parameterIndex, target.body)
      statement.setString(++parameterIndex, target.bodySha256)
      statement.setString(++parameterIndex, target.terminalRecoveryAttemptIds.sorted().joinToString("|"))
      statement.executeUpdate() == 1
    }
  }

  override fun forIncident(token: String): ProductReportRecord? =
    connection.prepareStatement(
      "SELECT report_id FROM block_incident_report_links WHERE occurrence_token = ?",
    ).use { statement ->
      var parameterIndex = 0
      statement.setString(++parameterIndex, token)
      statement.executeQuery().use { rows -> if (rows.next()) readRecord(rows.getString(1)) else null }
    }

  override fun reportIdForIncident(token: String): String? =
    connection.prepareStatement(
      "SELECT report_id FROM block_incident_report_links WHERE occurrence_token = ?",
    ).use { statement ->
      var parameterIndex = 0
      statement.setString(++parameterIndex, token)
      statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }

  private fun readRecord(reportId: String): ProductReportRecord =
    connection.prepareStatement(
      """
      SELECT report_id, fingerprint, status, failure_reason, attempts, next_attempt_at,
             issue_number, issue_url, body, body_sha256, redaction_outcomes, created_at, updated_at,
             create_payload, create_payload_sha256, create_intent, create_unresolved, attempt_open,
             last_remote_failure
      FROM block_incident_reports WHERE report_id = ?
      """.trimIndent(),
    ).use { statement ->
      var parameterIndex = 0
      statement.setString(++parameterIndex, reportId)
      statement.executeQuery().use { rows ->
        check(rows.next())
        val body = rows.getString("body")
        check(sha256HexUtf8(body) == rows.getString("body_sha256")) {
          "Product report body digest does not match its stored content."
        }
        val issueNumber = rows.getLong("issue_number").let { if (rows.wasNull()) null else it }
        ProductReportRecord(
          reportId = rows.getString("report_id"),
          fingerprint = rows.getString("fingerprint"),
          status = ProductReportStatus.entries.first { it.wireValue == rows.getString("status") },
          failure =
            rows.getString("failure_reason")?.let { token ->
              ProductReportFailure.entries.first { it.wireValue == token }
            },
          attempts = rows.getInt("attempts"),
          nextAttemptAt = rows.getString("next_attempt_at")?.let(Instant::parse),
          issue = issueNumber?.let { ProductReportIssue(it, rows.getString("issue_url")) },
          body = body,
          bodySha256 = rows.getString("body_sha256"),
          redactionOutcomes = decodeOutcomes(rows.getString("redaction_outcomes")),
          createdAt = Instant.parse(rows.getString("created_at")),
          updatedAt = Instant.parse(rows.getString("updated_at")),
          links = readLinks(reportId),
          createPayload =
            rows.getString("create_payload").also { payload ->
              if (payload != null) check(sha256HexUtf8(payload) == rows.getString("create_payload_sha256"))
            },
          createPayloadSha256 = rows.getString("create_payload_sha256"),
          createIntent = rows.getInt("create_intent") == 1,
          createUnresolved = rows.getInt("create_unresolved") == 1,
          attemptOpen = rows.getInt("attempt_open") == 1,
          lastRemoteFailure =
            rows.getString("last_remote_failure")?.let { token ->
              ProductReportFailure.entries.first { it.wireValue == token }
            },
        )
      }
    }

  private fun readLinks(reportId: String): List<ProductReportLink> =
    connection.prepareStatement(
      "SELECT occurrence_token, comment_state, included_recovery_attempts, pending_comment_body, " +
        "pending_comment_sha256, pending_comment_recovery_attempts FROM block_incident_report_links " +
        "WHERE report_id = ? ORDER BY occurrence_token",
    ).use { statement ->
      var parameterIndex = 0
      statement.setString(++parameterIndex, reportId)
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) {
            val state = rows.getString("comment_state")
            val body = rows.getString("pending_comment_body")
            val digest = rows.getString("pending_comment_sha256")
            val coverage = rows.getString("pending_comment_recovery_attempts")
            val pending =
              if (body == null || digest == null || coverage == null) {
                check(body == null && digest == null && coverage == null)
                null
              } else {
                check(sha256HexUtf8(body) == digest) { "Product report pending comment digest does not match." }
                ProductReportPendingComment(body, digest, coverage.split('|').filter(String::isNotBlank).toSet())
              }
            add(
              ProductReportLink(
                rows.getString("occurrence_token"),
                ProductReportCommentState.entries.first { it.wireValue == state },
                rows.getString("included_recovery_attempts").orEmpty().split('|').filter(String::isNotBlank).toSet(),
                pending,
              ),
            )
          }
        }
      }
    }

  private fun encodeOutcomes(outcomes: Map<String, ProductReportFieldOutcome>): String =
    outcomes.entries.joinToString("|") { (field, result) -> "$field=${result.wireValue}" }

  private fun decodeOutcomes(encoded: String): Map<String, ProductReportFieldOutcome> =
    encoded.split("|").filter(String::isNotBlank).associate { pair ->
      val (field, value) = pair.split("=", limit = 2)
      field to ProductReportFieldOutcome.entries.first { it.wireValue == value }
    }
}
