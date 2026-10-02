package skillbill.application.review.parallel.planning

import skillbill.application.review.snapshot.ReviewHarnessConfig
import skillbill.application.review.snapshot.ReviewRecorder
import skillbill.application.review.snapshot.diffForChanges
import skillbill.application.review.snapshot.harnessRequest
import skillbill.application.review.snapshot.reviewHarness
import skillbill.application.review.snapshot.reviewLayer
import skillbill.application.review.snapshot.reviewPack
import skillbill.application.review.snapshot.reviewed
import skillbill.error.shellcontent.AmbiguousLaneOwnershipError
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.review.plan.ReviewLaunchPlanPolicy
import skillbill.scaffold.model.PlatformManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParallelReviewCrossRootLanePlanTest {
  private val kotlinAreas = listOf("architecture", "security", "testing")
  private val kmpAreas = listOf("architecture", "platform-correctness")

  private val kotlin = reviewPack("kotlin", kotlinAreas, routingSignals = listOf("*.kt"))
  private val kmp =
    reviewPack(
      "kmp",
      kmpAreas,
      layers = listOf(reviewLayer("kotlin")),
      routingSignals = listOf("commonMain"),
      contentSignals = listOf("expect"),
    )
  private val manifests = listOf(kotlin, kmp)

  private val crossRootDiff =
    diffForChanges(
      "src/commonMain/kotlin/App.kt" to "expect fun platformName(): String",
      "src/main/kotlin/Repo.kt" to "class Repo",
    )

  @Test fun `a cross-root plan carries one lane per area owned by the nearest composing pack`() {
    val lanes = run(manifests, crossRootDiff).durableLanes

    assertEquals(
      lanes.map { it.area }.distinct().size,
      lanes.size,
      "A routed root brought along as a composition baseline must not add a second lane for an area.",
    )
    assertEquals(
      ReviewLaunchPlanPolicy
        .flatten("kmp", manifests, ReviewLaunchPlanPolicy.composedAreas("kmp", manifests))
        .lanes.associate { it.area to it.packSlug },
      lanes.associate { it.area to it.packSlug },
      "The composing root's own flattened plan is the oracle for cross-root area ownership.",
    )
    assertEquals(listOf(0, 1, 2, 3), lanes.sortedBy { it.orderIndex }.map { it.orderIndex })
  }

  @Test fun `dropping a cross-root duplicate leaves area coverage and lane accounting untouched`() {
    val withDuplicate = run(manifests, crossRootDiff)
    val kotlinWithoutOverlap = kotlin.copy(declaredCodeReviewAreas = listOf("security", "testing"))
    val withoutDuplicate = run(listOf(kotlinWithoutOverlap, kmp), crossRootDiff)

    assertEquals(
      (kotlinAreas + kmpAreas).toSet(),
      withDuplicate.durableLanes.map { it.area }.toSet(),
      "Reconciliation removes a duplicate lane, never the last lane for an area.",
    )
    assertEquals(
      withoutDuplicate.durableLanes.associate { it.area to it.reviewDisposition.wireValue },
      withDuplicate.durableLanes.associate { it.area to it.reviewDisposition.wireValue },
      "A lane dropped as a cross-root duplicate must not reduce any lane disposition.",
    )
    assertTrue(
      withDuplicate.durableLanes.all { it.unreviewedSegmentIds.isEmpty() },
      "A dropped duplicate is not unreviewed coverage.",
    )
    assertEquals(
      assertNotNull(withoutDuplicate.durableIntegrationPass).terminalOutcome,
      assertNotNull(withDuplicate.durableIntegrationPass).terminalOutcome,
    )
  }

  @Test fun `two non-composing routed packs tying on one area abort planning instead of launching both lanes`() {
    val swift = reviewPack("swift", listOf("architecture", "ui"), routingSignals = listOf("*.swift"))
    val recorder = ReviewRecorder()
    val diff =
      diffForChanges(
        "src/main/kotlin/Repo.kt" to "class Repo",
        "Sources/App/View.swift" to "struct View",
      )

    val error =
      assertFailsWith<AmbiguousLaneOwnershipError> {
        reviewHarness(ReviewHarnessConfig(manifests = listOf(kotlin, swift), diff = diff), recorder)
          .reviewed(
            harnessRequest(reviewRunId = "cross-root-ambiguous", codeReviewMode = CodeReviewExecutionMode.DELEGATED),
          )
      }

    assertTrue(
      listOf("architecture", "kotlin", "swift").all { it in error.message.orEmpty() },
      "The typed error must name the contested area and both owning packs: \${error.message}",
    )
    assertTrue(
      recorder.durableLanes.isEmpty() && recorder.durableIntegrationPass == null,
      "An unresolvable cross-root tie must leave no durable lanes and no integration pass.",
    )
  }

  private fun run(
    packs: List<PlatformManifest>,
    diff: String,
  ): ReviewRecorder {
    val recorder = ReviewRecorder()
    reviewHarness(ReviewHarnessConfig(manifests = packs, diff = diff), recorder)
      .reviewed(
        harnessRequest(reviewRunId = "cross-root-lane-plan", codeReviewMode = CodeReviewExecutionMode.DELEGATED),
      )
    return recorder
  }
}
