package skillbill.application.review.preparation

import skillbill.application.review.model.ReviewPreparationRequest
import skillbill.application.review.parallel.planning.criteriaReferences
import skillbill.application.review.preparation.model.ReviewLaneSelection
import skillbill.application.review.preparation.model.ReviewPreparationFacts
import skillbill.application.review.preparation.model.ReviewScopeFacts
import skillbill.application.review.preparation.model.ReviewStackRoutingFacts
import skillbill.error.shellcontent.InvalidReviewContextSchemaError
import skillbill.ports.review.ReviewContextEnvelopeValidator
import skillbill.review.context.ReviewContextWireMap
import skillbill.review.context.model.bundle.ReviewLaneBundle
import skillbill.review.context.model.bundle.ReviewLaneBundleEntry
import skillbill.review.context.model.commit.ReviewCommitCoverageFact
import skillbill.review.context.model.commit.ReviewCommitLaneDecision
import skillbill.review.context.model.commit.ReviewCommitLaneDisposition
import skillbill.review.context.model.commit.ReviewCommitLaneRoutingMatrix
import skillbill.review.context.model.commit.ReviewCommitSource
import skillbill.review.context.model.commit.ReviewCommitUnit
import skillbill.review.context.model.commit.ReviewLaneDecision
import skillbill.review.context.model.hunk.ReviewChangedHunk
import skillbill.review.context.model.hunk.ReviewRevision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun focusedMatrix(
  scope: ReviewScopeFacts,
  lanes: List<String>,
) = ReviewCommitLaneRoutingMatrix(
  scope.commitUnits.sortedBy { it.orderIndex }.map { it.commitSha },
  lanes,
  scope.commitUnits.sortedBy { it.orderIndex }.flatMap { unit ->
    lanes.map {
      ReviewCommitLaneDecision(unit.commitSha, unit.orderIndex, it, ReviewCommitLaneDisposition.FOCUSED, "focused")
    }
  },
)

class ReviewPreparationCommitBundleTest {
  private val hunkA = ReviewChangedHunk("src/A.kt", 1, 1, 1, 2, "+alpha")
  private val hunkB = ReviewChangedHunk("src/B.kt", 4, 1, 4, 1, "+beta")

  private fun decision(
    lane: String,
    path: String,
  ) = ReviewLaneDecision(
    lane = lane,
    included = true,
    reason = "routed",
    ownedPaths = listOf(path),
    originLayerChains = listOf(listOf("kotlin")),
    owningPack = "kotlin",
    specialistSkillName = "bill-kotlin-code-review-$lane",
  )

  private fun service(
    scope: ReviewScopeFacts,
    decisions: List<ReviewLaneDecision>,
  ): ReviewPreparationService =
    serviceWith(scope, decisions, focusedMatrix(scope, decisions.filter { it.included }.map { it.lane }))

  private fun serviceWith(
    scope: ReviewScopeFacts,
    decisions: List<ReviewLaneDecision>,
    matrix: ReviewCommitLaneRoutingMatrix,
  ): ReviewPreparationService {
    return ReviewPreparationService(
      ReviewPreparationFacts(
        scope = scope,
        stackRouting = ReviewStackRoutingFacts("kotlin", "kotlin", emptyList(), listOf("kotlin")),
        laneSelection = ReviewLaneSelection(decisions, matrix),
      ),
      object : ReviewContextEnvelopeValidator {
        override fun validate(
          envelope: ReviewContextWireMap,
          sourceLabel: String,
        ) = Unit
      },
    )
  }

  private fun request() =
    ReviewPreparationRequest(
      reviewId = "review",
      reviewRevision = ReviewRevision("rvs", 1),
      criteriaReferences = emptyMap(),
    )

  private val multiCommitScope =
    ReviewScopeFacts(
      "acme/repo",
      "base",
      "head",
      "clean",
      listOf(hunkA, hunkB),
      listOf(
        ReviewCommitUnit("c1", "base", "first", 0, listOf(hunkA), ReviewCommitSource.COMMIT_RANGE),
        ReviewCommitUnit("head", "c1", "second", 1, listOf(hunkB), ReviewCommitSource.COMMIT_RANGE),
      ),
      ReviewCommitCoverageFact("base", "head", 2, chainVerified = true, pathCoverageVerified = true),
    )

