package skillbill.review.plan

import skillbill.error.shellcontent.ambiguousLaneOwnership
import skillbill.error.shellcontent.incompatibleCompositionContract
import skillbill.error.shellcontent.missingCompositionLayer
import skillbill.error.shellcontent.reviewCompositionCycle
import skillbill.review.plan.model.ReviewLaunchLane
import skillbill.review.plan.model.ReviewLaunchPlan
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.policy.UNIVERSAL_CODE_REVIEW_AREAS

internal object ReviewLaunchPlanCompositionFailures {
  fun compositionCycle(cycle: String): Nothing =
    throw reviewCompositionCycle(
      "Review composition contains a cycle: $cycle.",
    )

  fun missingLayer(message: String): Nothing = throw missingCompositionLayer(message)

  fun incompatibleContract(message: String): Nothing = throw incompatibleCompositionContract(message)

  fun ambiguousOwnership(message: String): Nothing = throw ambiguousLaneOwnership(message)
}

internal fun composeReviewLaunchAreas(
  routedSlug: String,
  manifests: Collection<PlatformManifest>,
): Set<String> {
  val bySlug = manifests.associateBy { it.slug }
  val areas = linkedSetOf<String>()
  val visited = mutableSetOf<String>()

  fun visit(pack: PlatformManifest) {
    if (!visited.add(pack.slug)) return
    areas += pack.declaredCodeReviewAreas
    pack.codeReviewComposition?.baselineLayers.orEmpty().forEach { layer ->
      bySlug[layer.platform]?.let(::visit)
    }
  }
  bySlug[routedSlug]?.let(::visit)
  ReviewFallbackResolver.resolveOptional(manifests.toList())?.let { fallback ->
    areas += fallback.declaredCodeReviewAreas.filter { it in UNIVERSAL_CODE_REVIEW_AREAS }
  }
  return areas
}

internal data class ReviewLaunchAreaCandidate(
  val pack: PlatformManifest,
  val area: String,
  val depth: Int,
  val chain: List<String>,
  val requiredByComposition: Boolean,
  val chains: List<List<String>> = listOf(chain),
)

internal fun collectReviewLaunchAreaCandidates(
  root: PlatformManifest,
  bySlug: Map<String, PlatformManifest>,
  selectedAreas: Set<String>,
): List<ReviewLaunchAreaCandidate> {
  val candidates = mutableListOf<ReviewLaunchAreaCandidate>()

  fun visit(
    pack: PlatformManifest,
    depth: Int,
    chain: List<String>,
    path: List<String>,
    requiredByComposition: Boolean,
  ) {
    if (pack.slug in path) {
      val cycle = (path.dropWhile { it != pack.slug } + pack.slug).joinToString(" -> ")
      ReviewLaunchPlanCompositionFailures.compositionCycle(cycle)
    }
    pack.declaredCodeReviewAreas.filter { it in selectedAreas }.forEach { area ->
      candidates += ReviewLaunchAreaCandidate(pack, area, depth, chain + pack.slug, requiredByComposition)
    }
    pack.codeReviewComposition?.baselineLayers.orEmpty().forEach { layer ->
      val target = bySlug[layer.platform]
      if (target == null) {
        if (ReviewFallbackResolver.resolveOptional(bySlug.values.toList()) == null) {
          ReviewLaunchPlanCompositionFailures.missingLayer(
            "Platform pack '${pack.slug}' references missing composition layer '${layer.platform}/${layer.skill}'.",
          )
        }
        return@forEach
      }
      if (target.contractVersion != root.contractVersion) {
        ReviewLaunchPlanCompositionFailures.incompatibleContract(
          "Composition layer '${target.slug}' uses contract '${target.contractVersion}', " +
            "but routed pack '${root.slug}' uses '${root.contractVersion}'.",
        )
      }
      if (layer.skill != target.routedSkillName) {
        ReviewLaunchPlanCompositionFailures.missingLayer(
          "Platform pack '${pack.slug}' references unavailable baseline skill '${layer.platform}/${layer.skill}'.",
        )
      }
      visit(target, depth + 1, chain + pack.slug, path + pack.slug, layer.required)
    }
  }
  visit(root, 0, emptyList(), emptyList(), requiredByComposition = false)
  return candidates
}

