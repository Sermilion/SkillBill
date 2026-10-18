package skillbill.review.plan

import org.junit.jupiter.api.Test
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.ManifestFailureCode
import skillbill.model.FileLocation
import skillbill.review.plan.model.ReviewLaunchLane
import skillbill.review.plan.model.ReviewRootLanes
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.CodeReviewComposition
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.RoutingSignals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReviewCrossRootLaneReconciliationTest {
  @Test
  fun `independent Python fixture review retains its scope beside composed Kotlin lanes`() {
    val roots =
      mapOf(
        "kmp" to root(0, lane("kmp", "architecture", 0, ownedPaths = listOf("shared.kt"))),
        "kotlin" to root(1, lane("kotlin", "architecture", 0, ownedPaths = listOf("backend.kt"))),
        "python" to root(0, lane("python", "architecture", 0, ownedPaths = listOf("capture.py"))),
      )
    val manifests = listOf(pack("kmp", listOf("kotlin")), pack("kotlin"), pack("python"))

    val lanes = ReviewCrossRootLaneReconciliation.reconcileRoutedRoots(roots, manifests)

    assertEquals(listOf("kmp", "python"), lanes.map { it.lane.packSlug })
    assertEquals(listOf("backend.kt", "shared.kt"), lanes.first().lane.ownedPaths)
    assertEquals(listOf("capture.py"), lanes.last().lane.ownedPaths)
    assertEquals(listOf("hunk-capture.py"), lanes.last().lane.changedHunkIds)
    assertEquals(listOf(0, 1), lanes.map { it.lane.orderIndex })
    assertEquals(
      lanes,
      ReviewCrossRootLaneReconciliation.reconcileRoutedRoots(roots.toList().reversed().toMap(), manifests.reversed()),
    )
  }

  @Test
  fun `conflicting ownership recovers to fallback without losing paths hunks or original owners`() {
    val roots =
      mapOf(
        "kotlin" to root(0, lane("kotlin", "architecture", 0, ownedPaths = listOf("shared.txt", "main.kt"))),
        "python" to root(0, lane("python", "architecture", 0, ownedPaths = listOf("shared.txt", "capture.py"))),
      )
    val fallback = pack("generic").copy(fallbackCapabilities = setOf("code-review"))
    val resolution = ReviewLaneOwnershipResolution.reconcile(roots, listOf(pack("kotlin"), pack("python"), fallback))

    val recovered = resolution.lanes.single()
    assertEquals("generic", recovered.lane.packSlug)
    assertEquals(listOf("capture.py", "main.kt", "shared.txt"), recovered.lane.ownedPaths)
    assertEquals(listOf("hunk-capture.py", "hunk-main.kt", "hunk-shared.txt"), recovered.lane.changedHunkIds)
    assertTrue(recovered.lane.required)
    assertEquals(setOf("kotlin", "python"), recovered.inputs.map { it.packSlug }.toSet())
    assertEquals(listOf("kotlin", "python"), resolution.conflicts.single().owners)
    assertEquals("architecture", resolution.conflicts.single().area)
    assertEquals("generic", resolution.conflicts.single().fallbackOwner)
  }

  @Test
  fun `independent roots assigning the same path to different area owners still fail`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        ReviewCrossRootLaneReconciliation.reconcileRoutedRoots(
          mapOf(
            "kotlin" to root(0, lane("kotlin", "architecture", 0)),
            "python" to root(0, lane("python", "architecture", 0)),
          ),
          listOf(pack("kotlin"), pack("python")),
        )
      }
    assertEquals(ManifestFailureCode.AMBIGUOUS_LANE_OWNERSHIP, error.code)
  }

  @Test
  fun `same universal fallback lane from two roots reconciles once at the end`() {
    val first =
      root(
        0,
        lane("kmp", "architecture", depth = 0),
        lane("generic", "code-quality", depth = 1),
      )
    val second =
      root(
        1,
        lane("kotlin", "security", depth = 0),
        lane("generic", "code-quality", depth = 1),
      )

    val reconciled = ReviewCrossRootLaneReconciliation.reconcile(listOf(first, second))

    assertEquals(3, reconciled.size)
    assertEquals("bill-generic-code-review-code-quality", reconciled.last().lane.skillName)
    assertEquals(2, reconciled.last().lane.orderIndex)
    assertEquals(1, reconciled.count { it.lane.area == "code-quality" })
  }

  @Test
  fun `a composing root owns a shared area over the baseline root routing brought along`() {
    val composing = root(0, lane("kmp", "architecture", depth = 0, ownedPaths = listOf("a.kt")))
    val baseline = root(1, lane("kotlin", "architecture", depth = 0, ownedPaths = listOf("b.kt")))

    val reconciled = ReviewCrossRootLaneReconciliation.reconcile(listOf(baseline, composing))

    assertEquals(1, reconciled.size)
    assertEquals("kmp", reconciled.single().lane.packSlug)
    assertEquals("bill-kmp-code-review-architecture", reconciled.single().lane.skillName)
  }

  @Test
  fun `the nearest composition depth owns an area two roots both plan`() {
    val near = root(0, lane("kmp", "platform-correctness", depth = 0))
    val far = root(0, lane("kotlin", "platform-correctness", depth = 1))

    val reconciled = ReviewCrossRootLaneReconciliation.reconcile(listOf(far, near))

    assertEquals(listOf("kmp"), reconciled.map { it.lane.packSlug })
  }

  @Test
  fun `two distinct packs tying at the nearest depth raise ambiguous ownership naming both slugs`() {
    val roots =
      listOf(
        root(0, lane("kmp", "security", depth = 0)),
        root(0, lane("swift", "security", depth = 0)),
      )

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        ReviewCrossRootLaneReconciliation.reconcile(roots)
      }.also { failure ->
        assertEquals(ManifestFailureCode.AMBIGUOUS_LANE_OWNERSHIP, failure.code)
      }

    assertTrue(error.message!!.contains("security"))
    assertTrue(error.message!!.contains("kmp"))
    assertTrue(error.message!!.contains("swift"))
  }

  @Test
  fun `the surviving lane claims the union of every reconciled lane's paths hunks and requirement`() {
    val near =
      root(
        0,
        lane(
          "kmp",
          "architecture",
          depth = 0,
          required = false,
          ownedPaths = listOf("b.kt", "a.kt"),
        ),
      )
    val far =
      root(
        1,
        lane(
          "kotlin",
          "architecture",
          depth = 0,
          required = true,
          ownedPaths = listOf("c.kt", "a.kt"),
        ),
      )

    val survivor = ReviewCrossRootLaneReconciliation.reconcile(listOf(near, far)).single().lane

    assertEquals(listOf("a.kt", "b.kt", "c.kt"), survivor.ownedPaths)
    assertEquals(listOf("hunk-b.kt", "hunk-a.kt", "hunk-c.kt"), survivor.changedHunkIds)
    assertTrue(survivor.required)
  }

  @Test
  fun `reconciliation is independent of input order including assigned order indexes`() {
    val roots =
      listOf(
        root(
          0,
          lane("kmp", "ui", depth = 0),
          lane("kmp", "security", depth = 0),
          lane("kotlin", "testing", depth = 1),
        ),
        root(1, lane("kotlin", "ui", depth = 0), lane("kotlin", "testing", depth = 0)),
      )

    val first = ReviewCrossRootLaneReconciliation.reconcile(roots).map { it.lane }
    val second =
      ReviewCrossRootLaneReconciliation
        .reconcile(roots.reversed().map { it.copy(lanes = it.lanes.reversed()) })
        .map { it.lane }

    assertEquals(first, second)
    assertEquals(listOf(0, 1, 2), first.map { it.orderIndex })
    assertEquals(listOf("security", "ui", "testing"), first.map { it.area })
  }

  @Test
  fun `a routed baseline pack is offset beneath the routed pack that composes it`() {
    val kotlin = pack("kotlin")
    val kmp = pack("kmp", baselines = listOf("kotlin"))

    val offsets =
      ReviewCrossRootLaneReconciliation
        .compositionDepthOffsets(listOf("kotlin", "kmp"), listOf(kotlin, kmp))

    assertEquals(mapOf("kmp" to 0, "kotlin" to 1), offsets)
  }

  @Test
  fun `excluded fallback paths transfer into the surviving native lane for the area`() {
    val native = root(0, lane("kmp", "architecture", depth = 0, ownedPaths = listOf("a.kt")))
    val fallback = lane("generic", "architecture", depth = 0, ownedPaths = listOf("fallback.py"))

    val reconciled =
      ReviewCrossRootLaneReconciliation.reconcile(
        listOf(native),
        mapOf("architecture" to fallback),
      )

    assertEquals(listOf("a.kt", "fallback.py"), reconciled.single().lane.ownedPaths)
    assertEquals(listOf("hunk-a.kt", "hunk-fallback.py"), reconciled.single().lane.changedHunkIds)
  }

  private fun root(
    depthOffset: Int,
    vararg lanes: ReviewLaunchLane,
  ) = ReviewRootLanes(depthOffset, lanes.toList())

  private fun pack(
    slug: String,
    baselines: List<String> = emptyList(),
  ) = PlatformManifest(
    slug = slug,
    packRoot = FileLocation("platform-packs/$slug"),
    contractVersion = "1.3",
    routingSignals = RoutingSignals(emptyList(), emptyList()),
    declaredCodeReviewAreas = listOf("architecture"),
    declaredFiles =
      DeclaredFiles(
        baseline = FileLocation("platform-packs/$slug/code-review/bill-$slug-code-review/content.md"),
        areas = emptyMap(),
      ),
    areaMetadata = emptyMap(),
    laneConditions = emptyMap(),
    codeReviewComposition =
      baselines
        .takeIf { it.isNotEmpty() }
        ?.map {
          CodeReviewBaselineLayer(
            platform = it,
            skill = "bill-$it-code-review",
            scope = CodeReviewCompositionScope.SameReviewScope,
            required = true,
            mode = CodeReviewCompositionMode.KmpBaseline,
          )
        }
        ?.let(::CodeReviewComposition),
  )

  private fun lane(
    packSlug: String,
    area: String,
    depth: Int,
    required: Boolean = true,
    ownedPaths: List<String> = listOf("a.kt"),
  ) = ReviewLaunchLane(
    skillName = "bill-$packSlug-code-review-$area",
    packSlug = packSlug,
    area = area,
    depth = depth,
    originLayerChain = listOf(packSlug),
    required = required,
    addOns = emptyList(),
    orderIndex = 0,
    inclusionReason = "test",
    ownedPaths = ownedPaths,
    changedHunkIds = ownedPaths.map { "hunk-$it" },
  )
}
