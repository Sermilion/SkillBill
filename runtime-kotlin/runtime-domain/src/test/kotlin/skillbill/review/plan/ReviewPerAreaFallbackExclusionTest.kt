package skillbill.review.plan

import org.junit.jupiter.api.Test
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.SkillStagingFailureCode
import skillbill.model.FileLocation
import skillbill.review.plan.model.ReviewLaunchLane
import skillbill.review.plan.model.ReviewRootLanes
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.ReviewLaneCondition
import skillbill.scaffold.model.RoutingSignals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReviewPerAreaFallbackExclusionTest {
  @Test
  fun `a fallback-only area keeps its lane when no native routed pack contributes a candidate`() {
    val generic = fallbackPack("generic", listOf("fallback-only", "shared"))
    val kotlin = nativePack("kotlin", listOf("shared"))
    val roots =
      listOf(
        root(0, lane("generic", "fallback-only", ownedPaths = listOf("orphan.py"))),
        root(0, lane("kotlin", "shared", ownedPaths = listOf("App.kt"))),
        root(0, lane("generic", "shared", ownedPaths = listOf("orphan.py"))),
      )

    val partition = ReviewPerAreaFallbackExclusion.partition(roots, listOf(generic, kotlin))

    assertEquals(listOf("fallback-only", "shared"), partition.roots.flatMap { it.lanes }.map { it.area }.sorted())
    assertEquals(emptyMap(), partition.excludedFallbackLanesByArea.filterKeys { it == "fallback-only" })
    assertTrue(
      partition.roots.any { root ->
        root.lanes.any { it.packSlug == "generic" && it.area == "fallback-only" }
      },
    )
  }

  @Test
  fun `a native candidate for an area drops the fallback candidate for that area only`() {
    val generic = fallbackPack("generic", listOf("architecture", "fallback-only"))
    val kotlin = nativePack("kotlin", listOf("architecture"))
    val roots =
      listOf(
        root(0, lane("generic", "architecture", ownedPaths = listOf("orphan.py"))),
        root(0, lane("generic", "fallback-only", ownedPaths = listOf("orphan.py"))),
        root(0, lane("kotlin", "architecture", ownedPaths = listOf("App.kt"))),
      )

    val partition = ReviewPerAreaFallbackExclusion.partition(roots, listOf(generic, kotlin))

    assertTrue(
      partition.roots.none { root ->
        root.lanes.any { lane -> lane.packSlug == "generic" && lane.area == "architecture" }
      },
    )
    assertEquals(listOf("orphan.py"), partition.excludedFallbackLanesByArea.getValue("architecture").ownedPaths)
    assertTrue(partition.roots.any { it.lanes.any { it.packSlug == "generic" && it.area == "fallback-only" } })
  }

  @Test
  fun `two fallback owners still raise through ReviewFallbackResolver`() {
    val first = fallbackPack("generic-a", listOf("architecture"))
    val second = fallbackPack("generic-b", listOf("architecture"))

    assertFailsWith<SkillBillRuntimeException> {
      ReviewPerAreaFallbackExclusion.partition(emptyList(), listOf(first, second))
    }.also { failure ->
      assertEquals(SkillStagingFailureCode.INVALID_FALLBACK_CAPABILITY, failure.code)
    }
  }

  @Test
  fun `a fallback owner without a code-review baseline still raises`() {
    val owner =
      fallbackPack("generic", listOf("architecture")).copy(
        declaredFiles = DeclaredFiles(baseline = null, areas = emptyMap()),
      )

    assertFailsWith<SkillBillRuntimeException> {
      ReviewPerAreaFallbackExclusion.partition(emptyList(), listOf(owner))
    }.also { failure ->
      assertEquals(SkillStagingFailureCode.INVALID_FALLBACK_CAPABILITY, failure.code)
    }
  }

  @Test
  fun `partition order is independent of routed-root input order`() {
    val generic = fallbackPack("generic", listOf("architecture", "fallback-only"))
    val kotlin = nativePack("kotlin", listOf("architecture"))
    val roots =
      listOf(
        root(0, lane("generic", "architecture"), lane("generic", "fallback-only")),
        root(0, lane("kotlin", "architecture")),
      )

    val first = ReviewPerAreaFallbackExclusion.partition(roots, listOf(generic, kotlin))
    val second = ReviewPerAreaFallbackExclusion.partition(roots.reversed(), listOf(kotlin, generic))

    assertEquals(first, second)
  }

  private fun root(
    depthOffset: Int,
    vararg lanes: ReviewLaunchLane,
  ) = ReviewRootLanes(depthOffset, lanes.toList())

  private fun lane(
    packSlug: String,
    area: String,
    ownedPaths: List<String> = listOf("a.kt"),
  ) = ReviewLaunchLane(
    skillName = "bill-$packSlug-code-review-$area",
    packSlug = packSlug,
    area = area,
    depth = 0,
    originLayerChain = listOf(packSlug),
    required = true,
    addOns = emptyList(),
    orderIndex = 0,
    inclusionReason = "test",
    ownedPaths = ownedPaths,
    changedHunkIds = ownedPaths.map { "hunk-$it" },
  )

  private fun fallbackPack(
    slug: String,
    areas: List<String>,
  ) = nativePack(slug, areas).copy(
    fallbackCapabilities = setOf("code-review"),
    routingSignals = RoutingSignals(emptyList(), emptyList()),
  )

  private fun nativePack(
    slug: String,
    areas: List<String>,
  ) = PlatformManifest(
    slug = slug,
    packRoot = FileLocation("platform-packs/$slug"),
    contractVersion = "1.3",
    routingSignals = RoutingSignals(listOf("*.kt"), emptyList(), path = listOf("*.kt")),
    declaredCodeReviewAreas = areas,
    declaredFiles =
      DeclaredFiles(
        baseline = FileLocation("platform-packs/$slug/code-review/bill-$slug-code-review/content.md"),
        areas =
          areas.associateWith {
            FileLocation("platform-packs/$slug/code-review/bill-$slug-code-review-$it/content.md")
          },
      ),
    areaMetadata = emptyMap(),
    laneConditions = areas.associateWith { ReviewLaneCondition(required = true) },
    codeReviewComposition = null,
  )
}