internal fun resolveReviewLaunchAreaWinners(
  selectedAreas: Set<String>,
  candidates: List<ReviewLaunchAreaCandidate>,
): List<ReviewLaunchAreaCandidate> =
  selectedAreas.sorted().mapNotNull { area ->
    val areaCandidates = candidates.filter { it.area == area }
    val nearestDepth = areaCandidates.minOfOrNull { it.depth } ?: return@mapNotNull null
    val nearest = areaCandidates.filter { it.depth == nearestDepth }
    val owners = nearest.groupBy { it.pack.slug }
    if (owners.size > 1) {
      ReviewLaunchPlanCompositionFailures.ambiguousOwnership(
        "Review area '$area' has ambiguous ownership at composition depth $nearestDepth: " +
          owners.keys.sorted().joinToString() + ".",
      )
    }
    val ownerSlug = owners.keys.single()
    val winner = owners.values.single().first()
    val ownerCandidates = areaCandidates.filter { it.pack.slug == ownerSlug }
    winner.copy(
      chains = ownerCandidates.flatMap { it.chains }.distinct(),
      requiredByComposition = ownerCandidates.any { it.requiredByComposition },
    )
  }.sortedWith(compareBy<ReviewLaunchAreaCandidate>({ it.depth }, { it.pack.slug }, { it.area }))

internal fun reviewLaunchLanesFromWinners(winners: List<ReviewLaunchAreaCandidate>): List<ReviewLaunchLane> =
  winners.mapIndexed { index, winner ->
    val skillName = "bill-${winner.pack.slug}-code-review-${winner.area}"
    val condition = winner.pack.laneConditions[winner.area]
    ReviewLaunchLane(
      skillName = skillName,
      packSlug = winner.pack.slug,
      area = winner.area,
      depth = winner.depth,
      originLayerChain = winner.chain,
      originLayerChains = winner.chains,
      required = condition?.required ?: winner.requiredByComposition,
      addOns = ReviewAddonSelectionPolicy.select(winner.pack, skillName).map { it.slug },
      orderIndex = index,
      inclusionReason = if (winner.depth == 0) "routed-pack override" else "required baseline layer",
      pathSignals = condition?.path.orEmpty(),
      contentSignals = condition?.content.orEmpty(),
    )
  }

internal fun flattenReviewLaunchPlan(
  routedSlug: String,
  manifests: Collection<PlatformManifest>,
  selectedAreas: Set<String>,
): ReviewLaunchPlan {
  if (selectedAreas.isEmpty()) return ReviewLaunchPlan(routedSlug, emptyList())
  val bySlug = manifests.associateBy { it.slug }
  val root =
    bySlug[routedSlug]
      ?: ReviewLaunchPlanCompositionFailures.missingLayer(
        "Routed platform pack '$routedSlug' is missing from review composition.",
      )
  val candidates = collectReviewLaunchAreaCandidates(root, bySlug, selectedAreas)
  val winners = resolveReviewLaunchAreaWinners(selectedAreas, candidates)
  val routedWinners = winners.filter { it.area !in UNIVERSAL_CODE_REVIEW_AREAS }
  val routedLanes = reviewLaunchLanesFromWinners(routedWinners)
  val selectedUniversalAreas = selectedAreas intersect UNIVERSAL_CODE_REVIEW_AREAS
  val graphOwnedUniversalWinners = winners.filter { it.area in UNIVERSAL_CODE_REVIEW_AREAS }
  val fallback = ReviewFallbackResolver.resolveOptional(manifests.toList())
  val fallbackLanes =
    fallback?.let { owner ->
      val depth = (winners.maxOfOrNull { it.depth } ?: -1) + 1
      selectedUniversalAreas
        .filter { area -> graphOwnedUniversalWinners.none { it.area == area } }
        .filter { area -> area in owner.declaredCodeReviewAreas }
        .map { area ->
          val skillName = "bill-${owner.slug}-code-review-$area"
          val condition = owner.laneConditions[area]
          ReviewLaunchLane(
            skillName = skillName,
            packSlug = owner.slug,
            area = area,
            depth = depth,
            originLayerChain = listOf(routedSlug, owner.slug),
            originLayerChains = listOf(listOf(routedSlug, owner.slug)),
            required = true,
            addOns = ReviewAddonSelectionPolicy.select(owner, skillName).map { it.slug },
            orderIndex = routedLanes.size,
            inclusionReason = "universal fallback lane",
            pathSignals = condition?.path.orEmpty(),
            contentSignals = condition?.content.orEmpty(),
          )
        }
    }.orEmpty()
  val graphUniversalLanes =
    reviewLaunchLanesFromWinners(graphOwnedUniversalWinners).mapIndexed { index, lane ->
      lane.copy(orderIndex = routedLanes.size + index)
    }
  val lanes = routedLanes + graphUniversalLanes + fallbackLanes.mapIndexed { index, lane ->
    lane.copy(orderIndex = routedLanes.size + graphUniversalLanes.size + index)
  }
  return ReviewLaunchPlan(routedPackSlug = routedSlug, lanes = lanes)
}
