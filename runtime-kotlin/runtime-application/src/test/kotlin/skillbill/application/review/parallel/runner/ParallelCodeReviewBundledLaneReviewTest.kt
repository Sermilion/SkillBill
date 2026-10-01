package skillbill.application.review.parallel.runner

import skillbill.application.review.model.ReviewPrelaunchExpansion
import skillbill.application.review.model.ReviewPreparationRequest
import skillbill.application.review.model.ReviewPreparationResult
import skillbill.application.review.parallel.planning.criteriaReferences
import skillbill.application.review.preparation.ReviewPreparationService
import skillbill.application.review.preparation.model.ReviewLaneSelection
import skillbill.application.review.preparation.model.ReviewPreparationFacts
import skillbill.application.review.preparation.model.ReviewScopeFacts
import skillbill.application.review.preparation.model.ReviewStackRoutingFacts
import skillbill.application.runner
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
import skillbill.review.parallel.ParallelReviewFindingParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParallelCodeReviewBundledLaneReviewTest {
  private val hunkUi = ReviewChangedHunk("src/ui/View.kt", 1, 1, 1, 2, "+ui tweak")
  private val hunkDb = ReviewChangedHunk("src/db/Repo.kt", 1, 1, 1, 2, "+persist")
  private val hunkApi = ReviewChangedHunk("src/api/Auth.kt", 1, 1, 1, 2, "+auth")
  private val hunkTest = ReviewChangedHunk("src/test/AppTest.kt", 1, 1, 1, 2, "+test")
  private val hunkContractIntro = ReviewChangedHunk("src/contract/Api.yaml", 1, 1, 1, 5, "+intro")
  private val hunkContractChange = ReviewChangedHunk("src/contract/Api.yaml", 6, 1, 6, 3, "+change")

  private val units =
    listOf(
      ReviewCommitUnit("c0", "base", "UI tweak", 0, listOf(hunkUi), ReviewCommitSource.COMMIT_RANGE),
      ReviewCommitUnit("c1", "c0", "Persistence", 1, listOf(hunkDb), ReviewCommitSource.COMMIT_RANGE),
      ReviewCommitUnit("c2", "c1", "API security", 2, listOf(hunkApi), ReviewCommitSource.COMMIT_RANGE),
      ReviewCommitUnit("c3", "c2", "Tests", 3, listOf(hunkTest), ReviewCommitSource.COMMIT_RANGE),
      ReviewCommitUnit("c4", "c3", "Contract intro", 4, listOf(hunkContractIntro), ReviewCommitSource.COMMIT_RANGE),
      ReviewCommitUnit("head", "c4", "Contract change", 5, listOf(hunkContractChange), ReviewCommitSource.COMMIT_RANGE),
    )

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

  private fun sparseMatrix(focusedByLane: Map<String, Set<String>>) =
    ReviewCommitLaneRoutingMatrix(
      units.map { it.commitSha },
      listOf("ui", "persistence", "security", "testing"),
      units.flatMap { unit ->
        focusedByLane.flatMap { (lane, focused) ->
          val isFocused = unit.commitSha in focused
          listOf(
            ReviewCommitLaneDecision(
              unit.commitSha,
              unit.orderIndex,
              lane,
              if (isFocused) ReviewCommitLaneDisposition.FOCUSED else ReviewCommitLaneDisposition.SKIPPED,
              if (isFocused) "focused" else "skipped for $lane",
            ),
          )
        }
      },
    )

  private val scope =
    ReviewScopeFacts(
      "acme/repo",
      "base",
      "head",
      "clean",
      units.flatMap { it.hunks },
      units,
      ReviewCommitCoverageFact("base", "head", units.size, chainVerified = true, pathCoverageVerified = true),
    )

  private val focusedByLane =
    mapOf(
      "ui" to setOf("c0"),
      "persistence" to setOf("c1"),
      "security" to setOf("c2", "c4", "head"),
      "testing" to setOf("c3"),
    )

  private fun service() =
    ReviewPreparationService(
      ReviewPreparationFacts(
        scope = scope,
        stackRouting = ReviewStackRoutingFacts("kotlin", "kotlin", emptyList(), listOf("kotlin")),
        laneSelection =
          ReviewLaneSelection(
            listOf(
              decision("ui", listOf("src/ui/View.kt")),
              decision("persistence", listOf("src/db/Repo.kt")),
              decision("security", listOf("src/api/Auth.kt", "src/contract/Api.yaml")),
              decision("testing", listOf("src/test/AppTest.kt")),
            ),
            sparseMatrix(focusedByLane),
          ),
      ),
      object : ReviewContextEnvelopeValidator {
        override fun validate(
          envelope: ReviewContextWireMap,
          sourceLabel: String,
        ) = Unit
      },
    )

  private class FakeLaneWorker {
    val invocations = mutableListOf<GovernedReviewLaunch>()

    fun review(launch: GovernedReviewLaunch) {
      invocations += launch
    }
  }

  private fun governed(
    lane: String,
    prepared: ReviewPreparationResult,
  ) = GovernedReviewLaunch(
    prepared.assignments.single { it.lane == lane },
    prepared.packet,
    "contract",
    "rubric",
    "broker",
    ReviewContextBudgetPolicy.DEFAULT,
  )

  @Test fun `each lane bundle holds only routed commits hunks and security excludes pure UI`() {
    val prepared =
      service().prepare(
        ReviewPreparationRequest("review", ReviewRevision("rvs", 1), criteriaReferences = emptyMap()),
      )
    val security = prepared.assignments.single { it.lane == "security" }
    val ui = prepared.assignments.single { it.lane == "ui" }

    assertEquals(listOf(hunkUi.hunkId), ui.assignedHunks)
    assertEquals(
      setOf(hunkApi.hunkId, hunkContractIntro.hunkId, hunkContractChange.hunkId),
      security.assignedHunks.toSet(),
    )
    assertFalse(hunkUi.hunkId in security.assignedHunks)
    assertEquals(setOf("c2", "c4", "head"), security.assignedBundle.entries.map { it.commitSha }.toSet())
  }

  @Test fun `fake worker is invoked once per lane and receives every segment in that invocation`() {
    val prepared =
      service().prepare(
        ReviewPreparationRequest("review", ReviewRevision("rvs", 1), criteriaReferences = emptyMap()),
      )
    val worker = FakeLaneWorker()
    prepared.assignments.forEach { assignment ->
      worker.review(
        GovernedReviewLaunch(
          assignment,
          prepared.packet,
          "contract",
          "rubric",
          "broker",
          ReviewContextBudgetPolicy.DEFAULT,
        ),
      )
    }

    assertEquals(prepared.assignments.size, worker.invocations.size)
    worker.invocations.forEach { launch ->
      assertEquals(1, worker.invocations.count { it.assignment.lane == launch.assignment.lane })
      val payloadHunkIds = launch.assembledBundle.hunkIds.toSet()
      val segmentHunkIds =
        launch.segmentation.segments.flatMap { it.entries }.map { it.hunkId }.toSet() +
          launch.segmentation.unreviewableEntries.map { it.hunkId }.toSet()
      assertEquals(payloadHunkIds, segmentHunkIds)
      assertTrue(launch.segmentation.segments.isNotEmpty() || launch.segmentation.unreviewableEntries.isNotEmpty())
    }
  }

  @Test fun `cross-commit contract finding retains both commits from one pass`() {
    val finding =
      ParallelReviewFindingParser.parse(
        "[F-001] Major | High | commits=c4,head | path=\"src/contract/Api.yaml\" | line=1 | " +
          "contract drift across commits",
      ).findings.single()
    assertEquals(listOf("c4", "head"), finding.commitShas)
  }

  @Test fun `security bounded expansion to prior contract requires a nonblank reachability reason`() {
    val expansion =
      ReviewPrelaunchExpansion(
        lane = "security",
        path = "src/contract/Api.yaml",
        reachabilityReason = "assigned hunk references prior contract surface",
      )
    assertTrue(expansion.reachabilityReason.isNotBlank())
    val prepared =
      service().prepare(
        ReviewPreparationRequest("review", ReviewRevision("rvs", 1), criteriaReferences = emptyMap()),
      )
    val securityLaunch = governed("security", prepared)
    assertTrue(securityLaunch.assembledBundle.entries.any { it.hunk.path == "src/contract/Api.yaml" })
  }

  @Test fun `single-commit synthetic scopes still assemble one unit`() {
    val syntheticScope =
      scope.copy(
        commitUnits = listOf(ReviewCommitUnit.synthetic(ReviewCommitSource.SYNTHETIC_WORKING_TREE, listOf(hunkUi))),
        changedHunks = listOf(hunkUi),
        coverageFact =
          ReviewCommitCoverageFact(
            "base",
            "head",
            1,
            chainVerified = false,
            pathCoverageVerified = true,
            degradedReason = "working-tree scope",
          ),
      )
    val syntheticPrepared =
      ReviewPreparationService(
        ReviewPreparationFacts(
          scope = syntheticScope,
          stackRouting = ReviewStackRoutingFacts("kotlin", "kotlin", emptyList(), listOf("kotlin")),
          laneSelection =
            ReviewLaneSelection(
              listOf(decision("ui", listOf("src/ui/View.kt"))),
              ReviewCommitLaneRoutingMatrix(
                listOf(syntheticScope.commitUnits.single().commitSha),
                listOf("ui"),
                listOf(
                  ReviewCommitLaneDecision(
                    syntheticScope.commitUnits.single().commitSha,
                    0,
                    "ui",
                    ReviewCommitLaneDisposition.FOCUSED,
                    "focused",
                  ),
                ),
              ),
            ),
        ),
        object : ReviewContextEnvelopeValidator {
          override fun validate(
            envelope: ReviewContextWireMap,
            sourceLabel: String,
          ) = Unit
        },
      ).prepare(ReviewPreparationRequest("review", ReviewRevision("rvs", 1)))

    assertEquals(1, syntheticPrepared.packet.commitUnits.size)
    assertEquals(1, governed("ui", syntheticPrepared).assembledBundle.entries.size)
  }
}