  @Test fun `a multi-commit packet carries ordered units and per-lane bundles`() {
    val result =
      service(
        multiCommitScope,
        listOf(decision("security", "src/A.kt"), decision("testing", "src/B.kt")),
      ).prepare(request())

    assertEquals(listOf("c1", "head"), result.packet.commitUnits.sortedBy { it.orderIndex }.map { it.commitSha })
    val security = result.assignments.single { it.lane == "security" }
    assertEquals(
      ReviewLaneBundle(listOf(ReviewLaneBundleEntry("c1", 0, listOf(hunkA.hunkId)))),
      security.assignedBundle,
    )
    val testing = result.assignments.single { it.lane == "testing" }
    assertEquals(
      ReviewLaneBundle(listOf(ReviewLaneBundleEntry("head", 1, listOf(hunkB.hunkId)))),
      testing.assignedBundle,
    )
    assertTrue(result.packet.coverageFact.chainVerified)
  }

  @Test fun `a staged-scope packet carries exactly one synthetic unit`() {
    val scope =
      ReviewScopeFacts(
        "acme/repo",
        "base",
        "head",
        "staged",
        listOf(hunkA, hunkB),
        listOf(ReviewCommitUnit.synthetic(ReviewCommitSource.SYNTHETIC_WORKING_TREE, listOf(hunkA, hunkB))),
        ReviewCommitCoverageFact(
          "base",
          "head",
          1,
          chainVerified = false,
          pathCoverageVerified = true,
          degradedReason = "staged scope",
        ),
      )
    val result =
      service(scope, listOf(decision("security", "src/A.kt"), decision("testing", "src/B.kt")))
        .prepare(request())

    assertEquals(1, result.packet.commitUnits.size)
    assertEquals(ReviewCommitSource.SYNTHETIC_WORKING_TREE, result.packet.commitUnits.single().source)
    assertEquals(listOf("src/A.kt"), result.assignments.single { it.lane == "security" }.assignedPaths)
    assertEquals(
      listOf(hunkA.hunkId),
      result.assignments.single { it.lane == "security" }.assignedBundle.hunkIds,
    )
  }

  private fun sparseService(
    scope: ReviewScopeFacts,
    decisions: List<ReviewLaneDecision>,
    focusedByLane: Map<String, Set<String>>,
  ): ReviewPreparationService {
    val matrix =
      ReviewCommitLaneRoutingMatrix(
        scope.commitUnits.sortedBy { it.orderIndex }.map { it.commitSha },
        decisions.filter { it.included }.map { it.lane },
        scope.commitUnits.sortedBy { it.orderIndex }.flatMap { unit ->
          decisions.filter { it.included }.map { decision ->
            val focused = unit.commitSha in focusedByLane.getValue(decision.lane)
            ReviewCommitLaneDecision(
              unit.commitSha,
              unit.orderIndex,
              decision.lane,
              if (focused) ReviewCommitLaneDisposition.FOCUSED else ReviewCommitLaneDisposition.SKIPPED,
              if (focused) "focused" else "commit ${unit.commitSha} matched no ${decision.lane} signal",
            )
          }
        },
      )
    return serviceWith(scope, decisions, matrix)
  }

  @Test fun `a lane bundle carries only its focused commits' hunks in commit order`() {
    val hunkA2 = ReviewChangedHunk("src/A.kt", 9, 1, 9, 2, "+later alpha")
    val scope =
      multiCommitScope.copy(
        changedHunks = listOf(hunkA, hunkA2),
        commitUnits =
          listOf(
            ReviewCommitUnit("c1", "base", "first", 0, listOf(hunkA), ReviewCommitSource.COMMIT_RANGE),
            ReviewCommitUnit("head", "c1", "second", 1, listOf(hunkA2), ReviewCommitSource.COMMIT_RANGE),
          ),
      )
    val decisions = listOf(decision("security", "src/A.kt"))
    val result = sparseService(scope, decisions, mapOf("security" to setOf("c1"))).prepare(request())

    val security = result.assignments.single()
    assertEquals(listOf(hunkA.hunkId), security.assignedHunks)
    assertEquals(
      ReviewLaneBundle(listOf(ReviewLaneBundleEntry("c1", 0, listOf(hunkA.hunkId)))),
      security.assignedBundle,
      "the lane received a hunk from a commit it skipped",
    )
    assertEquals(listOf("c1", "head"), security.laneRouting.map { it.commitSha })
    assertTrue(security.laneRouting.single { it.commitSha == "head" }.reason.isNotBlank())
  }

