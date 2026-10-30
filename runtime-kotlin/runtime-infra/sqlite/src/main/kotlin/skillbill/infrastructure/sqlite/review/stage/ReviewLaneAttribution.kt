package skillbill.infrastructure.sqlite.review.stage

import skillbill.contracts.review.ReviewFindingPayloadKeys
import skillbill.contracts.review.ReviewFinishedTelemetryPayloadKeys
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.review.core.acceptedFindingOutcomeTypes
import skillbill.infrastructure.sqlite.review.core.rejectedFindingOutcomeTypes
import skillbill.infrastructure.sqlite.telemetry.SqliteReviewTelemetryPayloadKeys
import skillbill.infrastructure.sqlite.worklist.required
import skillbill.ports.review.model.ReviewIntegrationPassRecord
import skillbill.review.context.model.packet.toStoredSegmentIdList
import skillbill.review.model.ImportedFinding
import skillbill.review.model.ImportedReview
import skillbill.review.model.ReviewLaneEffectivenessRow
import skillbill.review.model.ReviewLaneResolutionState
import skillbill.review.model.ReviewLaneReviewDisposition
import skillbill.review.model.ReviewRunLane
import java.sql.Connection

internal const val UNATTRIBUTED_LANE: String = "unattributed"
private const val UNRESOLVED_ROUTED_SKILL: String = "unresolved"

