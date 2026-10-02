
package skillbill.review.context.model.packet

import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.bundle.ReviewLaneBundle
import skillbill.review.context.model.bundle.ReviewLaneBundleEntry
import skillbill.review.context.model.commit.ReviewAssignment
import skillbill.review.context.model.commit.ReviewCommitCoverageFact
import skillbill.review.context.model.commit.ReviewCommitLaneDecision
import skillbill.review.context.model.commit.ReviewCommitLaneDisposition
import skillbill.review.context.model.commit.ReviewCommitLaneRoutingMatrix
import skillbill.review.context.model.commit.ReviewCommitSource
import skillbill.review.context.model.commit.ReviewCommitUnit
import skillbill.review.context.model.commit.ReviewLaneDecision
import skillbill.review.context.model.hunk.ReviewChangedHunk
import skillbill.review.context.model.hunk.ReviewRevision
import skillbill.review.context.model.launch.GovernedReviewLaunch
import skillbill.review.model.ReviewLaneReviewDisposition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReviewLaneBundleAssemblyTest {
  private val hunkA = ReviewChangedHunk("src/A.kt", 1, 1, 1, 2, "+alpha")
  private val hunkB = ReviewChangedHunk("src/B.kt", 4, 1, 4, 1, "+beta")
  private val hunkC = ReviewChangedHunk("src/C.kt", 7, 1, 7, 1, "+gamma")

  private fun unit(
    sha: String,
    parent: String,
    order: Int,
    hunks: List<ReviewChangedHunk>,
    subject: String = "commit $sha",
  ) = ReviewCommitUnit(sha, parent, subject, order, hunks, ReviewCommitSource.COMMIT_RANGE)

  private fun lane(
    name: String,
    paths: List<String>,
  ) = ReviewLaneDecision(
    name,
    true,
    "routed",
    ownedPaths = paths,
    originLayerChains = listOf(listOf("kotlin")),
    owningPack = "kotlin",
    specialistSkillName = "bill-kotlin-code-review-$name",
  )

  private fun focusedMatrix(
    units: List<ReviewCommitUnit>,
    lanes: List<String>,
    focusedShas: Set<String>,
  ) = ReviewCommitLaneRoutingMatrix(
    units.sortedBy { it.orderIndex }.map { it.commitSha },
    lanes,
    units.sortedBy { it.orderIndex }.flatMap { commit ->
      lanes.map {
        ReviewCommitLaneDecision(
          commit.commitSha,
          commit.orderIndex,
          it,
          if (commit.commitSha in focusedShas) {
            ReviewCommitLaneDisposition.FOCUSED
          } else {
            ReviewCommitLaneDisposition.SKIPPED
          },
          if (commit.commitSha in focusedShas) "focused" else "skipped for $it",
        )
      }
    },
  )

  private fun packet(
    units: List<ReviewCommitUnit>,
    hunks: List<ReviewChangedHunk> = units.flatMap { it.hunks },
    lanes: List<String> = listOf("security"),
    focusedShas: Set<String> = units.map { it.commitSha }.toSet(),
  ): ReviewContextPacket {
    val ordered = units.sortedBy { it.orderIndex }
    return ReviewContextPacket(
      reviewId = "review",
      repositoryIdentity = "repo",
      baseRevision = "base",
      headRevision = ordered.last().commitSha,
      status = "clean",
      stack = "kotlin",
      pack = "kotlin",
      addOns = emptyList(),
      selectedLanes = lanes,
      changedHunks = hunks,
      commitUnits = units,
      coverageFact =
        ReviewCommitCoverageFact(
          "base",
          ordered.last().commitSha,
          units.size,
          chainVerified = true,
          pathCoverageVerified = true,
        ),
      routingMatrix = focusedMatrix(units, lanes, focusedShas),
      reviewRevision = ReviewRevision("rvs", 1),
      laneDecisions = lanes.map { lane(it, hunks.map { hunk -> hunk.path }.distinct()) },
    )
  }

  private fun assignment(
    built: ReviewContextPacket,
    bundle: ReviewLaneBundle,
    hunks: List<String> = bundle.hunkIds,
  ) = ReviewAssignment(
    reviewId = built.reviewId,
    packetDigest = built.digest,
    lane = "security",
    baseRevision = built.baseRevision,
    headRevision = built.headRevision,
    assignedPaths = built.laneDecisions.single { it.lane == "security" }.normalizedOwnedPaths.sorted(),
    assignedHunks = hunks,
    assignedBundle = bundle,
    laneRouting = built.routingMatrix.decisionsFor("security"),
    reviewRevision = built.reviewRevision,
    laneDecision = built.laneDecisions.single { it.lane == "security" },
  )

  private fun launch(
    built: ReviewContextPacket,
    bundle: ReviewLaneBundle,
    budget: ReviewContextBudgetPolicy = ReviewContextBudgetPolicy.DEFAULT,
  ) = GovernedReviewLaunch(assignment(built, bundle), built, "contract", "rubric", "broker", budget)

  private val twoCommits =
    listOf(
      unit("c1", "base", 0, listOf(hunkA)),
      unit("head", "c1", 1, listOf(hunkB)),
    )

  private val fullBundle =
    ReviewLaneBundle(
      listOf(
        ReviewLaneBundleEntry("c1", 0, listOf(hunkA.hunkId)),
        ReviewLaneBundleEntry("head", 1, listOf(hunkB.hunkId)),
      ),
    )

  @Test fun `assembled bundle contains exactly assigned hunk ids and no unassigned hunk body`() {
    val built = packet(twoCommits, focusedShas = setOf("c1"))
    val partialBundle = ReviewLaneBundle(listOf(ReviewLaneBundleEntry("c1", 0, listOf(hunkA.hunkId))))
    val assembled = ReviewLaneAssembledBundle.assemble(assignment(built, partialBundle), built)

    assertEquals(listOf(hunkA.hunkId), assembled.hunkIds)
    assertTrue(hunkA.hunkId in launch(built, partialBundle).canonicalPayload)
    assertTrue(hunkB.hunkId !in launch(built, partialBundle).canonicalPayload)
    assertTrue(hunkA.content !in launch(built, partialBundle).canonicalPayload)
  }

  @Test fun `assembled entries are ordered by commit order index then path and new start`() {
    val firstPath = ReviewChangedHunk("src/Z.kt", 1, 1, 1, 2, "+z-first")
    val secondPath = ReviewChangedHunk("src/A.kt", 1, 1, 1, 2, "+a-first")
    val laterLine = ReviewChangedHunk("src/A.kt", 5, 1, 5, 2, "+a-later")
    val units =
      listOf(
        unit("c1", "base", 0, listOf(firstPath, secondPath)),
        unit("head", "c1", 1, listOf(laterLine)),
      )
    val built = packet(units)
    val bundle =
      ReviewLaneBundle(
        listOf(
          ReviewLaneBundleEntry("c1", 0, listOf(firstPath.hunkId, secondPath.hunkId)),
          ReviewLaneBundleEntry("head", 1, listOf(laterLine.hunkId)),
        ),
      )
    val assembled = ReviewLaneAssembledBundle.assemble(assignment(built, bundle), built)

    assertEquals(listOf(0, 0, 1), assembled.entries.map { it.orderIndex })
    assertEquals(listOf("src/A.kt", "src/Z.kt", "src/A.kt"), assembled.entries.map { it.hunk.path })
    assertEquals(listOf(1, 1, 5), assembled.entries.map { it.hunk.newStart })
    assertEquals(listOf("c1", "c1", "head"), assembled.entries.map { it.commitSha })
    assertEquals(listOf("base", "base", "c1"), assembled.entries.map { it.parentSha })
    assertEquals(listOf("commit c1", "commit c1", "commit head"), assembled.entries.map { it.subject })
  }

  @Test fun `composition digest changes when assigned hunks or commit order change and stays stable otherwise`() {
    val built = packet(twoCommits)
    val base = ReviewLaneAssembledBundle.assemble(assignment(built, fullBundle), built)
    val partialBundle = ReviewLaneBundle(listOf(ReviewLaneBundleEntry("c1", 0, listOf(hunkA.hunkId))))
    val partial = ReviewLaneAssembledBundle.assemble(assignment(built, partialBundle), built)
    val alternateCommits =
      packet(
        listOf(unit("c1", "base", 0, listOf(hunkC)), unit("head", "c1", 1, listOf(hunkB))),
      )
    val alternateBundle =
      ReviewLaneBundle(
        listOf(
          ReviewLaneBundleEntry("c1", 0, listOf(hunkC.hunkId)),
          ReviewLaneBundleEntry("head", 1, listOf(hunkB.hunkId)),
        ),
      )
    val alternate = ReviewLaneAssembledBundle.assemble(assignment(alternateCommits, alternateBundle), alternateCommits)

    assertNotEquals(base.compositionDigest, partial.compositionDigest)
    assertNotEquals(base.compositionDigest, alternate.compositionDigest)
    assertEquals(
      base.compositionDigest,
      ReviewLaneAssembledBundle.assemble(assignment(built, fullBundle), built).compositionDigest,
    )
  }

  @Test fun `oversized bundle splits into the minimal segment count for a configured budget`() {
    val built = packet(listOf(unit("c1", "base", 0, listOf(hunkA, hunkB, hunkC))))
    val bundle =
      ReviewLaneAssembledBundle.assemble(
        assignment(
          built,
          ReviewLaneBundle(listOf(ReviewLaneBundleEntry("c1", 0, listOf(hunkA.hunkId, hunkB.hunkId, hunkC.hunkId)))),
        ),
        built,
      )
    val measure: (List<ReviewLaneAssembledEntry>) -> Long = { entries -> entries.size * 10L }
    val segmentation = segmentAssembledBundle(bundle, maxLaneLaunchBytes = 25, measure)

    assertEquals(2, segmentation.segments.size)
    assertEquals(2, segmentation.segments[0].entries.size)
    assertEquals(1, segmentation.segments[1].entries.size)
    assertEquals(20, segmentation.segments[0].measuredBytes)
    assertEquals(10, segmentation.segments[1].measuredBytes)
    assertTrue(segmentation.unreviewableEntries.isEmpty())
  }

  @Test fun `a bundle that fits produces exactly one segment`() {
    val assembled = ReviewLaneAssembledBundle.assemble(assignment(packet(twoCommits), fullBundle), packet(twoCommits))
    val segmentation = segmentAssembledBundle(assembled, maxLaneLaunchBytes = 100) { entries -> entries.size * 10L }

    assertEquals(1, segmentation.segments.size)
    assertEquals("seg-000", segmentation.segments.single().segmentId)
    assertEquals(2, segmentation.segments.single().entries.size)
  }

  @Test fun `size-driven split can place one commit hunks across two segments`() {
    val sharedCommit = unit("c1", "base", 0, listOf(hunkA, hunkB, hunkC))
    val assembled =
      ReviewLaneAssembledBundle.assemble(
        assignment(
          packet(listOf(sharedCommit)),
          ReviewLaneBundle(listOf(ReviewLaneBundleEntry("c1", 0, listOf(hunkA.hunkId, hunkB.hunkId, hunkC.hunkId)))),
        ),
        packet(listOf(sharedCommit)),
      )
    val segmentation = segmentAssembledBundle(assembled, maxLaneLaunchBytes = 25) { entries -> entries.size * 10L }

    assertEquals(2, segmentation.segments.size)
    assertEquals(setOf("c1"), segmentation.segments.flatMap { it.entries }.map { it.commitSha }.toSet())
    assertEquals(listOf(0, 0), segmentation.segments.map { it.entries.first().orderIndex })
  }

  @Test fun `every segment carries commit identity and order plus byte accounting`() {
    val assembled = ReviewLaneAssembledBundle.assemble(assignment(packet(twoCommits), fullBundle), packet(twoCommits))
    val segmentation = segmentAssembledBundle(assembled, maxLaneLaunchBytes = 15) { entries -> entries.size * 10L }

    segmentation.segments.forEach { segment ->
      assertTrue(segment.measuredBytes > 0)
      segment.entries.forEach { entry ->
        assertTrue(entry.commitSha.isNotBlank())
        assertTrue(entry.orderIndex >= 0)
      }
      val accounting = segment.toAccounting()
      assertEquals(segment.segmentId, accounting.segmentId)
      assertEquals(segment.measuredBytes, accounting.measuredBytes)
      assertEquals(segment.entries.size, accounting.entryCount)
      assertEquals(segment.compositionDigest, accounting.compositionDigest)
    }
  }

  @Test fun `an entry larger than the whole budget is recorded unreviewable rather than dropped`() {
    val assembled =
      ReviewLaneAssembledBundle.assemble(
        assignment(
          packet(listOf(unit("c1", "base", 0, listOf(hunkA)))),
          ReviewLaneBundle(listOf(ReviewLaneBundleEntry("c1", 0, listOf(hunkA.hunkId)))),
        ),
        packet(listOf(unit("c1", "base", 0, listOf(hunkA)))),
      )
    val segmentation = segmentAssembledBundle(assembled, maxLaneLaunchBytes = 5) { _ -> 10L }

    assertTrue(segmentation.segments.isEmpty())
    assertEquals(listOf(hunkA.hunkId), segmentation.unreviewableEntries.map { it.hunkId })
    assertEquals(listOf(ReviewLaneBundleSegmentation.UNREVIEWABLE_SEGMENT_ID), segmentation.unreviewedSegmentIds)
  }

  @Test fun `an incomplete completion state names the concrete units it left unreviewed`() {
    val built = packet(listOf(unit("c1", "base", 0, listOf(hunkA))))
    val assembled =
      ReviewLaneAssembledBundle.assemble(
        assignment(built, ReviewLaneBundle(listOf(ReviewLaneBundleEntry("c1", 0, listOf(hunkA.hunkId))))),
        built,
      )

    val incomplete =
      segmentAssembledBundle(assembled, maxLaneLaunchBytes = 5) { _ -> 10L }
        .toCompletionState(assembled.compositionDigest)

    assertEquals(ReviewLaneReviewDisposition.INCOMPLETE, incomplete.disposition)
    assertEquals(listOf("c1@${hunkA.path}"), incomplete.unreviewedUnits)

    val complete =
      segmentAssembledBundle(assembled, maxLaneLaunchBytes = 10_000) { _ -> 10L }
        .toCompletionState(assembled.compositionDigest)

    assertEquals(ReviewLaneReviewDisposition.COMPLETE, complete.disposition)
    assertEquals(emptyList(), complete.unreviewedUnits)
  }

  @Test fun `assembled bundle exceeding evidence budget with clean segmentation reports complete`() {
    val built = packet(listOf(unit("c1", "base", 0, listOf(hunkA, hunkB))))
    val bundle = ReviewLaneBundle(listOf(ReviewLaneBundleEntry("c1", 0, listOf(hunkA.hunkId, hunkB.hunkId))))
    val evidenceBudget = hunkA.contentBytes
    require(hunkA.contentBytes + hunkB.contentBytes > evidenceBudget)
    val governed =
      launch(
        built,
        bundle,
        ReviewContextBudgetPolicy.DEFAULT.copy(
          maxLaneEvidenceBytes = evidenceBudget,
          maxEvidenceResultBytes = evidenceBudget,
          maxLaneLaunchBytes = 10_000,
        ),
      )

    assertEquals(ReviewLaneReviewDisposition.COMPLETE, governed.completionState.disposition)
    assertEquals(emptyList(), governed.completionState.unreviewedSegmentIds)
    assertEquals(emptyList(), governed.completionState.unreviewedUnits)
    assertEquals(null, governed.completionState.budgetDimension)
  }

  @Test fun `broker evidence refusal names supplied units not bundle alphabetical tail`() {
    val firstPath = ReviewChangedHunk("src/A.kt", 1, 1, 1, 2, "+a")
    val middlePath = ReviewChangedHunk("src/M.kt", 4, 1, 4, 1, "+m")
    val latePath = ReviewChangedHunk("src/Z.kt", 7, 1, 7, 1, "+z")
    val built = packet(listOf(unit("c1", "base", 0, listOf(firstPath, middlePath, latePath))))
    val bundle =
      ReviewLaneBundle(
        listOf(ReviewLaneBundleEntry("c1", 0, listOf(firstPath.hunkId, middlePath.hunkId, latePath.hunkId))),
      )
    val assembled = ReviewLaneAssembledBundle.assemble(assignment(built, bundle), built)
    val complete =
      segmentAssembledBundle(assembled, maxLaneLaunchBytes = 10_000) { _ -> 10L }
        .toCompletionState(assembled.compositionDigest)
    val brokerDenied = "c1@src/M.kt"
    val result = complete.withBrokerEvidenceRefusal(listOf(brokerDenied))

    assertEquals(ReviewLaneReviewDisposition.INCOMPLETE, result.disposition)
    assertEquals(LANE_EVIDENCE_BYTES_DIMENSION, result.budgetDimension)
    assertEquals(listOf(brokerDenied), result.unreviewedUnits)
    assertFalse(result.unreviewedUnits.contains("c1@src/Z.kt"))
    assertFalse(result.unreviewedSegmentIds.contains(ReviewLaneBundleSegmentation.UNREVIEWABLE_SEGMENT_ID))
  }

  @Test fun `withBrokerEvidenceRefusal on a complete state yields incomplete with broker denied units`() {
    val built = packet(listOf(unit("c1", "base", 0, listOf(hunkA))))
    val assembled =
      ReviewLaneAssembledBundle.assemble(
        assignment(built, ReviewLaneBundle(listOf(ReviewLaneBundleEntry("c1", 0, listOf(hunkA.hunkId))))),
        built,
      )
    val complete =
      segmentAssembledBundle(assembled, maxLaneLaunchBytes = 10_000) { _ -> 10L }
        .toCompletionState(assembled.compositionDigest)
    val denied = listOf("c1@${hunkA.path}")

    val result = complete.withBrokerEvidenceRefusal(denied)

    assertEquals(ReviewLaneReviewDisposition.INCOMPLETE, result.disposition)
    assertEquals(LANE_EVIDENCE_BYTES_DIMENSION, result.budgetDimension)
    assertEquals(denied, result.unreviewedUnits)
    assertFalse(result.unreviewedSegmentIds.contains(ReviewLaneBundleSegmentation.UNREVIEWABLE_SEGMENT_ID))
  }

  @Test fun `a failed lane run downgrades a complete state to incomplete naming its whole bundle`() {
    val built = packet(listOf(unit("c1", "base", 0, listOf(hunkA))))
    val assembled =
      ReviewLaneAssembledBundle.assemble(
        assignment(built, ReviewLaneBundle(listOf(ReviewLaneBundleEntry("c1", 0, listOf(hunkA.hunkId))))),
        built,
      )
    val complete =
      segmentAssembledBundle(assembled, maxLaneLaunchBytes = 10_000) { _ -> 10L }
        .toCompletionState(assembled.compositionDigest)

    val failed = complete.asFailedLaneRun(listOf("c1@${hunkA.path}"))

    assertEquals(ReviewLaneReviewDisposition.INCOMPLETE, failed.disposition)
    assertEquals(listOf("c1@${hunkA.path}"), failed.unreviewedUnits)
    assertEquals(LANE_RUN_OUTCOME_DIMENSION, failed.budgetDimension)
    assertNotEquals(complete, failed)

    val budgetIncomplete =
      segmentAssembledBundle(assembled, maxLaneLaunchBytes = 5) { _ -> 10L }
        .toCompletionState(assembled.compositionDigest)
    assertEquals(budgetIncomplete, budgetIncomplete.asFailedLaneRun(listOf("c1@${hunkA.path}")))
  }

  @Test fun `a completion state cannot claim a disposition its unreviewed units contradict`() {
    assertFailsWith<IllegalArgumentException> {
      ReviewLaneCompletionState(
        disposition = ReviewLaneReviewDisposition.COMPLETE,
        bundleCompositionDigest = ReviewLaneAssembledBundle.EMPTY.compositionDigest,
        segments = emptyList(),
        unreviewedUnits = listOf("c1@src/A.kt"),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      ReviewLaneCompletionState(
        disposition = ReviewLaneReviewDisposition.INCOMPLETE,
        bundleCompositionDigest = ReviewLaneAssembledBundle.EMPTY.compositionDigest,
        segments = emptyList(),
        unreviewedSegmentIds = listOf(ReviewLaneBundleSegmentation.UNREVIEWABLE_SEGMENT_ID),
      )
    }
  }

  @Test fun `forbidden rediscovery includes bundled-lane anti-patterns`() {
    val payload = launch(packet(twoCommits), fullBundle).canonicalPayload
    listOf("per_commit_stepping", "worker_relevance_redecision", "aggregate_diff_restart").forEach { forbidden ->
      assertTrue(forbidden in payload, "Launch payload must forbid '$forbidden'.")
    }
    assertTrue(
      ReviewPacketConsumerContract.FORBIDDEN_REDISCOVERY.containsAll(
        listOf("per_commit_stepping", "worker_relevance_redecision", "aggregate_diff_restart"),
      ),
    )
  }

  @Test fun `canonical payload renders each segment with commit metadata and segment id`() {
    val pad = 2_000
    val firstBig = ReviewChangedHunk("src/${"A".repeat(pad)}.kt", 1, 1, 1, 1, "+a")
    val secondBig = ReviewChangedHunk("src/${"B".repeat(pad)}.kt", 1, 1, 1, 1, "+b")
    val built = packet(listOf(unit("c1", "base", 0, listOf(firstBig, secondBig))))
    val bundle = ReviewLaneBundle(listOf(ReviewLaneBundleEntry("c1", 0, listOf(firstBig.hunkId, secondBig.hunkId))))
    val twoEntryBytes = launch(built, bundle).canonicalPayload.toByteArray(Charsets.UTF_8).size.toLong()
    val splitBudget = twoEntryBytes - pad
    assertTrue(splitBudget in 1 until twoEntryBytes)
    val governed =
      launch(
        built,
        bundle,
        ReviewContextBudgetPolicy.DEFAULT.copy(maxLaneLaunchBytes = splitBudget),
      )

    assertTrue(governed.segmentation.segments.size >= 2, "Fixture must force multiple segments.")
    val payload = governed.canonicalPayload
    governed.segmentation.segments.forEach { segment ->
      assertTrue("segment_id: ${segment.segmentId}" in payload)
      segment.entries.forEach { entry ->
        assertTrue("commit_sha: \"${entry.commitSha}\"" in payload)
        assertTrue("order_index: ${entry.orderIndex}" in payload)
      }
    }
  }

  @Test fun `an oversized body is not rendered in the canonical launch payload`() {
    val oversized = ReviewChangedHunk("src/Huge.kt", 1, 1, 1, 1, "+" + "z".repeat(4_000))
    val built = packet(listOf(unit("c1", "base", 0, listOf(hunkA, oversized))))
    val bundle = ReviewLaneBundle(listOf(ReviewLaneBundleEntry("c1", 0, listOf(hunkA.hunkId, oversized.hunkId))))
    val governed = launch(built, bundle)

    assertEquals(setOf(hunkA.hunkId, oversized.hunkId), governed.deliveredEntries.map { it.hunkId }.toSet())
    val payload = governed.canonicalPayload
    assertTrue(oversized.hunkId in payload)
    assertTrue(oversized.content !in payload)
    assertTrue(hunkA.content !in payload)
  }

  @Test fun `a launch whose fixed overhead alone exceeds the lane budget yields a typed breach`() {
    val built = packet(twoCommits)
    val governed = launch(built, fullBundle, ReviewContextBudgetPolicy.DEFAULT.copy(maxLaneLaunchBytes = 1))

    val outcome = governed.budgetOutcomeOrNull()
    assertEquals("lane_launch_bytes", outcome?.budgetKind?.wireValue)
    assertTrue((outcome?.observedValue ?: 0) > 1)
  }

  @Test fun `segmentation rejects duplicate hunk claims`() {
    val assembled = ReviewLaneAssembledBundle.assemble(assignment(packet(twoCommits), fullBundle), packet(twoCommits))
    assertFailsWith<IllegalArgumentException> {
      ReviewLaneBundleSegmentation(
        segments =
          listOf(
            ReviewLaneBundleSegment("seg-000", assembled.entries.take(1), measuredBytes = 1),
            ReviewLaneBundleSegment("seg-001", assembled.entries, measuredBytes = 2),
          ),
        budgetLimitBytes = 100,
      )
    }
  }
}