  @Test fun `validation rejects an assignment claiming a hunk from a commit skipped for its lane`() {
    val hunkA2 = ReviewChangedHunk("src/A.kt", 9, 1, 9, 2, "+later alpha")
    val scope =
      multiCommitScope.copy(
        changedHunks = listOf(hunkA, hunkA2),
        commitUnits =
          listOf(
            ReviewCommitUnit("c1", "base", "first", 0, listOf(hunkA), ReviewCommitSource.COMMIT_RANGE),
            ReviewCommitUnit("head", "c1", "second", 1, listOf(hunkA2), ReviewCommitSource.COMMIT_RANGE),
          ),
      )
    val decisions = listOf(decision("security", "src/A.kt"))
    val prepared = sparseService(scope, decisions, mapOf("security" to setOf("c1"))).prepare(request())
    val widened = prepared.assignments.map { it.copy(assignedHunks = listOf(hunkA.hunkId, hunkA2.hunkId)) }

    val error =
      assertFailsWith<InvalidReviewContextSchemaError> {
        sparseService(scope, decisions, mapOf("security" to setOf("c1")))
          .validateAgainstPacket(prepared.packet, widened)
      }
    assertTrue("skipped" in error.message.orEmpty(), error.message.orEmpty())

    val bundled =
      assertFailsWith<IllegalArgumentException> {
        prepared.assignments.single().copy(
          assignedHunks = listOf(hunkA.hunkId, hunkA2.hunkId),
          assignedBundle =
            ReviewLaneBundle(
              listOf(
                ReviewLaneBundleEntry("c1", 0, listOf(hunkA.hunkId)),
                ReviewLaneBundleEntry("head", 1, listOf(hunkA2.hunkId)),
              ),
            ),
        )
      }
    assertTrue("skipped" in bundled.message.orEmpty(), bundled.message.orEmpty())
  }

  @Test fun `flipping a disposition or a skip reason moves the packet and assignment digests`() {
    val decisions = listOf(decision("security", "src/A.kt"), decision("testing", "src/B.kt"))
    val focused = mapOf("security" to setOf("c1"), "testing" to setOf("head"))
    val base = sparseService(multiCommitScope, decisions, focused).prepare(request())
    val widened =
      sparseService(
        multiCommitScope,
        decisions,
        mapOf("security" to setOf("c1", "head"), "testing" to setOf("head")),
      ).prepare(request())

    assertTrue(base.packet.digest != widened.packet.digest, "a disposition flip left the packet digest unchanged")

    val rephrased =
      base.packet.copy(
        routingMatrix =
          base.packet.routingMatrix.copy(
            decisions =
              base.packet.routingMatrix.decisions.map {
                if (it.focused) it else it.copy(reason = "${it.reason} (restated)")
              },
          ),
      )
    assertTrue(base.packet.digest != rephrased.digest, "a skip-reason change left the packet digest unchanged")

    val securityAssignment = base.assignments.single { it.lane == "security" }
    val restated =
      securityAssignment.copy(
        laneRouting =
          securityAssignment.laneRouting.map {
            if (it.focused) it else it.copy(reason = "${it.reason} (restated)")
          },
      )
    assertTrue(
      securityAssignment.digest != restated.digest,
      "a skip-reason change left the assignment digest unchanged",
    )
  }

  @Test fun `validation rejects an assignment claiming a commit unit outside its packet`() {
    val prepared =
      service(
        multiCommitScope,
        listOf(decision("security", "src/A.kt"), decision("testing", "src/B.kt")),
      ).prepare(request())
    val forged =
      prepared.assignments.map { assignment ->
        if (assignment.lane != "security") {
          assignment
        } else {
          assignment.copy(
            assignedBundle =
              ReviewLaneBundle(
                listOf(ReviewLaneBundleEntry("not-a-packet-commit", 0, listOf(hunkA.hunkId))),
              ),
          )
        }
      }
    val failure =
      assertFailsWith<InvalidReviewContextSchemaError> {
        service(multiCommitScope, prepared.packet.laneDecisions).validateAgainstPacket(prepared.packet, forged)
      }
    assertTrue("packet does not own" in failure.message.orEmpty(), failure.message.orEmpty())
  }

