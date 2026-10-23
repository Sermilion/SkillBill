package skillbill.infrastructure.sqlite.telemetry.outbox

import skillbill.contracts.telemetry.LifecycleTelemetryPayloadKeys
import skillbill.contracts.telemetry.TelemetryOutboxEvent
import skillbill.contracts.workflow.identity.blockincident.BlockIncidentReportKeys
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.telemetry.model.TelemetryCorrelationStatus
import skillbill.ports.telemetry.model.TelemetryOutboxClaimRequest
import skillbill.ports.telemetry.model.TelemetryOutboxRecord
import skillbill.ports.telemetry.model.TelemetryOutboxSettlementResult
import skillbill.ports.telemetry.transport.TelemetryOutboxRepository
import java.sql.Connection
import java.sql.ResultSet
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val ROW_COLUMNS =
  "id, event_name, payload_json, created_at, synced_at, last_error, skill_bill_version, " +
    "event_uuid, delivery_attempts"

private val FIXED_WIDTH_CLAIM_TIMESTAMP: DateTimeFormatter =
  DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

internal class TelemetryOutboxStore(
  private val connection: Connection,
  private val version: String,
) : TelemetryOutboxRepository {
  override fun enqueue(
    event: TelemetryOutboxEvent,
    payloadJson: String,
  ): Long {
    connection.prepareStatement(
      """
      INSERT INTO telemetry_outbox (event_name, payload_json, skill_bill_version, event_uuid)
      VALUES (?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(event.wireValue, payloadJson, version, UUID.randomUUID().toString())
      statement.executeUpdate()
    }
    return connection.createStatement().use { statement ->
      statement.executeQuery("SELECT last_insert_rowid()").use { resultSet ->
        resultSet.next()
        resultSet.getLong(1)
      }
    }
  }

  fun listPending(limit: Int? = null): List<TelemetryOutboxRecord> {
    val sql =
      buildString {
        appendLine("SELECT $ROW_COLUMNS")
        appendLine("FROM telemetry_outbox")
        appendLine("WHERE synced_at IS NULL")
        appendLine("ORDER BY id")
        if (limit != null) {
          append("LIMIT ?")
        }
      }.trimIndent()
    return connection.prepareStatement(sql).use { statement ->
      if (limit != null) {
        statement.bindAll(limit)
      }
      statement.executeQuery().use { it.readOutboxRows() }
    }
  }

  override fun claimPending(request: TelemetryOutboxClaimRequest): List<TelemetryOutboxRecord> {
    connection.prepareStatement(
      """
      UPDATE telemetry_outbox
      SET claim_token = ?, claimed_at = ?
      WHERE id IN (
        SELECT id FROM telemetry_outbox
        WHERE synced_at IS NULL
          AND delivery_attempts < ?
          AND (claim_token IS NULL OR claimed_at IS NULL OR claimed_at < ?)
        ORDER BY id
        LIMIT ?
      )
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        request.claimToken,
        FIXED_WIDTH_CLAIM_TIMESTAMP.format(request.claimedAt),
        request.attemptBudget,
        FIXED_WIDTH_CLAIM_TIMESTAMP.format(request.reclaimBefore),
        request.limit,
      )
      statement.executeUpdate()
    }
    return connection.prepareStatement(
      """
      SELECT $ROW_COLUMNS
      FROM telemetry_outbox
      WHERE claim_token = ? AND synced_at IS NULL AND delivery_attempts < ?
      ORDER BY id
      LIMIT ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(request.claimToken, request.attemptBudget, request.limit)
      statement.executeQuery().use { it.readOutboxRows() }
    }
  }

  override fun pendingCount(): Int =
    connection.prepareStatement(
      """
      SELECT COUNT(*)
      FROM telemetry_outbox
      WHERE synced_at IS NULL
      """.trimIndent(),
    ).use { statement ->
      statement.executeQuery().use { resultSet ->
        resultSet.next()
        resultSet.getInt(1)
      }
    }

  override fun blockedCount(attemptBudget: Int): Int =
    connection.prepareStatement(
      """
      SELECT COUNT(*)
      FROM telemetry_outbox
      WHERE synced_at IS NULL AND delivery_attempts >= ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(attemptBudget)
      statement.executeQuery().use { resultSet ->
        resultSet.next()
        resultSet.getInt(1)
      }
    }

  override fun latestError(): String? =
    connection.prepareStatement(
      """
      SELECT last_error
      FROM telemetry_outbox
      WHERE synced_at IS NULL AND last_error IS NOT NULL AND last_error != ''
      ORDER BY id DESC
      LIMIT 1
      """.trimIndent(),
    ).use { statement ->
      statement.executeQuery().use { resultSet ->
        if (!resultSet.next()) {
          return null
        }
        resultSet.getString("last_error").orEmpty().ifBlank { null }
      }
    }

  override fun lastSyncedAt(): String? =
    connection.prepareStatement(
      """
      SELECT MAX(synced_at)
      FROM telemetry_outbox
      WHERE synced_at IS NOT NULL
      """.trimIndent(),
    ).use { statement ->
      statement.executeQuery().use { resultSet ->
        if (!resultSet.next()) {
          return null
        }
        resultSet.getString(1)
      }
    }

  override fun latestByCorrelation(
    events: Set<TelemetryOutboxEvent>,
    incidentToken: String,
  ): TelemetryCorrelationStatus? {
    if (events.isEmpty()) return null
    val placeholders = events.joinToString(",") { "?" }
    return connection.prepareStatement(
      "SELECT synced_at, last_error FROM telemetry_outbox " +
        "WHERE event_name IN ($placeholders) AND json_extract(payload_json, " +
        "'$.${BlockIncidentReportKeys.INCIDENT_TOKEN}') = ? " +
        "ORDER BY id DESC LIMIT 1",
    ).use { statement ->
      var index = 1
      events.forEach { event -> statement.setString(index++, event.wireValue) }
      statement.setString(index, incidentToken)
      statement.executeQuery().use { rows ->
        if (!rows.next()) {
          null
        } else {
          TelemetryCorrelationStatus(rows.getString("synced_at") != null, rows.getString("last_error"))
        }
      }
    }
  }

  fun markSynced(
    id: Long,
    syncedAt: String,
  ) {
    connection.prepareStatement(
      """
      UPDATE telemetry_outbox
      SET synced_at = ?, last_error = NULL, claim_token = NULL, claimed_at = NULL
      WHERE id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(syncedAt, id)
      statement.executeUpdate()
    }
  }

  override fun markSynced(
    eventIds: List<Long>,
    claimToken: String,
  ): TelemetryOutboxSettlementResult {
    if (eventIds.isEmpty()) {
      return TelemetryOutboxSettlementResult.forRequest(eventIds, updatedRows = 0)
    }
    val placeholders = eventIds.joinToString(", ") { "?" }
    val updated =
      connection.prepareStatement(
        """
        UPDATE telemetry_outbox
        SET synced_at = CURRENT_TIMESTAMP, last_error = NULL, claim_token = NULL, claimed_at = NULL
        WHERE id IN ($placeholders) AND claim_token = ? AND synced_at IS NULL
        """.trimIndent(),
      ).use { statement ->
        val values: List<Any?> = eventIds.map { it } + claimToken
        statement.bindAll(values)
        statement.executeUpdate()
      }
    return TelemetryOutboxSettlementResult.forRequest(eventIds, updatedRows = updated)
  }

  fun markFailed(
    id: Long,
    claimToken: String,
    lastError: String,
  ) {
    markFailed(listOf(id), claimToken, lastError)
  }

  override fun markFailed(
    eventIds: List<Long>,
    claimToken: String,
    lastError: String,
  ): TelemetryOutboxSettlementResult = recordFailure(eventIds, claimToken, lastError, consumesAttempt = true)

  override fun markUnconfirmed(
    eventIds: List<Long>,
    claimToken: String,
    lastError: String,
  ): TelemetryOutboxSettlementResult = recordFailure(eventIds, claimToken, lastError, consumesAttempt = false)

  private fun recordFailure(
    eventIds: List<Long>,
    claimToken: String,
    lastError: String,
    consumesAttempt: Boolean,
  ): TelemetryOutboxSettlementResult {
    if (eventIds.isEmpty()) {
      return TelemetryOutboxSettlementResult.forRequest(eventIds, updatedRows = 0)
    }
    val placeholders = eventIds.joinToString(", ") { "?" }
    val assignments =
      buildList {
        add("last_error = ?")
        if (consumesAttempt) {
          add("delivery_attempts = delivery_attempts + 1")
        }
        add("claim_token = NULL")
        add("claimed_at = NULL")
      }.joinToString(", ")
    val updated =
      connection.prepareStatement(
        """
        UPDATE telemetry_outbox
        SET $assignments
        WHERE id IN ($placeholders) AND claim_token = ? AND synced_at IS NULL
        """.trimIndent(),
      ).use { statement ->
        val values: List<Any?> =
          buildList {
            add(lastError)
            addAll(eventIds)
            add(claimToken)
          }
        statement.bindAll(values)
        statement.executeUpdate()
      }
    return TelemetryOutboxSettlementResult.forRequest(eventIds, updatedRows = updated)
  }

  override fun clear(): Int {
    val count =
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM telemetry_outbox").use { resultSet ->
          resultSet.next()
          resultSet.getInt(1)
        }
      }
    connection.createStatement().use { statement ->
      statement.executeUpdate("DELETE FROM telemetry_outbox")
    }
    return count
  }
}

private fun ResultSet.readOutboxRows(): List<TelemetryOutboxRecord> =
  buildList {
    while (next()) {
      add(
        TelemetryOutboxRecord(
          id = getLong("id"),
          eventName = getString(LifecycleTelemetryPayloadKeys.EVENT_NAME),
          payloadJson = getString("payload_json"),
          createdAt = getString("created_at"),
          syncedAt = getString("synced_at"),
          lastError = getString("last_error").orEmpty(),
          skillBillVersion = getString("skill_bill_version"),
          eventUuid = getString("event_uuid").orEmpty(),
          deliveryAttempts = getInt("delivery_attempts"),
        ),
      )
    }
  }
