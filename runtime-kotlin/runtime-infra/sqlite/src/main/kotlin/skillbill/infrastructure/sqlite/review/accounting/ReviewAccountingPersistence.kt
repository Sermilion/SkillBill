package skillbill.infrastructure.sqlite.review.accounting

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.review.stage.ReviewRuntime
import skillbill.infrastructure.sqlite.review.stage.effectiveLaneName
import skillbill.infrastructure.sqlite.review.stage.fetchFindingLaneAttribution
import skillbill.infrastructure.sqlite.review.stage.fetchReviewRunLanes
import skillbill.infrastructure.sqlite.review.stage.replaceReviewRunLanes
import skillbill.infrastructure.sqlite.review.stage.reviewSummarySql
import skillbill.infrastructure.sqlite.review.stage.toReviewSummary
import skillbill.infrastructure.sqlite.review.stage.updateFindingLaneAttribution
import skillbill.infrastructure.sqlite.review.stats.ReviewStatsRuntime
import skillbill.infrastructure.sqlite.telemetry.lifecycle.LifecycleTelemetryStore
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.review.model.ReviewAccountingRecord
import skillbill.review.model.ImportedFinding
import skillbill.review.model.ImportedReview
import skillbill.review.model.ReviewRunLane
import skillbill.review.model.ReviewStageDegradationMeasurement
import skillbill.review.model.ReviewStageDegradationReason
import skillbill.review.model.ReviewSummary
import skillbill.workflow.model.goalreview.toReviewAccountingBoundedJson
import java.sql.Connection

