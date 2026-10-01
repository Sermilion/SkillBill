package skillbill.infrastructure.sqlite

import skillbill.contracts.JsonCodec
import skillbill.infrastructure.sqlite.review.stats.ReviewFinishedPayloadBuildRequest
import skillbill.infrastructure.sqlite.review.stats.ReviewStatsRuntime
import skillbill.infrastructure.sqlite.telemetry.lifecycle.LifecycleTelemetryStore
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import skillbill.ports.telemetry.model.toReviewFinishedTelemetryPayload
import skillbill.review.parsing.ReviewParser
import java.nio.file.Files
import java.sql.Connection
import java.time.Clock

fun telemetryReliabilityEmittedEnvelope(
  eventName: String,
  contractVersion: String,
  emit: (LifecycleTelemetryRepository, Connection) -> Unit,
): LinkedHashMap<String, Any?> {
  val dbPath = Files.createTempDirectory("telemetry-reliability-emitter").resolve("metrics.db")
  return ensureTestDatabase(dbPath).use { connection ->
    emit(LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics), connection)
    val payloadJson =
      connection.prepareStatement(
        "SELECT payload_json FROM telemetry_outbox WHERE event_name = ? ORDER BY id DESC LIMIT 1",
      ).use { statement ->
        statement.setString(1, eventName)
        statement.executeQuery().use { resultSet ->
          check(resultSet.next()) { "Expected a real outbox row for $eventName" }
          resultSet.getString("payload_json")
        }
      }
    val parsed = requireNotNull(JsonCodec.parseObjectOrNull(payloadJson))
    linkedMapOf<String, Any?>().apply {
      val contractEventName =
        if (eventName == "skillbill_review_finished") {
          eventName
        } else {
          eventName.removePrefix("skillbill_")
        }
      put("event_name", contractEventName)
      put("contract_version", contractVersion)
      putAll(requireNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(parsed))))
    }
  }
}

fun telemetryReliabilityReviewFinishedEnvelope(
  reviewMarkdown: String,
  contractVersion: String,
  routedSkillPlatformSlugs: Map<String, String>,
): LinkedHashMap<String, Any?> {
  val dbPath = Files.createTempDirectory("telemetry-reliability-review").resolve("metrics.db")
  return ensureTestDatabase(dbPath).use { connection ->
    val review = ReviewParser.parseReview(reviewMarkdown)
    SQLiteReviewRepository(connection, Clock.systemUTC(), "test-runtime-version", SqliteTestDiagnostics)
      .saveImportedReview(review, sourcePath = null)
    linkedMapOf<String, Any?>().apply {
      put("event_name", "skillbill_review_finished")
      put("contract_version", contractVersion)
      putAll(
        ReviewStatsRuntime.buildReviewFinishedPayload(
          ReviewFinishedPayloadBuildRequest(
            connection = connection,
            reviewRunId = review.reviewRunId,
            level = "full",
            routedSkillPlatformSlugs = routedSkillPlatformSlugs,
          ),
        ).toReviewFinishedTelemetryPayload().toPayload(),
      )
    }
  }
}

fun ageTelemetryReliabilitySession(
  connection: Connection,
  tableName: String,
  sessionId: String,
  seconds: Int,
) {
  connection.prepareStatement(
    "UPDATE $tableName SET started_at = datetime('now', '-' || ? || ' seconds') WHERE session_id = ?",
  ).use { statement ->
    statement.setInt(1, seconds)
    statement.setString(2, sessionId)
    statement.executeUpdate()
  }
}