internal fun replaceReviewRunLanes(
  connection: Connection,
  reviewRunId: String,
  lanes: List<ReviewRunLane>,
) {
  reserveReviewRun(connection, reviewRunId)
  connection.prepareStatement("DELETE FROM review_run_lanes WHERE review_run_id = ?").use { statement ->
    statement.bindAll(reviewRunId)
    statement.executeUpdate()
  }
  lanes.forEach { lane ->
    connection.prepareStatement(
      """
      INSERT INTO review_run_lanes (
        review_run_id,
        lane_skill_name,
        pack_slug,
        area,
        depth,
        required,
        order_index,
        origin_layer_chain,
        resolution_state,
        review_disposition,
        bundle_composition_digest,
        segment_accounting_json,
        unreviewed_segment_ids,
        budget_dimension
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        reviewRunId,
        lane.laneSkillName,
        lane.packSlug,
        lane.area,
        lane.depth,
        lane.required,
        lane.orderIndex,
        lane.originLayerChain.joinToString("->"),
        lane.resolutionState.wireValue,
        lane.reviewDisposition.wireValue,
        lane.bundleCompositionDigest,
        lane.segmentAccountingJson,
        lane.unreviewedSegmentIds.toStoredSegmentIdList(),
        lane.budgetDimension,
      )
      statement.executeUpdate()
    }
  }
}

internal fun reserveReviewRun(
  connection: Connection,
  reviewRunId: String,
) {
  connection.prepareStatement(
    "INSERT OR IGNORE INTO review_runs (review_run_id, review_session_id, raw_text) VALUES (?, ?, '')",
  ).use { statement ->
    statement.bindAll(reviewRunId, reviewRunId)
    statement.executeUpdate()
  }
}

internal fun fetchReviewRunLanes(
  connection: Connection,
  reviewRunId: String,
): List<ReviewRunLane> =
  connection.prepareStatement(reviewRunLanesSql).use { statement ->
    statement.bindAll(reviewRunId)
    statement.executeQuery().use { resultSet ->
      buildList {
        while (resultSet.next()) {
          add(
            ReviewRunLane(
              laneSkillName = resultSet.getString("lane_skill_name"),
              packSlug = resultSet.getString("pack_slug"),
              area = resultSet.getString("area"),
              depth = resultSet.getInt("depth"),
              required = resultSet.getBoolean("required"),
              orderIndex = resultSet.getInt("order_index"),
              originLayerChain =
                resultSet.getString("origin_layer_chain")
                  .orEmpty()
                  .split("->")
                  .filter(String::isNotEmpty),
              resolutionState =
                ReviewLaneResolutionState.fromWire(resultSet.getString("resolution_state"))
                  ?: ReviewLaneResolutionState.UNRESOLVED,
              reviewDisposition =
                ReviewLaneReviewDisposition.fromWire(resultSet.getString("review_disposition"))
                  ?: ReviewLaneReviewDisposition.INCOMPLETE,
              bundleCompositionDigest = resultSet.getString("bundle_composition_digest"),
              segmentAccountingJson = resultSet.getString("segment_accounting_json"),
              unreviewedSegmentIds =
                resultSet.getString(
                  SqliteReviewTelemetryPayloadKeys.UNREVIEWED_SEGMENT_IDS,
                ).orEmpty().toStoredSegmentIdList(),
              budgetDimension = resultSet.getString("budget_dimension"),
            ),
          )
        }
      }
    }
  }

internal fun recordIntegrationPass(
  connection: Connection,
  reviewRunId: String,
  record: ReviewIntegrationPassRecord,
) {
  reserveReviewRun(connection, reviewRunId)
  connection.prepareStatement(
    """
    UPDATE review_runs
    SET integration_terminal_outcome = ?, integration_commit_sequence_digest = ?
    WHERE review_run_id = ?
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(record.terminalOutcome, record.commitSequenceDigest, reviewRunId)
    statement.executeUpdate()
  }
}

internal fun fetchIntegrationPass(
  connection: Connection,
  reviewRunId: String,
): ReviewIntegrationPassRecord? =
  connection.prepareStatement(
    "SELECT integration_terminal_outcome, integration_commit_sequence_digest " +
      "FROM review_runs WHERE review_run_id = ?",
  ).use { statement ->
    statement.bindAll(reviewRunId)
    statement.executeQuery().use { resultSet ->
      if (!resultSet.next()) return null
      val outcome = resultSet.getString("integration_terminal_outcome") ?: return null
      val digest = resultSet.getString("integration_commit_sequence_digest") ?: return null
      ReviewIntegrationPassRecord(commitSequenceDigest = digest, terminalOutcome = outcome)
    }
  }

internal fun queryReviewLaneEffectiveness(
  connection: Connection,
  reviewRunId: String?,
): List<ReviewLaneEffectivenessRow> {
  val counters = linkedMapOf<Triple<String, String, String>, MutableList<String>>()
  connection.prepareStatement(laneEffectivenessSql).use { statement ->
    statement.bindAll(reviewRunId, reviewRunId)
    statement.executeQuery().use { resultSet ->
      while (resultSet.next()) {
        val key =
          Triple(
            resultSet.getString("routed_skill_canonical") ?: UNRESOLVED_ROUTED_SKILL,
            resultSet.getString("pack_slug") ?: UNATTRIBUTED_LANE,
            resultSet.getString("area") ?: UNATTRIBUTED_LANE,
          )
        counters.getOrPut(key) {
          mutableListOf()
        } += resultSet.getString(ReviewFinishedTelemetryPayloadKeys.OUTCOME_TYPE).orEmpty()
      }
    }
  }
  return counters.map { (key, outcomes) ->
    ReviewLaneEffectivenessRow(
      routedSkillCanonical = key.first,
      packSlug = key.second,
      area = key.third,
      totalFindings = outcomes.size,
      acceptedFindings = outcomes.count { it in acceptedFindingOutcomeTypes },
      rejectedFindings = outcomes.count { it in rejectedFindingOutcomeTypes },
    )
  }
}

internal fun updateFindingLaneAttribution(
  connection: Connection,
  review: ImportedReview,
  lanes: List<ReviewRunLane>,
  recordedLanes: Map<String, String>,
) {
  val lanesByName = lanes.associateBy { it.laneSkillName }
  connection.prepareStatement(
    """
    UPDATE findings SET lane_skill_name = ?, lane_area = ?, lane_pack_slug = ?
    WHERE review_run_id = ? AND finding_id = ?
    """.trimIndent(),
  ).use { statement ->
    review.findings.forEach { finding ->
      val laneName = finding.effectiveLaneName(recordedLanes)
      val lane = laneName?.let(lanesByName::get)
      statement.bindAll(laneName, lane?.area, lane?.packSlug, review.reviewRunId, finding.findingId)
      statement.executeUpdate()
    }
  }
}

internal fun recordFindingLaneAttribution(
  connection: Connection,
  reviewRunId: String,
  attribution: Map<String, String>,
) {
  if (attribution.isEmpty()) return
  reserveReviewRun(connection, reviewRunId)
  connection.prepareStatement(
    """
    INSERT INTO review_run_finding_lanes (review_run_id, finding_id, lane_skill_name)
    VALUES (?, ?, ?)
    ON CONFLICT(review_run_id, finding_id) DO UPDATE SET lane_skill_name = excluded.lane_skill_name
    """.trimIndent(),
  ).use { statement ->
    attribution.forEach { (findingId, laneSkillName) ->
      statement.bindAll(reviewRunId, findingId, laneSkillName)
      statement.executeUpdate()
    }
  }
}

internal fun fetchFindingLaneAttribution(
  connection: Connection,
  reviewRunId: String,
): Map<String, String> =
  connection.prepareStatement(
    "SELECT finding_id, lane_skill_name FROM review_run_finding_lanes WHERE review_run_id = ?",
  ).use { statement ->
    statement.bindAll(reviewRunId)
    statement.executeQuery().use { resultSet ->
      buildMap {
        while (resultSet.next()) {
          put(resultSet.getString(ReviewFindingPayloadKeys.FINDING_ID), resultSet.getString("lane_skill_name"))
        }
      }
    }
  }

internal fun ImportedFinding.effectiveLaneName(recordedLanes: Map<String, String>): String? =
  recordedLanes[findingId] ?: laneSkillName
