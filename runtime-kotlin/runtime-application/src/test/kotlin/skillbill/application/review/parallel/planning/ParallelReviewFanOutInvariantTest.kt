package skillbill.application.review.parallel.planning

import skillbill.application.review.model.ReviewPreparationRequest
import skillbill.application.review.model.ReviewPreparationResult
import skillbill.application.review.preparation.ReviewPreparationService
import skillbill.application.review.preparation.model.ReviewLaneSelection
import skillbill.application.review.preparation.model.ReviewPreparationFacts
import skillbill.application.review.preparation.model.ReviewScopeFacts
import skillbill.application.review.preparation.model.ReviewStackRoutingFacts
import skillbill.error.shellcontent.InvalidReviewContextSchemaError
import skillbill.ports.review.ReviewContextEnvelopeValidator
import skillbill.review.context.ReviewContextWireMap
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
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
import skillbill.review.context.model.packet.ReviewLaneAssembledBundle
import skillbill.review.context.model.packet.segmentAssembledBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParallelReviewFanOutInvariantTest {
  private val hunkTemplate = ReviewChangedHunk("src/A.kt", 1, 1, 1, 2, "+alpha")

  private fun decision(
    lane: String,
    paths: List<String>,
  ) = ReviewLaneDecision(
    lane = lane,
    included = true,
    reason = "routed",
    ownedPaths = paths,
    originLayerChains = listOf(listOf("kotlin")),
    owningPack = "kotlin",
    specialistSkillName = "bill-kotlin-code-review-$lane",
  )

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

  private fun service(
    scope: ReviewScopeFacts,
    decisions: List<ReviewLaneDecision>,
  ): ReviewPreparationService {
    return ReviewPreparationService(
      ReviewPreparationFacts(
        scope = scope,
        stackRouting = ReviewStackRoutingFacts("kotlin", "kotlin", emptyList(), listOf("kotlin")),
        laneSelection =
          ReviewLaneSelection(
            decisions,
            focusedMatrix(scope, decisions.filter { it.included }.map { it.lane }),
          ),
      ),
      object : ReviewContextEnvelopeValidator {
        override fun validate(
          envelope: ReviewContextWireMap,
          sourceLabel: String,
        ) = Unit
      },
    )
  }

  private fun scopeWithCommitCount(count: Int): ReviewScopeFacts {
    val units =
      (0 until count).map { index ->
        val sha = if (index == count - 1) "head" else "c$index"
        val parent =
          if (index == 0) {
            "base"
          } else if (index == count - 1 && count > 1) {
            "c${index - 1}"
          } else {
            "c${index - 1}"
          }
        val hunk = hunkTemplate.copy(path = "src/File$index.kt", content = "+line-$index")
        ReviewCommitUnit(sha, parent, "commit $sha", index, listOf(hunk), ReviewCommitSource.COMMIT_RANGE)
      }
    return ReviewScopeFacts(
      "acme/repo",
      "base",
      "head",
      "clean",
      units.flatMap { it.hunks },
      units,
      ReviewCommitCoverageFact("base", "head", count, chainVerified = true, pathCoverageVerified = true),
    )
  }

  private fun prepare(count: Int): ReviewPreparationResult {
    val scope = scopeWithCommitCount(count)
    val paths = scope.changedHunks.map { it.path }.distinct().sorted()
    val decisions = listOf(decision("security", paths), decision("testing", paths))
    return service(scope, decisions).prepare(
      ReviewPreparationRequest(
        reviewId = "review",
        reviewRevision = ReviewRevision("rvs", 1),
        criteriaReferences = emptyMap(),
      ),
    )
  }

  @Test fun `varying commit counts keep launch count equal to selected lane count`() {
    val expectedLaneCount = 2
    listOf(1, 5, 20).forEach { commitCount ->
      val prepared = prepare(commitCount)
      assertEquals(expectedLaneCount, prepared.packet.selectedLanes.size)
      assertEquals(expectedLaneCount, prepared.assignments.size)
    }
  }

  @Test fun `a multi-segment lane still produces exactly one assignment launch`() {
    val prepared = prepare(3)
    val security = prepared.assignments.single { it.lane == "security" }
    val assembled = ReviewLaneAssembledBundle.assemble(security, prepared.packet)
    val segmentation = segmentAssembledBundle(assembled, maxLaneLaunchBytes = 25) { entries -> entries.size * 10L }
    assertTrue(segmentation.segments.size >= 2, "Fixture must force segmentation without multiplying launches.")
    assertEquals(1, prepared.assignments.count { it.lane == "security" })
    assertEquals(prepared.packet.selectedLanes.size, prepared.assignments.size)

    GovernedReviewLaunch(
      security,
      prepared.packet,
      "contract",
      "rubric",
      "broker",
      ReviewContextBudgetPolicy.DEFAULT,
    )
  }

  @Test fun `synthesized launch set larger than selected lane count is rejected loudly`() {
    val prepared = prepare(2)
    val source = prepared.assignments.first()
    val extra =
      source.copy(
        lane = "forged-lane",
        laneRouting = source.laneRouting.map { it.copy(lane = "forged-lane") },
        laneDecision =
          source.laneDecision.copy(
            lane = "forged-lane",
            specialistSkillName = "bill-kotlin-code-review-forged-lane",
          ),
      )
    val failure =
      assertFailsWith<InvalidReviewContextSchemaError> {
        service(scopeWithCommitCount(2), prepared.packet.laneDecisions).validateAgainstPacket(
          prepared.packet,
          prepared.assignments + extra,
        )
      }
    assertTrue(
      "synthesized ${prepared.assignments.size + 1} assignment" in failure.message.orEmpty() ||
        "must cover exactly the packet's selected lanes" in failure.message.orEmpty(),
      failure.message.orEmpty(),
    )
  }
}