  @Test fun `a shuffled commitUnits input still yields bundle entries in packet commit order`() {
    val shuffled =
      multiCommitScope.copy(
        commitUnits =
          listOf(
            ReviewCommitUnit("head", "c1", "second", 1, listOf(hunkB), ReviewCommitSource.COMMIT_RANGE),
            ReviewCommitUnit("c1", "base", "first", 0, listOf(hunkA), ReviewCommitSource.COMMIT_RANGE),
          ),
      )
    val result =
      sparseService(
        shuffled,
        listOf(decision("security", "src/A.kt"), decision("testing", "src/B.kt")),
        mapOf("security" to setOf("c1", "head"), "testing" to setOf("c1", "head")),
      ).prepare(request())

    assertEquals(listOf(0, 1), result.packet.commitUnits.sortedBy { it.orderIndex }.map { it.orderIndex })
    result.assignments.forEach { assignment ->
      assertEquals(
        assignment.assignedBundle.entries.map { it.orderIndex },
        assignment.assignedBundle.entries.map { it.orderIndex }.sorted(),
        "lane '${assignment.lane}' bundle left packet commit order",
      )
    }
  }

  @Test fun `changing an owned hunk id moves the assignment digest`() {
    val decisions = listOf(decision("security", "src/A.kt"))
    val focused = mapOf("security" to setOf("c1"))
    val prepared = sparseService(multiCommitScope, decisions, focused).prepare(request())
    val security = prepared.assignments.single()
    val retargeted =
      security.copy(
        assignedHunks = listOf(hunkB.hunkId),
        assignedBundle = ReviewLaneBundle(listOf(ReviewLaneBundleEntry("c1", 0, listOf(hunkB.hunkId)))),
      )

    assertTrue(security.digest != retargeted.digest, "an owned-hunk change left the assignment digest unchanged")
  }

  @Test fun `sparse routing preserves lane order owning pack add-ons and origin chains`() {
    val security =
      decision("security", "src/A.kt").copy(
        orderIndex = 0,
        addOns = listOf("auth-notes"),
        originLayerChains = listOf(listOf("kmp", "kotlin")),
      )
    val testing =
      decision("testing", "src/B.kt").copy(
        orderIndex = 1,
        addOns = listOf("test-notes"),
        originLayerChains = listOf(listOf("kotlin")),
      )
    val result =
      sparseService(
        multiCommitScope,
        listOf(security, testing),
        mapOf("security" to setOf("c1"), "testing" to setOf("head")),
      ).prepare(request())

    assertEquals(listOf("security", "testing"), result.packet.selectedLanes)
    assertEquals(listOf("security", "testing"), result.assignments.map { it.lane })
    assertEquals(listOf(listOf("kmp", "kotlin")), result.assignments[0].laneDecision.originLayerChains)
    assertEquals(listOf("auth-notes"), result.assignments[0].laneDecision.addOns)
    assertEquals("kotlin", result.assignments[0].laneDecision.owningPack)
    assertEquals(listOf(listOf("kotlin")), result.assignments[1].laneDecision.originLayerChains)
    assertEquals(listOf("test-notes"), result.assignments[1].laneDecision.addOns)
  }

  @Test fun `staged and unstaged synthetic scopes keep inclusion-equivalent lane ownership`() {
    listOf("staged", "unstaged").forEach { status ->
      val synthetic =
        ReviewCommitUnit.synthetic(
          ReviewCommitSource.SYNTHETIC_WORKING_TREE,
          listOf(hunkA, hunkB),
        )
      val scope =
        ReviewScopeFacts(
          "acme/repo",
          "base",
          "head",
          status,
          listOf(hunkA, hunkB),
          listOf(synthetic),
          ReviewCommitCoverageFact(
            "base",
            "head",
            1,
            chainVerified = false,
            pathCoverageVerified = true,
            degradedReason = "$status scope",
          ),
        )
      val decisions = listOf(decision("security", "src/A.kt"), decision("testing", "src/B.kt"))
      val result = service(scope, decisions).prepare(request())

      assertEquals(1, result.packet.commitUnits.size, status)
      assertTrue(result.packet.commitUnits.single().commitSha.startsWith("synthetic:"), status)
      assertEquals(listOf("src/A.kt"), result.assignments.single { it.lane == "security" }.assignedPaths, status)
      assertEquals(listOf(hunkA.hunkId), result.assignments.single { it.lane == "security" }.assignedHunks, status)
      assertEquals(listOf("src/B.kt"), result.assignments.single { it.lane == "testing" }.assignedPaths, status)
      assertEquals(listOf(hunkB.hunkId), result.assignments.single { it.lane == "testing" }.assignedHunks, status)
    }
  }
}
