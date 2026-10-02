package skillbill.application.review.stats

import skillbill.application.review.model.ReviewPrelaunchExpansion
import skillbill.application.review.snapshot.RecordedWorkerResponse
import skillbill.application.review.snapshot.ReviewHarnessConfig
import skillbill.application.review.snapshot.ReviewRecorder
import skillbill.application.review.snapshot.diffForPaths
import skillbill.application.review.snapshot.harnessRequest
import skillbill.application.review.snapshot.reviewHarness
import skillbill.application.review.snapshot.reviewPack
import skillbill.application.review.snapshot.reviewed
import skillbill.application.review.snapshot.validateReviewContextPayload
import skillbill.contracts.JsonCodec
import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.contracts.review.ReviewAccountingPayloadKeys
import skillbill.ports.review.model.ReviewAccountingRecord
import skillbill.review.context.ReviewTreeAccounting
import skillbill.review.context.model.accounting.ReviewAccountingCounters
import skillbill.review.context.model.accounting.ReviewAccountingInput
import skillbill.review.context.model.accounting.ReviewAccountingSummary
import skillbill.review.context.model.accounting.ReviewAccountingTerminalOutcome
import skillbill.review.context.model.packet.ReviewLaneSegmentAccounting
import skillbill.workflow.model.goalreview.toReviewAccountingBoundedJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReviewAccountingProjectionRedactionTest {
  private val forbidden = listOf("DIFF_SECRET", "RUBRIC_SECRET", "TOOL_OUTPUT_SECRET")

  @Test fun `projection of a real review contains bounded metadata and no content bodies`() {
    val (recorder, recorded) = recordedReview()
    val serialized = recorded.toReviewAccountingBoundedJson()

    assertTrue(
      recorder.parentPrompts.isNotEmpty() &&
        recorder.parentPrompts.all { prompt -> forbidden.dropLast(1).all { prompt.contains(it) } },
      "The projection proof needs a run whose prompts actually carried rubric bodies and owned paths.",
    )
    forbidden.forEach { assertFalse(serialized.contains(it), "Accounting projection leaked '$it'.") }
    assertTrue(recorded.aggregateCounters.launchBytes > 0)
  }

  @Test fun `projection contains bounded metadata and no content bodies`() {
    val parent =
      requireNotNull(JsonCodec.anyToStringAnyMap(summary().boundedPayload()[ReviewAccountingPayloadKeys.PARENT]))

    assertEquals(2L, (parent[ReviewAccountingPayloadKeys.TOOL_CALLS] as Number).toLong())
  }

  @Test fun `projection exposes exactly the bounded contract keys`() {
    val payload = summary().boundedPayload()

    assertEquals(
      setOf(
        ReviewAccountingPayloadKeys.CONTRACT_VERSION,
        ReviewAccountingPayloadKeys.KIND,
        ReviewAccountingPayloadKeys.REVIEW_ID,
        ReviewAccountingPayloadKeys.PACKET_DIGEST,
        ReviewAccountingPayloadKeys.PARENT,
        ReviewAccountingPayloadKeys.LANES,
        ReviewAccountingPayloadKeys.COMMIT_ROUTING_ACCOUNTING,
        ReviewAccountingPayloadKeys.PARENT_ANALYSIS_CONSUMPTION,
        ReviewAccountingPayloadKeys.INTEGRATION,
        ReviewAccountingPayloadKeys.AGGREGATE_COUNTERS,
      ),
      payload.keys,
    )
    assertEquals(REVIEW_CONTEXT_CONTRACT_VERSION, payload[ReviewAccountingPayloadKeys.CONTRACT_VERSION])
    val parent = requireNotNull(JsonCodec.anyToStringAnyMap(payload[ReviewAccountingPayloadKeys.PARENT]))
    assertEquals(
      setOf(
        ReviewAccountingPayloadKeys.LANE,
        ReviewAccountingPayloadKeys.ASSIGNMENT_DIGEST,
        ReviewAccountingPayloadKeys.LAUNCH_BYTES,
        ReviewAccountingPayloadKeys.EVIDENCE_BYTES,
        ReviewAccountingPayloadKeys.RESULT_BYTES,
        ReviewAccountingPayloadKeys.EXPANSIONS,
        ReviewAccountingPayloadKeys.TOOL_CALLS,
        ReviewAccountingPayloadKeys.MODEL_TURNS,
        ReviewAccountingPayloadKeys.INCLUSIVE_COUNTERS,
        ReviewAccountingPayloadKeys.TERMINAL_OUTCOME,
      ),
      parent.keys,
    )
    val counters = requireNotNull(JsonCodec.anyToStringAnyMap(payload[ReviewAccountingPayloadKeys.AGGREGATE_COUNTERS]))
    assertEquals(
      setOf(
        ReviewAccountingPayloadKeys.LAUNCH_BYTES,
        ReviewAccountingPayloadKeys.EVIDENCE_BYTES,
        ReviewAccountingPayloadKeys.RESULT_BYTES,
        ReviewAccountingPayloadKeys.EXPANSIONS,
        ReviewAccountingPayloadKeys.TOOL_CALLS,
        ReviewAccountingPayloadKeys.MODEL_TURNS,
      ),
      counters.keys,
    )
  }

  @Test fun `lane nodes project bundle composition and segment accounting`() {
    val digest = "a".repeat(64)
    val summary =
      ReviewTreeAccounting.summarize(
        "review-id",
        "packet-digest",
        ReviewAccountingInput(
          lane = "parent",
          assignmentDigest = "assignment-digest",
          children =
            listOf(
              ReviewAccountingInput(
                lane = "architecture",
                assignmentDigest = "architecture-digest",
                terminalOutcome = ReviewAccountingTerminalOutcome.INCOMPLETE,
                bundleCompositionDigest = digest,
                segmentAccounting = listOf(ReviewLaneSegmentAccounting("seg-000", 128, 2, digest)),
                unreviewedSegmentIds = listOf("unreviewable"),
              ),
            ),
        ),
      )
    val lane =
      requireNotNull(
        JsonCodec.anyToStringAnyMapList(summary.boundedPayload()[ReviewAccountingPayloadKeys.LANES]),
      ).single()
    assertEquals(digest, lane[ReviewAccountingPayloadKeys.BUNDLE_COMPOSITION_DIGEST])
    assertEquals(listOf("unreviewable"), lane[ReviewAccountingPayloadKeys.UNREVIEWED_SEGMENT_IDS])
    val segments = requireNotNull(JsonCodec.anyToStringAnyMapList(lane[ReviewAccountingPayloadKeys.SEGMENT_ACCOUNTING]))
    assertEquals("seg-000", segments.single()[ReviewAccountingPayloadKeys.SEGMENT_ID])
    assertEquals(128L, (segments.single()[ReviewAccountingPayloadKeys.MEASURED_BYTES] as Number).toLong())
    assertEquals(2L, (segments.single()[ReviewAccountingPayloadKeys.ENTRY_COUNT] as Number).toLong())
  }

  @Test fun `incomplete broker-refusal accounting projects refused segment ids only`() {
    val digest = "a".repeat(64)
    val summary =
      ReviewTreeAccounting.summarize(
        "review-id",
        "packet-digest",
        ReviewAccountingInput(
          lane = "parent",
          assignmentDigest = "assignment-digest",
          children =
            listOf(
              ReviewAccountingInput(
                lane = "architecture",
                assignmentDigest = "architecture-digest",
                terminalOutcome = ReviewAccountingTerminalOutcome.INCOMPLETE,
                bundleCompositionDigest = digest,
                segmentAccounting = listOf(ReviewLaneSegmentAccounting("seg-000", 128, 2, digest)),
                unreviewedSegmentIds = listOf("seg-evidence-refused"),
              ),
            ),
        ),
      )
    val lane =
      requireNotNull(
        JsonCodec.anyToStringAnyMapList(summary.boundedPayload()[ReviewAccountingPayloadKeys.LANES]),
      ).single()
    assertEquals(listOf("seg-evidence-refused"), lane[ReviewAccountingPayloadKeys.UNREVIEWED_SEGMENT_IDS])
    assertFalse(lane.toString().contains("evidence-unreviewable"))
  }

  @Test fun `bounded payload survives the durable record contract`() {
    val recorded = recordedReview().second
    val payload = recorded.boundedPayload()

    val record = ReviewAccountingRecord(recorded.reviewId, recorded.packetDigest, recorded)

    assertEquals(payload, record.summary.boundedPayload())
    forbidden.forEach { assertFalse(record.summary.toReviewAccountingBoundedJson().contains(it)) }
    validateReviewContextPayload(payload, "review-accounting-record")
  }

  @Test fun `durable record rejects an accounting payload carrying content`() {
    val leaking = summary().boundedPayload() + ("prompt" to "PROMPT_SECRET")

    val failure =
      runCatching {
        validateReviewContextPayload(leaking, "review-accounting-leak")
      }.exceptionOrNull()

    assertTrue(failure != null, "Content-bearing accounting payload must fail schema validation.")
  }

  private fun recordedReview(): Pair<ReviewRecorder, ReviewAccountingSummary> {
    val recorder = ReviewRecorder()
    val result =
      reviewHarness(
        ReviewHarnessConfig(
          manifests = listOf(reviewPack("kotlin", listOf("architecture"), routingSignals = listOf("*.kt"))),
          diff = diffForPaths("src/DIFF_SECRET.kt"),
          response = {
            RecordedWorkerResponse(
              stdout = "TOOL_OUTPUT_SECRET ".repeat(8),
            )
          },
          rubricBody = { "RUBRIC_SECRET ".repeat(8) },
        ),
        recorder,
      ).reviewed(
        harnessRequest(
          prelaunchExpansions =
            listOf(
              ReviewPrelaunchExpansion(
                "parallel-code-review",
                "src/DIFF_SECRET.kt",
                "The redaction test measures one explicitly authorized complete-file expansion.",
              ),
            ),
        ),
      )

    return recorder to assertNotNull(result.accountingSummary)
  }

  private fun summary() =
    ReviewTreeAccounting.summarize(
      "review-id",
      "packet-digest",
      ReviewAccountingInput(
        lane = "parent",
        assignmentDigest = "assignment-digest",
        counters = ReviewAccountingCounters(10, 20, 30, 1, 2, 3),
        children =
          listOf(
            ReviewAccountingInput(
              lane = "architecture",
              assignmentDigest = "architecture-digest",
              counters = ReviewAccountingCounters(11, 22, 33, 1, 1, 1),
            ),
          ),
      ),
    )
}

private fun ReviewAccountingSummary.boundedPayload(): Map<String, Any?> =
  requireNotNull(
    JsonCodec.parseObjectOrNull(toReviewAccountingBoundedJson())
      ?.let(JsonCodec::jsonElementToValue)
      ?.let(JsonCodec::anyToStringAnyMap),
  )
