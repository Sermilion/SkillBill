package skillbill.review.plan

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.ManifestFailureCode
import skillbill.review.plan.model.ReviewLaneOwnershipConflict
import skillbill.review.plan.model.ReviewLaneOwnershipResult
import skillbill.review.plan.model.ReviewLaunchLane
import skillbill.review.plan.model.ReviewReconciledLane
import skillbill.review.plan.model.ReviewRootLanes
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.policy.UNIVERSAL_CODE_REVIEW_AREAS

object ReviewLaneOwnershipResolution {
  fun reconcile(
    roots: Map<String, ReviewRootLanes>,
    manifests: Collection<PlatformManifest>,
  ): ReviewLaneOwnershipResult {
    try {
      return ReviewLaneOwnershipResult(ReviewCrossRootLaneReconciliation.reconcileRoutedRoots(roots, manifests))
    } catch (error: SkillBillRuntimeException) {
      if (error.code != ManifestFailureCode.AMBIGUOUS_LANE_OWNERSHIP) throw error
    }
    val inputs = roots.values.flatMap { it.lanes }
    return resolveAreas(
      inputs.map { it.area }.toSet(),
      manifests,
      { area -> inputs.filter { it.area == area } },
    ) { area ->
      val scoped = roots.mapValues { (_, root) -> root.copy(lanes = root.lanes.filter { it.area == area }) }
      ReviewCrossRootLaneReconciliation.reconcileRoutedRoots(scoped, manifests)
    }
  }

  private fun resolveAreas(
    areas: Set<String>,
    manifests: Collection<PlatformManifest>,
    inputs: (String) -> List<ReviewLaunchLane>,
    resolve: (String) -> List<ReviewReconciledLane>,
  ): ReviewLaneOwnershipResult {
    val conflicts = mutableListOf<ReviewLaneOwnershipConflict>()
    val lanes =
      areas.sorted().flatMap { area ->
        try {
          resolve(area)
        } catch (error: SkillBillRuntimeException) {
          if (error.code != ManifestFailureCode.AMBIGUOUS_LANE_OWNERSHIP) throw error
          val fallback = ReviewFallbackResolver.resolveRequired(manifests.toList())
          val candidates = inputs(area)
          val lane =
            ReviewLaunchPlanPolicy.flatten(
              fallback.slug,
              manifests,
              setOf(area),
            ).lanes.single { it.area == area }
          conflicts +=
            ReviewLaneOwnershipConflict(
              area,
              candidates.map { it.packSlug }.distinct().sorted(),
              fallback.slug,
              error.message.orEmpty(),
            )
          listOf(
            ReviewReconciledLane(
              lane.copy(
                required = true,
                ownedPaths = candidates.flatMap { it.ownedPaths }.distinct().sorted(),
                changedHunkIds = candidates.flatMap { it.changedHunkIds }.distinct().sorted(),
                inclusionReason = "ambiguous ownership fallback",
              ),
              candidates,
            ),
          )
        }
      }.sortedWith(
        compareBy({
          it.lane.area in UNIVERSAL_CODE_REVIEW_AREAS
        }, { it.lane.orderIndex }, { it.lane.packSlug }, { it.lane.area }),
      )
        .mapIndexed { index, lane -> lane.copy(lane = lane.lane.copy(orderIndex = index)) }
    return ReviewLaneOwnershipResult(lanes, conflicts)
  }
}