internal fun upsertReviewAccounting(
  connection: Connection,
  record: ReviewAccountingRecord,
) {
  connection.prepareStatement(
    """
    INSERT INTO review_accounting (review_id, packet_digest, bounded_payload_json, updated_at)
    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
    ON CONFLICT(review_id) DO UPDATE SET
      packet_digest = excluded.packet_digest,
      bounded_payload_json = excluded.bounded_payload_json,
      updated_at = CURRENT_TIMESTAMP
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(
      record.reviewId,
      record.packetDigest,
      record.summary.toReviewAccountingBoundedJson(),
    )
    statement.executeUpdate()
  }
}

internal fun loadReviewAccounting(
  connection: Connection,
  reviewId: String,
  runtimeVersion: String,
  diagnostics: RuntimeDiagnostics,
): ReviewAccountingRecord? =
  connection.prepareStatement(
    "SELECT packet_digest, bounded_payload_json FROM review_accounting WHERE review_id = ?",
  ).use { statement ->
    statement.bindAll(reviewId)
    statement.executeQuery().use { rows ->
      if (!rows.next()) return@use null
      val payload =
        requireNotNull(decodeBoundedAccounting(rows.getString("bounded_payload_json"))) {
          "Malformed bounded review accounting for '$reviewId'."
        }
      val declaredVersion = payload[SharedPayloadKeys.CONTRACT_VERSION]?.toString()
      if (
        declaredVersion != REVIEW_CONTEXT_CONTRACT_VERSION &&
        declaredVersion != LEGACY_REVIEW_CONTEXT_CONTRACT_VERSION
      ) {
        quarantineReviewAccounting(connection, runtimeVersion, diagnostics, reviewId, declaredVersion)
        return@use null
      }
      if (payloadCarriesLegacyEvidenceUnreviewableSegment(payload)) {
        quarantineReviewAccounting(connection, runtimeVersion, diagnostics, reviewId, declaredVersion)
        return@use null
      }
      ReviewAccountingRecord(
        reviewId,
        rows.getString("packet_digest"),
        decodeReviewAccountingSummary(payload),
      )
    }
  }

private const val ACCOUNTING_LOAD_SEAM: String = "ReviewAccountingPersistence.loadReviewAccounting"

private const val LEGACY_REVIEW_CONTEXT_CONTRACT_VERSION: String = "2.1"

private fun quarantineReviewAccounting(
  connection: Connection,
  runtimeVersion: String,
  diagnostics: RuntimeDiagnostics,
  reviewId: String,
  declaredVersion: String?,
) {
  LifecycleTelemetryStore(connection, runtimeVersion, diagnostics).reviewStageDegradation(
    ReviewStageDegradationMeasurement(
      reviewRunId = reviewId,
      seam = ACCOUNTING_LOAD_SEAM,
      expected = REVIEW_CONTEXT_CONTRACT_VERSION,
      actual = declaredVersion?.takeIf(String::isNotBlank) ?: "<missing>",
      reason = ReviewStageDegradationReason.ACCOUNTING_CONTRACT_QUARANTINED,
    ),
  )
}

private fun decodeBoundedAccounting(rawJson: String): Map<String, Any?>? =
  JsonCodec.parseObjectOrNull(rawJson)?.let {
    JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(it))
  }

internal fun existingReviewSummary(
  connection: Connection,
  reviewRunId: String,
): ReviewSummary? =
  connection.prepareStatement(reviewSummarySql).use { statement ->
    statement.bindAll(reviewRunId)
    statement.executeQuery().use { resultSet ->
      if (resultSet.next()) resultSet.toReviewSummary() else null
    }
  }

internal fun reviewSummaryChanged(
  existingReviewSummary: ReviewSummary?,
  review: ImportedReview,
  existingFindings: List<ImportedFinding>,
): Boolean =
  existingReviewSummary == null ||
    existingReviewSummary.reviewSessionId != review.reviewSessionId ||
    existingReviewSummary.routedSkill != review.routedSkill ||
    existingReviewSummary.detectedScope != review.detectedScope ||
    existingReviewSummary.detectedStack != review.detectedStack ||
    existingReviewSummary.executionMode != review.executionMode ||
    existingReviewSummary.routedSkillCanonical != review.routedSkillCanonical ||
    existingReviewSummary.detectedStackCanonical != review.detectedStackCanonical ||
    existingReviewSummary.detectedScopeCanonical != review.detectedScopeCanonical ||
    existingReviewSummary.detectedScopeDetail != review.detectedScopeDetail ||
    existingReviewSummary.specialistReviewsRaw != review.specialistReviews.joinToString(",") ||
    existingFindings != review.findings

internal fun upsertReviewRun(
  connection: Connection,
  review: ImportedReview,
  sourcePath: String?,
) {
  connection.prepareStatement(
    """
    INSERT INTO review_runs (
      review_run_id,
      review_session_id,
      routed_skill,
      detected_scope,
      detected_stack,
      execution_mode,
      routed_skill_canonical,
      detected_stack_canonical,
      detected_scope_canonical,
      detected_scope_detail,
      specialist_reviews,
      source_path,
      raw_text
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(review_run_id) DO UPDATE SET
      review_session_id = excluded.review_session_id,
      routed_skill = excluded.routed_skill,
      detected_scope = excluded.detected_scope,
      detected_stack = excluded.detected_stack,
      execution_mode = excluded.execution_mode,
      routed_skill_canonical = excluded.routed_skill_canonical,
      detected_stack_canonical = excluded.detected_stack_canonical,
      detected_scope_canonical = excluded.detected_scope_canonical,
      detected_scope_detail = excluded.detected_scope_detail,
      specialist_reviews = excluded.specialist_reviews,
      source_path = excluded.source_path,
      raw_text = excluded.raw_text
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(
      review.reviewRunId,
      review.reviewSessionId,
      review.routedSkill,
      review.detectedScope,
      review.detectedStack,
      review.executionMode?.wireValue,
      review.routedSkillCanonical,
      review.detectedStackCanonical,
      review.detectedScopeCanonical,
      review.detectedScopeDetail,
      review.specialistReviews.joinToString(","),
      sourcePath,
      review.rawText,
    )
    statement.executeUpdate()
  }
}

internal fun persistImportedReview(
  connection: Connection,
  review: ImportedReview,
  sourcePath: String?,
) {
  val existingReviewSummary = existingReviewSummary(connection, review.reviewRunId)
  val existingFindings = ReviewRuntime.fetchImportedFindings(connection, review.reviewRunId)
  val summarySnapshotChanged = reviewSummaryChanged(existingReviewSummary, review, existingFindings)
  val existingLanes = fetchReviewRunLanes(connection, review.reviewRunId)
  val lanes = existingLanes.ifEmpty { review.planLanes }
  upsertReviewRun(connection, review, sourcePath)
  if (existingLanes.isEmpty()) {
    replaceReviewRunLanes(connection, review.reviewRunId, review.planLanes)
  }
  if (summarySnapshotChanged) {
    ReviewStatsRuntime.clearReviewFinishedTelemetryState(connection, review.reviewRunId)
  }
  val recordedLanes = fetchFindingLaneAttribution(connection, review.reviewRunId)

  if (existingFindings.withoutLanes() != review.findings.withoutLanes()) {
    replaceFindings(connection, review, lanes, recordedLanes)
  } else {
    updateFindingLaneAttribution(connection, review, lanes, recordedLanes)
  }
}

private fun List<ImportedFinding>.withoutLanes(): List<ImportedFinding> = map { it.copy(laneSkillName = null) }

internal fun replaceFindings(
  connection: Connection,
  review: ImportedReview,
  lanes: List<ReviewRunLane>,
  recordedLanes: Map<String, String> = emptyMap(),
) {
  val lanesByName = lanes.associateBy { it.laneSkillName }
  connection.prepareStatement("DELETE FROM findings WHERE review_run_id = ?").use { statement ->
    statement.bindAll(review.reviewRunId)
    statement.executeUpdate()
  }
  review.findings.forEach { finding ->
    val laneName = finding.effectiveLaneName(recordedLanes)
    val lane = laneName?.let(lanesByName::get)
    connection.prepareStatement(
      """
      INSERT INTO findings (
        review_run_id,
        finding_id,
        severity,
        confidence,
        issue_category,
        location,
        description,
        finding_text,
        lane_skill_name,
        lane_area,
        lane_pack_slug
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        review.reviewRunId,
        finding.findingId,
        finding.severity,
        finding.confidence,
        finding.issueCategory,
        finding.location,
        finding.description,
        finding.findingText,
        laneName,
        lane?.area,
        lane?.packSlug,
      )
      statement.executeUpdate()
    }
  }
}
