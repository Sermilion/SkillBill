package skillbill.review.plan

import org.junit.jupiter.api.Test
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.ManifestFailureCode
import skillbill.model.FileLocation
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.CodeReviewComposition
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.ReviewLaneCondition
import skillbill.scaffold.model.RoutingSignals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewLaunchPlanPolicyTest {
  @Test
  fun `kmp flattens kotlin baseline into ten direct specialists`() {
    val kotlin = pack("kotlin", KOTLIN_AREAS)
    val kmp = pack("kmp", KMP_AREAS, layers = listOf(layer("kotlin")))

    val plan = ReviewLaunchPlanPolicy.flatten("kmp", listOf(kmp, kotlin), (KOTLIN_AREAS + KMP_AREAS).toSet())

    assertEquals(10, plan.lanes.size)
    assertEquals(KMP_AREAS.sorted(), plan.lanes.take(3).map { it.area })
    assertEquals(KOTLIN_AREAS.filterNot { it in KMP_AREAS }.sorted(), plan.lanes.drop(3).map { it.area })
    assertTrue(plan.lanes.none { it.skillName == "bill-kotlin-code-review" })
    assertTrue(plan.lanes.filter { it.packSlug == "kotlin" }.all { it.originLayerChain == listOf("kmp", "kotlin") })
  }

  @Test
  fun `universal fallback lane is appended without changing routed lanes`() {
    val kotlin = pack("kotlin", KOTLIN_AREAS)
    val kmp = pack("kmp", KMP_AREAS, layers = listOf(layer("kotlin")))
    val generic =
      pack(
        "generic",
        listOf("code-quality"),
        laneConditions =
          mapOf(
            "code-quality" to
              ReviewLaneCondition(required = true, path = listOf("quality/"), content = listOf("idiom")),
          ),
      ).copy(fallbackCapabilities = setOf("code-review"))
    val genericWithoutArea = pack("generic", emptyList()).copy(fallbackCapabilities = setOf("code-review"))
    val selected = (KOTLIN_AREAS + KMP_AREAS + "code-quality").toSet()

    val plan = ReviewLaunchPlanPolicy.flatten("kmp", listOf(kmp, kotlin, generic), selected)
    val baselinePlan = ReviewLaunchPlanPolicy.flatten("kmp", listOf(kmp, kotlin, genericWithoutArea), selected)
    val lane = plan.lanes.last()

    assertEquals(baselinePlan.lanes, plan.lanes.dropLast(1))
    assertEquals("bill-generic-code-review-code-quality", lane.skillName)
    assertEquals("generic", lane.packSlug)
    assertEquals("code-quality", lane.area)
    assertTrue(lane.required)
    assertEquals(listOf("kmp", "generic"), lane.originLayerChain)
    assertEquals(listOf(listOf("kmp", "generic")), lane.originLayerChains)
    assertEquals(plan.lanes.size - 1, lane.orderIndex)
    assertEquals((baselinePlan.lanes.maxOf { it.depth }) + 1, lane.depth)
    assertEquals("universal fallback lane", lane.inclusionReason)
    assertEquals(listOf("quality/"), lane.pathSignals)
    assertEquals(listOf("idiom"), lane.contentSignals)
    assertEquals(ReviewAddonSelectionPolicy.select(generic, lane.skillName).map { it.slug }, lane.addOns)
  }

  @Test
  fun `generic root owns one quality lane and missing fallback is optional`() {
    val generic =
      pack(
        "generic",
        listOf("security", "code-quality"),
        laneConditions = mapOf("code-quality" to ReviewLaneCondition(required = true)),
      ).copy(fallbackCapabilities = setOf("code-review"))
    val genericPlan = ReviewLaunchPlanPolicy.flatten("generic", listOf(generic), setOf("security", "code-quality"))
    assertEquals(1, genericPlan.lanes.count { it.area == "code-quality" })
    assertEquals("code-quality", genericPlan.lanes.last().area)
    assertEquals("bill-generic-code-review-code-quality", genericPlan.lanes.last().skillName)

    val routed = pack("kotlin", listOf("security"))
    val absent = ReviewLaunchPlanPolicy.flatten("kotlin", listOf(routed), setOf("security", "code-quality"))
    val undeclaredFallback = pack("generic", emptyList()).copy(fallbackCapabilities = setOf("code-review"))
    val undeclared =
      ReviewLaunchPlanPolicy.flatten("kotlin", listOf(routed, undeclaredFallback), setOf("security", "code-quality"))
    assertTrue(absent.lanes.none { it.area == "code-quality" })
    assertTrue(undeclared.lanes.none { it.area == "code-quality" })
  }

  @Test
  fun `a required baseline layer does not force a signal-gated composed area to be required`() {
    val kotlin =
      pack(
        "kotlin",
        listOf("architecture", "security"),
        laneConditions =
          mapOf(
            "architecture" to ReviewLaneCondition(required = true),
            "security" to ReviewLaneCondition(content = listOf("secret")),
          ),
      )
    val kmp = pack("kmp", emptyList(), layers = listOf(layer("kotlin", required = true)))

    val plan = ReviewLaunchPlanPolicy.flatten("kmp", listOf(kmp, kotlin), setOf("architecture", "security"))

    assertTrue(plan.lanes.single { it.area == "architecture" }.required)
    assertFalse(
      plan.lanes.single { it.area == "security" }.required,
      "Kotlin's own signal-gated 'security' condition must not be overridden by the composing " +
        "layer's blanket required flag.",
    )
  }

  @Test
  fun `standalone pack emits its selected direct specialists and drops empty selection`() {
    val kotlin = pack("kotlin", KOTLIN_AREAS)
    assertEquals(
      listOf("bill-kotlin-code-review-architecture", "bill-kotlin-code-review-security"),
      ReviewLaunchPlanPolicy.flatten("kotlin", listOf(kotlin), setOf("security", "architecture"))
        .lanes.map { it.skillName },
    )
    assertTrue(ReviewLaunchPlanPolicy.flatten("kotlin", listOf(kotlin), emptySet()).lanes.isEmpty())
  }

  @Test
  fun `three deep composition keeps the complete attribution chain`() {
    val base = pack("base", listOf("security"))
    val middle = pack("middle", listOf("testing"), layers = listOf(layer("base")))
    val root = pack("root", listOf("ui"), layers = listOf(layer("middle")))

    val plan = ReviewLaunchPlanPolicy.flatten("root", listOf(root, middle, base), setOf("ui", "testing", "security"))

    assertEquals(listOf("root", "middle", "base"), plan.lanes.single { it.area == "security" }.originLayerChain)
  }

  @Test
  fun `diamond composition retains every origin and required reachability`() {
    val base = pack("base", listOf("security"))
    val left = pack("left", emptyList(), layers = listOf(layer("base", required = false)))
    val right = pack("right", emptyList(), layers = listOf(layer("base")))
    val root =
      pack(
        "root",
        emptyList(),
        layers = listOf(layer("left", required = false), layer("right", required = false)),
      )

    val lane = ReviewLaunchPlanPolicy.flatten("root", listOf(root, left, right, base), setOf("security")).lanes.single()

    assertEquals(listOf(listOf("root", "left", "base"), listOf("root", "right", "base")), lane.originLayerChains)
    assertTrue(lane.required)
  }

  @Test
  fun `nearest owner retains deeper required reachability for the same owner`() {
    val owner = pack("owner", listOf("security"))
    val bridge = pack("bridge", emptyList(), layers = listOf(layer("owner")))
    val root =
      pack(
        "root",
        emptyList(),
        layers = listOf(layer("owner", required = false), layer("bridge", required = false)),
      )

    val lane = ReviewLaunchPlanPolicy.flatten("root", listOf(root, owner, bridge), setOf("security")).lanes.single()

    assertEquals("owner", lane.packSlug)
    assertEquals(1, lane.depth)
    assertEquals(listOf("root", "owner"), lane.originLayerChain)
    assertEquals(
      listOf(listOf("root", "owner"), listOf("root", "bridge", "owner")),
      lane.originLayerChains,
    )
    assertTrue(lane.required)
    assertEquals(0, lane.orderIndex)
  }

  @Test
  fun `cycle missing layer and contract drift fail loudly`() {
    val a = pack("a", listOf("security"), layers = listOf(layer("b")))
    val b = pack("b", listOf("testing"), layers = listOf(layer("a")))
    assertFailsWith<SkillBillRuntimeException> {
      ReviewLaunchPlanPolicy.flatten("a", listOf(a, b), setOf("security", "testing"))
    }.also { failure ->
      assertEquals(ManifestFailureCode.REVIEW_COMPOSITION_CYCLE, failure.code)
    }
    assertFailsWith<SkillBillRuntimeException> {
      ReviewLaunchPlanPolicy.flatten("a", listOf(a), setOf("security"))
    }.also { failure ->
      assertEquals(ManifestFailureCode.MISSING_COMPOSITION_LAYER, failure.code)
    }
    assertFailsWith<SkillBillRuntimeException> {
      ReviewLaunchPlanPolicy.flatten("a", listOf(a, b.copy(contractVersion = "2.0")), setOf("security"))
    }.also { failure ->
      assertEquals(ManifestFailureCode.INCOMPATIBLE_COMPOSITION_CONTRACT, failure.code)
    }
  }

  @Test
  fun `same depth sibling ownership is rejected`() {
    val left = pack("left", listOf("security"))
    val right = pack("right", listOf("security"))
    val root = pack("root", listOf("ui"), layers = listOf(layer("left"), layer("right")))
    assertFailsWith<SkillBillRuntimeException> {
      ReviewLaunchPlanPolicy.flatten("root", listOf(root, left, right), setOf("security"))
    }.also { failure ->
      assertEquals(ManifestFailureCode.AMBIGUOUS_LANE_OWNERSHIP, failure.code)
    }
  }

  @Test
  fun `flattened lanes preserve path and content signals for sparse commit routing`() {
    val kotlin =
      pack(
        "kotlin",
        listOf("security", "ui"),
        laneConditions =
          mapOf(
            "security" to ReviewLaneCondition(path = listOf("auth/"), content = listOf("authorize")),
            "ui" to ReviewLaneCondition(path = listOf("ui/"), content = listOf("@Composable")),
          ),
      )

    val plan = ReviewLaunchPlanPolicy.flatten("kotlin", listOf(kotlin), setOf("security", "ui"))

    val security = plan.lanes.single { it.area == "security" }
    val ui = plan.lanes.single { it.area == "ui" }
    assertEquals(listOf("auth/"), security.pathSignals)
    assertEquals(listOf("authorize"), security.contentSignals)
    assertEquals(listOf("ui/"), ui.pathSignals)
    assertEquals(listOf("@Composable"), ui.contentSignals)
    assertEquals(listOf("security", "ui"), plan.lanes.map { it.area })
  }

  private fun pack(
    slug: String,
    areas: List<String>,
    layers: List<CodeReviewBaselineLayer> = emptyList(),
    laneConditions: Map<String, ReviewLaneCondition> = emptyMap(),
  ) = PlatformManifest(
    slug = slug,
    packRoot = FileLocation("platform-packs/$slug"),
    contractVersion = "1.3",
    routingSignals = RoutingSignals(emptyList(), emptyList()),
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
    laneConditions = laneConditions,
    codeReviewComposition = layers.takeIf { it.isNotEmpty() }?.let(::CodeReviewComposition),
  )

  private fun layer(
    slug: String,
    required: Boolean = true,
  ) = CodeReviewBaselineLayer(
    platform = slug,
    skill = "bill-$slug-code-review",
    scope = CodeReviewCompositionScope.SameReviewScope,
    required = required,
    mode = CodeReviewCompositionMode.KmpBaseline,
  )

  private companion object {
    val KMP_AREAS = listOf("platform-correctness", "ui", "ux-accessibility")
    val KOTLIN_AREAS =
      listOf(
        "architecture", "performance", "platform-correctness", "security", "testing",
        "api-contracts", "persistence", "reliability", "ui", "ux-accessibility",
      )
  }
}
