package skillbill.review.plan

import skillbill.error.shellcontent.ambiguousLaneOwnership
import skillbill.review.plan.model.ReviewLaunchLane
import skillbill.review.plan.model.ReviewReconciledLane
import skillbill.review.plan.model.ReviewRootLanes
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.policy.UNIVERSAL_CODE_REVIEW_AREAS

object ReviewCrossRootLaneReconciliation {
  fun compositionDepthOffsets(
    routedSlugs: Collection<String>,
    manifests: Collection<PlatformManifest>,
  ): Map<String, Int> {
    val bySlug = manifests.associateBy { it.slug }
    val routed = routedSlugs.toSet()
    val offsets = routed.associateWith { Int.MAX_VALUE }.toMutableMap()
    routed.forEach { rootSlug ->
      val distances = mutableMapOf(rootSlug to 0)
      val queue = ArrayDeque(listOf(rootSlug))
      while (queue.isNotEmpty()) {
        val slug = queue.removeFirst()
        val distance = distances.getValue(slug)
        bySlug[slug]?.codeReviewComposition?.baselineLayers.orEmpty().forEach { layer ->
          if (distances.putIfAbsent(layer.platform, distance + 1) == null) queue.addLast(layer.platform)
        }
      }
      distances.filterKeys { it != rootSlug && it in routed }.forEach { (slug, distance) ->
        offsets[slug] = minOf(offsets.getValue(slug), distance)
      }
    }
    return offsets.mapValues { (_, offset) -> if (offset == Int.MAX_VALUE) 0 else offset }
  }

  fun reconcile(
    roots: List<ReviewRootLanes>,
    excludedFallbackLanesByArea: Map<String, ReviewLaunchLane> = emptyMap(),
  ): List<ReviewReconciledLane> {
    val candidates =
      roots.flatMap { root -> root.lanes.map { Candidate(root.depthOffset + it.depth, it) } }
        .sortedWith(
          compareBy<Candidate>(
            { it.lane.area in UNIVERSAL_CODE_REVIEW_AREAS },
            { it.effectiveDepth },
            { it.lane.packSlug },
            { it.lane.area },
            { it.lane.skillName },
            { it.lane.depth },
            { it.lane.originLayerChain.joinToString(">") },
            { it.lane.inclusionReason },
          ),
        )
    return candidates.groupBy { it.lane.area }.map { (area, areaCandidates) ->
      val nearestDepth = areaCandidates.minOf { it.effectiveDepth }
      val nearest = areaCandidates.filter { it.effectiveDepth == nearestDepth }
      val owners = nearest.map { it.lane.packSlug }.distinct().sorted()
      if (owners.size > 1) {
        throw ambiguousLaneOwnership(
          "Review area '$area' has ambiguous cross-root ownership at composition depth $nearestDepth: " +
            owners.joinToString() + ".",
        )
      }
      val ownerLanes = areaCandidates.filter { it.lane.packSlug == owners.single() }.map { it.lane }
      val inputs = areaCandidates.map { it.lane } + listOfNotNull(excludedFallbackLanesByArea[area])
      Candidate(
        nearestDepth,
        nearest.first().lane.copy(
          required = inputs.any { it.required },
          ownedPaths = inputs.flatMap { it.ownedPaths }.distinct().sorted(),
          changedHunkIds = inputs.flatMap { it.changedHunkIds }.distinct(),
          originLayerChains = ownerLanes.flatMap { it.originLayerChains }.distinct(),
        ),
      ) to inputs
    }.sortedWith(
      compareBy<Pair<Candidate, List<ReviewLaunchLane>>>(
        { it.first.lane.area in UNIVERSAL_CODE_REVIEW_AREAS },
        { it.first.effectiveDepth },
        { it.first.lane.packSlug },
        { it.first.lane.area },
      ),
    ).mapIndexed { index, (survivor, inputs) ->
      ReviewReconciledLane(survivor.lane.copy(orderIndex = index), inputs)
    }
  }

  private data class Candidate(val effectiveDepth: Int, val lane: ReviewLaunchLane)
}
