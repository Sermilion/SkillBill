package skillbill.application.review.parallel.planning

import skillbill.application.review.snapshot.ReviewHarnessConfig
import skillbill.application.review.snapshot.ReviewRecorder
import skillbill.application.review.snapshot.diffForChanges
import skillbill.application.review.snapshot.diffForPaths
import skillbill.application.review.snapshot.harnessRequest
import skillbill.application.review.snapshot.reviewHarness
import skillbill.application.review.snapshot.reviewLayer
import skillbill.application.review.snapshot.reviewPack
import skillbill.application.review.snapshot.reviewed
import skillbill.review.plan.ReviewLaunchPlanPolicy
import skillbill.scaffold.model.PlatformManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParallelReviewComposedAreaPlanTest {
  private val genericAreas = listOf("performance", "ux-accessibility")
  private val kotlinAreas = listOf("architecture", "security", "testing")
  private val kmpAreas = listOf("platform-correctness", "ui")

  private val generic = reviewPack("generic", genericAreas, routingSignals = listOf("*.py"))
  private val kotlin = reviewPack("kotlin", kotlinAreas, routingSignals = listOf("*.kt"))
  private val kmp =
    reviewPack(
      "kmp",
      kmpAreas,
      layers = listOf(reviewLayer("kotlin")),
      routingSignals = listOf("commonMain"),
      contentSignals = listOf("expect", "actual"),
    )
  private val manifests = listOf(generic, kotlin, kmp)

  @Test fun `a routed root plans only the areas its own composition closure declares`() {
    assertEquals(
      kotlinAreas.toSet(),
      plannedAreas(kotlinDiff()),
      "The kotlin root's plan must not carry areas only an unrouted pack declares.",
    )
    assertEquals(
      (kmpAreas + kotlinAreas).toSet(),
      plannedAreas(kmpDiff()),
      "The kmp root's plan is its own areas plus its composed kotlin baseline, and nothing else.",
    )
  }

  @Test fun `launch and attribution pin the same composed area set for a routed pack`() {
    assertEquals(
      composedPlanAreas("kotlin"),
      plannedAreas(kotlinDiff()),
      "Parity pin: a pack with no composed baseline layer resolves one area set on both sides.",
    )
    assertEquals(
      composedPlanAreas("kmp"),
      plannedAreas(kmpDiff()),
      "Parity pin: a pack composing a baseline layer resolves one area set on both sides.",
    )
  }

  @Test fun `a routed root declaring no code review area fails loudly instead of planning no lane`() {
    val bare = reviewPack("bare", emptyList(), routingSignals = listOf("*.kt"))
    val failure =
      assertFailsWith<IllegalArgumentException> {
        run(listOf(bare), kotlinDiff())
      }

    assertTrue(
      "bare" in failure.message.orEmpty(),
      "The empty-composition failure must name the routed pack: ${failure.message}",
    )
  }

  private fun composedPlanAreas(slug: String): Set<String> =
    ReviewLaunchPlanPolicy
      .flatten(slug, manifests, ReviewLaunchPlanPolicy.composedAreas(slug, manifests))
      .lanes.map { it.area }.toSet()

  private fun kotlinDiff() = diffForPaths("src/Repo.kt")

  private fun kmpDiff() =
    diffForChanges(
      "src/commonMain/kotlin/App.kt" to "expect fun platformName(): String",
      "src/main/kotlin/App.kt" to "actual fun platformName(): String = \"jvm\"",
    )

  private fun plannedAreas(diff: String): Set<String> {
    val recorder = run(manifests, diff)
    return recorder.durableLanes.map { it.area }.toSet()
  }

  private fun run(
    packs: List<PlatformManifest>,
    diff: String,
  ): ReviewRecorder {
    val recorder = ReviewRecorder()
    reviewHarness(ReviewHarnessConfig(manifests = packs, diff = diff), recorder)
      .reviewed(harnessRequest(reviewRunId = "composed-area-plan"))
    return recorder
  }
}
