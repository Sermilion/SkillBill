package skillbill.application.review.parallel.planning

import skillbill.application.review.snapshot.ReviewHarnessConfig
import skillbill.application.review.snapshot.ReviewRecorder
import skillbill.application.review.snapshot.diffForChanges
import skillbill.application.review.snapshot.harnessRequest
import skillbill.application.review.snapshot.reviewHarness
import skillbill.application.review.snapshot.reviewLayer
import skillbill.application.review.snapshot.reviewPack
import skillbill.application.review.snapshot.reviewed
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.review.plan.ReviewCrossRootLaneReconciliation
import skillbill.review.plan.ReviewLaunchPlanPolicy
import skillbill.review.plan.ReviewStackRouting
import skillbill.review.plan.model.ReviewRootLanes
import skillbill.review.plan.model.ReviewRoutingChangedFile
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.ReviewLaneCondition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParallelReviewFallbackLaneExclusionTest {
  private val genericAreas =
    listOf(
      "architecture",
      "performance",
      "platform-correctness",
      "security",
      "testing",
      "api-contracts",
      "persistence",
      "reliability",
      "ui",
      "ux-accessibility",
    )
  private val kotlinAreas = genericAreas
  private val kmpAreas =
    listOf(
      "architecture",
      "platform-correctness",
      "security",
      "persistence",
      "reliability",
      "ui",
      "ux-accessibility",
    )

  private val generic =
    reviewPack("generic", genericAreas, routingSignals = listOf("*.py"), fallback = true).copy(
      laneConditions = genericLaneConditions(),
    )
  private val kotlin =
    reviewPack("kotlin", kotlinAreas, routingSignals = listOf("*.kt")).copy(
      laneConditions = nativeLaneConditions(),
    )
  private val kmp =
    reviewPack(
      "kmp",
      kmpAreas,
      layers = listOf(reviewLayer("kotlin")),
      routingSignals = listOf("commonMain"),
      contentSignals = listOf("expect", "actual"),
    ).copy(laneConditions = nativeLaneConditions())
  private val manifests = listOf(generic, kotlin, kmp)

  private val crossStackDiff =
    diffForChanges(
      "src/commonMain/kotlin/App.kt" to "expect fun platformName(): String",
      "src/main/kotlin/Repo.kt" to "class Repo",
      "scripts/fallback_route.py" to "print('fallback')",
    )

  @Test fun `generic kotlin and kmp cross-stack plan keeps one lane per area with no generic owner`() {
    val recorder = delegatedRun(manifests, crossStackDiff)
    val lanes = recorder.durableLanes.sortedBy { it.orderIndex }

    assertEquals(lanes.map { it.area }.toSet().size, lanes.size)
    assertTrue(lanes.none { it.packSlug == "generic" })
    assertEquals(
      ReviewLaunchPlanPolicy
        .flatten("kmp", manifests, ReviewLaunchPlanPolicy.composedAreas("kmp", manifests))
        .lanes.associate { it.area to it.packSlug },
      lanes.associate { it.area to it.packSlug },
    )
    assertEquals(lanes.indices.toList(), lanes.map { it.orderIndex })
  }

  @Test fun `fallback exclusion does not shrink the distinct area set while lane count falls`() {
    val preChangeAreas = areasFromRootLanesBeforeFallbackExclusion(manifests, crossStackDiff)
    val preChangeLaneCount = laneCountFromRootLanesBeforeFallbackExclusion(manifests, crossStackDiff)
    val recorder = delegatedRun(manifests, crossStackDiff)
    val postChangeAreas = recorder.durableLanes.map { it.area }.toSet()

    assertEquals(preChangeAreas, postChangeAreas)
    assertTrue(recorder.durableLanes.size < preChangeLaneCount)
  }

  @Test fun `excluding a redundant fallback lane leaves area coverage and lane accounting untouched`() {
    val withFallback = delegatedRun(manifests, crossStackDiff)
    val withoutGeneric = delegatedRun(listOf(kotlin, kmp), crossStackDiff)

    assertEquals(
      withoutGeneric.durableLanes.map { it.area }.toSet(),
      withFallback.durableLanes.map { it.area }.toSet(),
    )
    assertEquals(
      withoutGeneric.durableLanes.associate { it.area to it.reviewDisposition.wireValue },
      withFallback.durableLanes.associate { it.area to it.reviewDisposition.wireValue },
    )
    assertTrue(withFallback.durableLanes.all { it.unreviewedSegmentIds.isEmpty() })
    assertEquals(
      assertNotNull(withoutGeneric.durableIntegrationPass).terminalOutcome,
      assertNotNull(withFallback.durableIntegrationPass).terminalOutcome,
    )
  }

  @Test fun `manifest order does not change the delegated fallback-excluded lane plan`() {
    val first = delegatedRun(manifests, crossStackDiff).durableLanes.sortedBy { it.orderIndex }
    val second = delegatedRun(listOf(kmp, generic, kotlin), crossStackDiff).durableLanes.sortedBy { it.orderIndex }

    assertEquals(
      first.map { Triple(it.area, it.packSlug, it.orderIndex) },
      second.map { Triple(it.area, it.packSlug, it.orderIndex) },
    )
  }

  private fun delegatedRun(
    packs: List<PlatformManifest>,
    diff: String,
  ): ReviewRecorder {
    val recorder = ReviewRecorder()
    reviewHarness(ReviewHarnessConfig(manifests = packs, diff = diff), recorder)
      .reviewed(
        harnessRequest(
          reviewRunId = "fallback-lane-exclusion",
          codeReviewMode = CodeReviewExecutionMode.DELEGATED,
        ),
      )
    return recorder
  }

  private fun buildRootLanes(
    packs: List<PlatformManifest>,
    diff: String,
  ) = rootLanesFromRouting(packs, diff)

  private fun areasFromRootLanesBeforeFallbackExclusion(
    packs: List<PlatformManifest>,
    diff: String,
  ): Set<String> = buildRootLanes(packs, diff).flatMap { it.lanes }.map { it.area }.toSet()

  private fun laneCountFromRootLanesBeforeFallbackExclusion(
    packs: List<PlatformManifest>,
    diff: String,
  ): Int = buildRootLanes(packs, diff).sumOf { it.lanes.size }

  private fun rootLanesFromRouting(
    packs: List<PlatformManifest>,
    diff: String,
  ): List<ReviewRootLanes> {
    val evidenceFiles =
      diff.lines()
        .filter { it.startsWith("+++ b/") }
        .map { it.removePrefix("+++ b/") }
        .distinct()
        .map { ReviewRoutingChangedFile(it, diff) }
    val routing = ReviewStackRouting.route(packs, evidenceFiles)
    val routed = packs.filter { it.slug in routing.routedSlugs }
    val depthOffsets =
      ReviewCrossRootLaneReconciliation
        .compositionDepthOffsets(routed.map { it.slug }, packs)
    val rootLanes =
      routed.map { root ->
        val rootOwnedPaths = routing.ownedPathsBySlug[root.slug].orEmpty()
        val selectedAreas = ReviewLaunchPlanPolicy.composedAreas(root.slug, packs)
        val lanes =
          ReviewLaunchPlanPolicy.flatten(root.slug, packs, selectedAreas).lanes
            .map { lane ->
              lane.copy(
                ownedPaths = rootOwnedPaths.toList().sorted(),
                changedHunkIds = emptyList(),
              )
            }
            .filter { it.ownedPaths.isNotEmpty() }
        ReviewRootLanes(depthOffsets[root.slug] ?: 0, lanes)
      }
    return rootLanes
  }

  private fun genericLaneConditions(): Map<String, ReviewLaneCondition> =
    buildMap {
      put("architecture", ReviewLaneCondition(required = true))
      put("platform-correctness", ReviewLaneCondition(required = true))
      genericAreas.filter { it !in setOf("architecture", "platform-correctness") }
        .forEach { put(it, ReviewLaneCondition(path = listOf("*"))) }
    }

  private fun nativeLaneConditions(): Map<String, ReviewLaneCondition> =
    buildMap {
      put("architecture", ReviewLaneCondition(required = true))
      put("platform-correctness", ReviewLaneCondition(required = true))
      (genericAreas + kmpAreas).distinct().filter { it !in setOf("architecture", "platform-correctness") }
        .forEach { put(it, ReviewLaneCondition(path = listOf("*"))) }
    }
}
