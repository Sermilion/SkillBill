package skillbill.infrastructure.sqlite.review.stage

import skillbill.infrastructure.sqlite.columnNames
import skillbill.infrastructure.sqlite.core.migration.DatabaseReviewColumnMigrations
import skillbill.infrastructure.sqlite.tempDbConnection
import skillbill.infrastructure.sqlite.worklist.required
import skillbill.review.context.model.packet.ReviewLaneSegmentAccounting
import skillbill.review.context.model.packet.ReviewRunLaneSegmentAccountingJson
import skillbill.review.model.ReviewLaneResolutionState
import skillbill.review.model.ReviewLaneReviewDisposition
import skillbill.review.model.ReviewRunLane
import skillbill.review.parsing.ReviewRunLaneResolver
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewRunLaneDispositionPersistenceTest {
  @Test fun `disposition bundle digest and segment accounting round-trip`() {
    val (_, connection) = tempDbConnection("review-lane-disposition")
    connection.use {
      val segments =
        listOf(
          ReviewLaneSegmentAccounting("seg-000", 120, 2, "a".repeat(64)),
          ReviewLaneSegmentAccounting("seg-001", 80, 1, "b".repeat(64)),
        )
      val lane =
        ReviewRunLane(
          laneSkillName = "bill-kotlin-code-review-security",
          packSlug = "kotlin",
          area = "security",
          depth = 0,
          required = false,
          orderIndex = 0,
          originLayerChain = listOf("kotlin"),
          resolutionState = ReviewLaneResolutionState.RESOLVED,
          reviewDisposition = ReviewLaneReviewDisposition.INCOMPLETE,
          bundleCompositionDigest = "c".repeat(64),
          segmentAccountingJson = ReviewRunLaneSegmentAccountingJson.encode(segments),
          unreviewedSegmentIds = listOf("unreviewable"),
          budgetDimension = "lane_launch_bytes",
        )
      replaceReviewRunLanes(it, RUN_ID, listOf(lane))

      val persisted = fetchReviewRunLanes(it, RUN_ID).single()
      assertEquals("incomplete", persisted.reviewDisposition.wireValue)
      assertEquals("c".repeat(64), persisted.bundleCompositionDigest)
      assertEquals(listOf("unreviewable"), persisted.unreviewedSegmentIds)
      assertEquals("lane_launch_bytes", persisted.budgetDimension)
      assertEquals(segments, ReviewRunLaneSegmentAccountingJson.decode(persisted.segmentAccountingJson))
    }
  }

  @Test fun `lanesToResume returns only incomplete lanes`() {
    val complete = lane()
    val incomplete = lane(unreviewedSegmentIds = listOf("unreviewable"))
    assertEquals(listOf(incomplete), ReviewRunLaneResolver.lanesToResume(listOf(complete, incomplete)))
  }

  @Test fun `completed lane durable findings are excluded from resume selection`() {
    val lanes =
      listOf(
        lane(bundleCompositionDigest = "d".repeat(64)),
        lane(
          laneSkillName = "bill-kotlin-code-review-testing",
          unreviewedSegmentIds = listOf("seg-001"),
        ),
      )
    val resume = ReviewRunLaneResolver.lanesToResume(lanes)
    assertEquals(1, resume.size)
    assertEquals("bill-kotlin-code-review-testing", resume.single().laneSkillName)
  }

  @Test fun `segment accounting json codec round-trips empty and populated arrays`() {
    assertEquals(emptyList(), ReviewRunLaneSegmentAccountingJson.decode(null))
    assertEquals(emptyList(), ReviewRunLaneSegmentAccountingJson.decode("[]"))
    val encoded =
      ReviewRunLaneSegmentAccountingJson.encode(
        listOf(ReviewLaneSegmentAccounting("seg-000", 10, 1, "e".repeat(64))),
      )
    assertEquals(
      ReviewLaneSegmentAccounting("seg-000", 10, 1, "e".repeat(64)),
      ReviewRunLaneSegmentAccountingJson.decode(encoded).single(),
    )
  }

  @Test fun `ensureReviewRunLaneDispositionColumns upgrades legacy review_run_lanes tables`() {
    val tempDir = Files.createTempDirectory("review-lane-legacy-columns")
    val dbPath = tempDir.resolve("legacy.db")
    DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
      seedLegacyReviewRunLanes(connection)
      DatabaseReviewColumnMigrations.ensureReviewRunLaneDispositionColumns(connection)
      assertTrue(
        columnNames(connection, "review_run_lanes").containsAll(
          setOf(
            "review_disposition",
            "bundle_composition_digest",
            "segment_accounting_json",
            "unreviewed_segment_ids",
            "budget_dimension",
          ),
        ),
      )
      replaceReviewRunLanes(
        connection,
        RUN_ID,
        listOf(
          lane(
            bundleCompositionDigest = "f".repeat(64),
            segmentAccountingJson =
              ReviewRunLaneSegmentAccountingJson.encode(
                listOf(ReviewLaneSegmentAccounting("seg-000", 10, 1, "a".repeat(64))),
              ),
            unreviewedSegmentIds = listOf("seg-000"),
          ),
        ),
      )
      assertEquals("incomplete", fetchReviewRunLanes(connection, RUN_ID).single().reviewDisposition.wireValue)
    }
  }

  private fun seedLegacyReviewRunLanes(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute(
        """
        CREATE TABLE review_runs (
          review_run_id TEXT PRIMARY KEY,
          review_session_id TEXT NOT NULL,
          raw_text TEXT NOT NULL
        )
        """.trimIndent(),
      )
      statement.execute(
        """
        CREATE TABLE review_run_lanes (
          review_run_id TEXT NOT NULL,
          lane_skill_name TEXT NOT NULL,
          pack_slug TEXT NOT NULL,
          area TEXT NOT NULL,
          depth INTEGER NOT NULL DEFAULT 0,
          required INTEGER NOT NULL DEFAULT 0,
          order_index INTEGER NOT NULL DEFAULT 0,
          origin_layer_chain TEXT NOT NULL DEFAULT '',
          resolution_state TEXT NOT NULL DEFAULT 'resolved',
          recorded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (review_run_id, lane_skill_name)
        )
        """.trimIndent(),
      )
      statement.execute(
        "INSERT INTO review_runs (review_run_id, review_session_id, raw_text) VALUES ('$RUN_ID', '$RUN_ID', '')",
      )
      statement.execute(
        """
        INSERT INTO review_run_lanes (
          review_run_id, lane_skill_name, pack_slug, area, depth, required, order_index,
          origin_layer_chain, resolution_state
        ) VALUES ('$RUN_ID', 'bill-kotlin-code-review-security', 'kotlin', 'security', 0, 0, 0, 'kotlin', 'resolved')
        """.trimIndent(),
      )
    }
  }

  private fun lane(
    laneSkillName: String = "bill-kotlin-code-review-security",
    bundleCompositionDigest: String? = null,
    segmentAccountingJson: String? = null,
    unreviewedSegmentIds: List<String> = emptyList(),
  ) = ReviewRunLane(
    laneSkillName = laneSkillName,
    packSlug = "kotlin",
    area = laneSkillName.substringAfterLast('-'),
    depth = 0,
    required = false,
    orderIndex = 0,
    originLayerChain = listOf("kotlin"),
    resolutionState = ReviewLaneResolutionState.RESOLVED,
    reviewDisposition =
      if (unreviewedSegmentIds.isEmpty()) {
        ReviewLaneReviewDisposition.COMPLETE
      } else {
        ReviewLaneReviewDisposition.INCOMPLETE
      },
    bundleCompositionDigest = bundleCompositionDigest,
    segmentAccountingJson = segmentAccountingJson,
    unreviewedSegmentIds = unreviewedSegmentIds,
    budgetDimension = "lane_launch_bytes".takeIf { unreviewedSegmentIds.isNotEmpty() },
  )

  private fun columnNames(
    connection: Connection,
    table: String,
  ): Set<String> =
    connection.createStatement().use { statement ->
      statement.executeQuery("PRAGMA table_info($table)").use { resultSet ->
        buildSet {
          while (resultSet.next()) {
            add(resultSet.getString("name"))
          }
        }
      }
    }

  private companion object {
    const val RUN_ID = "rvw-disposition-001"
  }
}
