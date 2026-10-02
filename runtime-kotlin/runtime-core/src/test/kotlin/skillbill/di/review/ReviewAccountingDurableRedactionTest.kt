package skillbill.di.review

import skillbill.application.review.model.ReviewPrelaunchExpansion
import skillbill.application.review.snapshot.RecordedWorkerResponse
import skillbill.application.review.snapshot.ReviewHarnessConfig
import skillbill.application.review.snapshot.ReviewRecorder
import skillbill.application.review.snapshot.diffForChanges
import skillbill.application.review.snapshot.harnessRequest
import skillbill.application.review.snapshot.reviewHarness
import skillbill.application.review.snapshot.reviewPack
import skillbill.application.review.snapshot.reviewed
import skillbill.contracts.JsonCodec
import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.contracts.telemetry.TelemetryOutboxEvent
import skillbill.infrastructure.contracts.review.ReviewContextSchemaValidator
import skillbill.infrastructure.sqlite.ensureTestDatabase
import skillbill.infrastructure.sqlite.reviewAccountingOnConnection
import skillbill.infrastructure.sqlite.telemetryOutboxOnConnection
import skillbill.ports.review.model.ReviewAccountingRecord
import skillbill.review.context.model.accounting.ReviewAccountingSummary
import skillbill.workflow.model.goalreview.toReviewAccountingBoundedJson
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewAccountingDurableRedactionTest {
  private val diffBody = "DIFF_SENTINEL ".repeat(64)
  private val guidanceBody = "GUIDANCE_SENTINEL ".repeat(64)
  private val rubricBody = "RUBRIC_SENTINEL ".repeat(64)
  private val toolOutputBody = "TOOL_OUTPUT_SENTINEL ".repeat(64)
  private val sentinels =
    listOf(
      "DIFF_SENTINEL",
      "GUIDANCE_SENTINEL",
      "RUBRIC_SENTINEL",
      "TOOL_OUTPUT_SENTINEL",
    )

  @Test fun `the measured review really did carry every content sentinel`() {
    val (recorder, summary) = recordedReview()

    val prompts = recorder.parentPrompts
    assertTrue(prompts.isNotEmpty(), "The redaction proof is only meaningful if a lane was launched.")
    assertTrue(prompts.all { it.contains("hunk_id:") }, "Indexed hunk locators must reach the lane prompt.")
    assertTrue(prompts.all { !it.contains("DIFF_SENTINEL") }, "The stored hunk body must not be inlined in the prompt.")
    assertTrue(prompts.all { it.contains("RUBRIC_SENTINEL") }, "The rubric body must reach the lane prompt.")
    assertTrue(prompts.all { it.contains("docs/GUIDANCE.md") }, "Changed guidance paths must reach the prompt.")
    assertTrue(prompts.all { !it.contains("GUIDANCE_SENTINEL") }, "Guidance hunk bodies must not be inlined.")
    assertTrue(summary.aggregateCounters.launchBytes > 0)
    assertTrue(summary.aggregateCounters.resultBytes > 0)
  }

  @Test fun `bounded accounting validates against the governed review-context schema`() {
    ReviewContextSchemaValidator.validate(recordedReview().second.boundedPayload(), "review-accounting")
  }

  @Test fun `a recorded review retains its measured sizes and none of the measured bodies`() {
    val (recorder, summary) = recordedReview()
    val payload = summary.boundedPayload()

    assertEquals(
      recorder.parentPrompts.sumOf { it.toByteArray().size.toLong() },
      requireNotNull(aggregate(payload))["launch_bytes"].toString().toLong(),
      "Launch bytes measure exactly the prompts the lanes were given.",
    )
    assertEquals(
      recorder.parentPrompts.size * toolOutputBody.toByteArray().size.toLong(),
      requireNotNull(aggregate(payload))["result_bytes"].toString().toLong(),
    )
    assertNoSentinels(payload.toString())
  }

  @Test fun `sqlite round trip preserves the payload and retains no measured content`() {
    withConnection { connection ->
      val accounting = reviewAccountingOnConnection(connection)
      val summary = recordedReview().second
      val payload = summary.boundedPayload()

      accounting.upsert(ReviewAccountingRecord(REVIEW_RUN_ID, summary.packetDigest, summary))
      val loaded = assertNotNull(accounting.load(REVIEW_RUN_ID))

      assertEquals(JsonCodec.mapToJsonString(payload), JsonCodec.mapToJsonString(loaded.summary.boundedPayload()))
      assertNoSentinels(storedAccountingJson(connection))
      ReviewContextSchemaValidator.validate(loaded.summary.boundedPayload(), "durable-review-accounting")
    }
  }

  @Test fun `a legacy evidence-unreviewable segment quarantines and regenerates in band`() {
    withConnection { connection ->
      val accounting = reviewAccountingOnConnection(connection)
      val summary = recordedReview().second
      val current = summary.boundedPayload()
      val legacy = legacyEvidenceUnreviewablePayload(current)
      connection.prepareStatement(
        """
        INSERT INTO review_accounting (review_id, packet_digest, bounded_payload_json, updated_at)
        VALUES (?, ?, ?, CURRENT_TIMESTAMP)
        """.trimIndent(),
      ).use { statement ->
        statement.setString(1, REVIEW_RUN_ID)
        statement.setString(2, summary.packetDigest)
        statement.setString(3, JsonCodec.mapToJsonString(legacy))
        statement.executeUpdate()
      }

      assertNull(accounting.load(REVIEW_RUN_ID))
      val quarantined = telemetryOutboxOnConnection(connection).listPending(null)
      assertTrue(
        quarantined.any { record ->
          record.eventName == TelemetryOutboxEvent.REVIEW_STAGE_DEGRADATION.wireValue &&
            record.payloadJson.contains("accounting_contract_quarantined")
        },
      )

      accounting.upsert(ReviewAccountingRecord(REVIEW_RUN_ID, summary.packetDigest, summary))
      val regenerated = assertNotNull(accounting.load(REVIEW_RUN_ID))
      assertEquals(REVIEW_CONTEXT_CONTRACT_VERSION, regenerated.summary.boundedPayload()["contract_version"])
    }
  }

  @Test fun `a pre-bump accounting record quarantines and regenerates in band`() {
    withConnection { connection ->
      val accounting = reviewAccountingOnConnection(connection)
      val summary = recordedReview().second
      val current = summary.boundedPayload()
      val legacy = LinkedHashMap(current).apply { this["contract_version"] = "2.0" }
      connection.prepareStatement(
        """
        INSERT INTO review_accounting (review_id, packet_digest, bounded_payload_json, updated_at)
        VALUES (?, ?, ?, CURRENT_TIMESTAMP)
        """.trimIndent(),
      ).use { statement ->
        statement.setString(1, REVIEW_RUN_ID)
        statement.setString(2, summary.packetDigest)
        statement.setString(3, JsonCodec.mapToJsonString(legacy))
        statement.executeUpdate()
      }

      assertNull(accounting.load(REVIEW_RUN_ID))
      val quarantined = telemetryOutboxOnConnection(connection).listPending(null)
      assertTrue(
        quarantined.any { record ->
          record.eventName == TelemetryOutboxEvent.REVIEW_STAGE_DEGRADATION.wireValue &&
            record.payloadJson.contains("accounting_contract_quarantined")
        },
      )

      accounting.upsert(ReviewAccountingRecord(REVIEW_RUN_ID, summary.packetDigest, summary))
      val regenerated = assertNotNull(accounting.load(REVIEW_RUN_ID))
      assertEquals(REVIEW_CONTEXT_CONTRACT_VERSION, regenerated.summary.boundedPayload()["contract_version"])
      assertEquals("2.4", regenerated.summary.boundedPayload()["contract_version"])
    }
  }

  @Test fun `a legacy accounting row with retired usage fields remains readable`() {
    withConnection { connection ->
      val accounting = reviewAccountingOnConnection(connection)
      val summary = recordedReview().second
      val legacy = legacyAccountingPayload(summary.boundedPayload())
      connection.prepareStatement(
        """
        INSERT INTO review_accounting (review_id, packet_digest, bounded_payload_json, updated_at)
        VALUES (?, ?, ?, CURRENT_TIMESTAMP)
        """.trimIndent(),
      ).use { statement ->
        statement.setString(1, REVIEW_RUN_ID)
        statement.setString(2, summary.packetDigest)
        statement.setString(3, JsonCodec.mapToJsonString(legacy))
        statement.executeUpdate()
      }

      val loaded = assertNotNull(accounting.load(REVIEW_RUN_ID))
      assertEquals("2.1", loaded.summary.boundedPayload()["contract_version"])
      assertEquals(JsonCodec.mapToJsonString(legacy), storedAccountingJson(connection))
    }
  }

  private fun recordedReview(): Pair<ReviewRecorder, ReviewAccountingSummary> {
    val recorder = ReviewRecorder()
    val runner =
      reviewHarness(
        ReviewHarnessConfig(
          manifests =
            listOf(
              reviewPack("kotlin", listOf("architecture", "security"), routingSignals = listOf("*.kt", "*.md")),
            ),
          diff =
            diffForChanges(
              "src/Repo.kt" to "val diffBody = \"$diffBody\"",
              "docs/GUIDANCE.md" to guidanceBody,
            ),
          response = {
            RecordedWorkerResponse(
              stdout = toolOutputBody,
            )
          },
          rubricBody = { rubricBody },
        ),
        recorder,
      )

    val result =
      runner.reviewed(
        harnessRequest(
          reviewRunId = REVIEW_RUN_ID,
          prelaunchExpansions =
            listOf(
              ReviewPrelaunchExpansion(
                "parallel-code-review",
                "src/Repo.kt",
                "The durable redaction proof measures an explicitly authorized complete-file expansion.",
              ),
            ),
        ),
      )

    return recorder to assertNotNull(result.accountingSummary, "The recorded review produced no accounting.")
  }

  private fun legacyEvidenceUnreviewablePayload(current: Map<String, Any?>): Map<String, Any?> {
    val digest = "a".repeat(64)
    val lanes =
      requireNotNull(JsonCodec.anyToStringAnyMapList((current["lanes"]))).map { lane ->
        if (lane["lane"] == "parent") {
          lane
        } else {
          LinkedHashMap(lane).apply {
            put("bundle_composition_digest", digest)
            put(
              "segment_accounting",
              listOf(
                mapOf(
                  "segment_id" to "seg-000",
                  "measured_bytes" to 128L,
                  "entry_count" to 2,
                  "composition_digest" to digest,
                ),
              ),
            )
            put("unreviewed_segment_ids", listOf("evidence-unreviewable"))
          }
        }
      }
    return LinkedHashMap(current).apply { put("lanes", lanes) }
  }

  private fun legacyAccountingPayload(current: Map<String, Any?>): Map<String, Any?> {
    val usage = mapOf("input_tokens" to 1L, "ownership" to "direct")
    val legacyNodes =
      requireNotNull(JsonCodec.anyToStringAnyMapList((current["lanes"]))).map { lane ->
        LinkedHashMap(lane).apply {
          put("provider_usage", usage)
          put("direct_usage", usage)
          put("inclusive_usage", usage)
        }
      }
    val parent =
      LinkedHashMap(requireNotNull(JsonCodec.anyToStringAnyMap(current["parent"]))).apply {
        put("provider_usage", usage)
        put("direct_usage", usage)
        put("inclusive_usage", usage)
      }
    val integration =
      JsonCodec.anyToStringAnyMap(current["integration"])?.let {
        LinkedHashMap(it).apply { put("usage", emptyMap<String, Any?>()) }
      }
    return LinkedHashMap(current).apply {
      put("contract_version", "2.1")
      put("parent", parent)
      put("lanes", legacyNodes)
      put("aggregate_direct_usage", emptyMap<String, Any?>())
      put("aggregate_inclusive_usage", emptyMap<String, Any?>())
      put("budget_regression", false)
      put("integration", integration)
    }
  }

  private fun aggregate(payload: Map<String, Any?>): Map<String, Any?>? =
    JsonCodec.anyToStringAnyMap(payload["aggregate_counters"])

  private fun assertNoSentinels(serialized: String) =
    sentinels.forEach { sentinel ->
      assertFalse(serialized.contains(sentinel), "Review accounting leaked '$sentinel'.")
    }

  private fun storedAccountingJson(connection: Connection): String =
    connection.prepareStatement("SELECT bounded_payload_json FROM review_accounting").use { statement ->
      statement.executeQuery().use { rows ->
        buildString { while (rows.next()) append(rows.getString(1)) }
      }
    }

  private fun withConnection(block: (Connection) -> Unit) {
    val dbPath = Files.createTempDirectory("review-accounting-redaction").resolve("metrics.db")
    ensureTestDatabase(dbPath).use(block)
  }

  private companion object {
    const val REVIEW_RUN_ID = "rvw-20260722-101500-ab12"
  }
}

private fun ReviewAccountingSummary.boundedPayload(): Map<String, Any?> =
  requireNotNull(
    JsonCodec.parseObjectOrNull(toReviewAccountingBoundedJson())
      ?.let(JsonCodec::jsonElementToValue)
      ?.let(JsonCodec::anyToStringAnyMap),
  )
