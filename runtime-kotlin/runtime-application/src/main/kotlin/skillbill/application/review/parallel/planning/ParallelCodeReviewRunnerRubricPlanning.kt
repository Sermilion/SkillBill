package skillbill.application.review.parallel.planning

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.model.ReviewRubricProjection
import skillbill.application.review.model.ReviewWorkerKind.GENERIC
import skillbill.application.review.parallel.runner.ParallelCodeReviewStackDetection
import skillbill.application.reviewevidence.model.ReviewChangedFileEvidence
import skillbill.application.reviewevidence.model.ReviewDiffEvidence
import skillbill.error.shellcontent.reviewAggregationIntegrityError
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.review.model.ReviewOwnedFileEvidence
import skillbill.ports.review.preparation.ReviewRubricResolver
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort
import skillbill.review.model.ReviewRunLane
import skillbill.review.plan.ReviewAddonSelectionPolicy
import skillbill.review.plan.ReviewCrossRootLaneReconciliation
import skillbill.review.plan.ReviewFallbackResolver
import skillbill.review.plan.ReviewLaneInclusionPolicy
import skillbill.review.plan.ReviewLaneOwnershipResolution
import skillbill.review.plan.ReviewLaunchPlanPolicy
import skillbill.review.plan.ReviewStackRouting
import skillbill.review.plan.model.ReviewLaneOwnershipConflict
import skillbill.review.plan.model.ReviewLaunchLane
import skillbill.review.plan.model.ReviewReconciledLane
import skillbill.review.plan.model.ReviewRootLanes
import skillbill.review.plan.model.ReviewRoutingChangedFile
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.policy.UNIVERSAL_CODE_REVIEW_AREAS

@Inject
class ParallelCodeReviewRunnerRubricPlanning(
  private val reviewRubricResolver: ReviewRubricResolver,
  private val installedPackCatalog: InstalledPlatformPackCatalogPort,
  private val diagnostics: RuntimeDiagnostics,
) {
  internal fun installedManifests(): List<PlatformManifest> = installedPackCatalog.manifests()

  internal fun routeStack(
    manifests: List<PlatformManifest>,
    evidence: ReviewDiffEvidence,
  ) = ReviewStackRouting.route(manifests, evidence.files.map { ReviewRoutingChangedFile(it.path, it.changedContent) })
    .also { routing ->
      routing.ownershipConflicts.groupBy { it.owners to it.fallbackSlug }.forEach { (owners, conflicts) ->
        diagnostics.error(
          "seam=review_stack_routing failure_code=AMBIGUOUS_LANE_OWNERSHIP " +
            "value_expected=unambiguous_owner value_used=${owners.second ?: "horizontal"} " +
            "conflicting_owners=${diagnosticOwners(owners.first)} " +
            "owner_count=${owners.first.size} affected_path_count=${conflicts.size}",
        )
      }
    }

  internal fun resolvePlannedRubrics(
    evidence: ReviewDiffEvidence,
    routing: ParallelCodeReviewStackDetection,
    acceptedLanes: List<ReviewRunLane> = emptyList(),
    qualityOnly: Boolean = false,
  ): List<PlannedReviewRubric> =
    if (routing.routed.isEmpty()) {
      resolveWithoutRoutedManifests(evidence, routing.manifests, acceptedLanes, qualityOnly)
    } else {
      resolveWithRoutedManifests(evidence, routing, acceptedLanes, qualityOnly)
    }

  private fun resolveWithoutRoutedManifests(
    evidence: ReviewDiffEvidence,
    manifests: List<PlatformManifest>,
    acceptedLanes: List<ReviewRunLane>,
    qualityOnly: Boolean,
  ): List<PlannedReviewRubric> {
    val installed = installedPackCatalog.manifests()
    if (installed.isEmpty()) {
      if (qualityOnly) {
        recordMissingQualityOwner("no_installed_packs")
        return emptyList()
      }
      return preserveAcceptedQualityOwner(horizontalPlannedRubrics(evidence), evidence, manifests, acceptedLanes)
    }
    val routing =
      routeStack(installed, evidence)
    return if (routing.routedSlugs.isEmpty()) {
      if (qualityOnly) {
        recordMissingQualityOwner("no_routed_pack")
        return emptyList()
      }
      preserveAcceptedQualityOwner(horizontalPlannedRubrics(evidence), evidence, manifests, acceptedLanes)
    } else {
      resolveWithRoutedManifests(
        evidence,
        ParallelCodeReviewStackDetection(
          installed.filter { it.slug in routing.routedSlugs },
          manifests.ifEmpty { installed },
          routing.ownedPathsBySlug,
        ),
        acceptedLanes,
        qualityOnly,
      )
    }
  }

  private fun resolveWithRoutedManifests(
    evidence: ReviewDiffEvidence,
    routing: ParallelCodeReviewStackDetection,
    acceptedLanes: List<ReviewRunLane>,
    qualityOnly: Boolean,
  ): List<PlannedReviewRubric> {
    val manifests = routing.manifests
    val resolution = ReviewLaneOwnershipResolution.reconcile(scopedRootLanes(evidence, routing), manifests)
    val conflicts = resolution.conflicts
    conflicts.forEach(::recordOwnershipFallback)
    val reconciled = resolution.lanes
    if (reconciled.none { it.lane.area in UNIVERSAL_CODE_REVIEW_AREAS }) {
      recordMissingQualityOwner("no_manifest_declared_owner")
    }
    val current =
      reconciled
        .filter { it.lane.ownedPaths.isNotEmpty() && (!qualityOnly || it.lane.area in UNIVERSAL_CODE_REVIEW_AREAS) }
        .map { toPlannedRubric(evidence, it, manifests) }
    val conflictingAreas = conflicts.map { it.area }.toSet()
    return preserveAcceptedQualityOwner(
      current,
      evidence,
      manifests,
      acceptedLanes.filter { it.area !in conflictingAreas },
    )
  }

  private fun scopedRootLanes(
    evidence: ReviewDiffEvidence,
    routing: ParallelCodeReviewStackDetection,
  ): Map<String, ReviewRootLanes> {
    val manifests = routing.manifests
    val depthOffsets =
      ReviewCrossRootLaneReconciliation
        .compositionDepthOffsets(routing.routed.map { it.slug }, manifests)
    return routing.routed.associate { root ->
      val rootOwnedPaths = routing.ownedPathsBySlug[root.slug].orEmpty()
      val rootFiles = evidence.files.filter { it.path in rootOwnedPaths }
      val selectedAreas = ReviewLaunchPlanPolicy.composedAreas(root.slug, manifests)
      val lanes =
        ReviewLaunchPlanPolicy.flatten(root.slug, manifests, selectedAreas).lanes.also { lanes ->
          require(lanes.isNotEmpty()) {
            "Routed pack '${root.slug}' resolved no declared flattened specialist worker."
          }
        }.map { lane ->
          val ownedPaths = if (lane.required) rootOwnedPaths.toList() else laneOwnedPaths(lane, rootFiles)
          lane.copy(
            ownedPaths = ownedPaths.distinct().sorted(),
            changedHunkIds = evidence.hunks.filter { it.path in ownedPaths }.map { it.hunkId },
          )
        }
      root.slug to ReviewRootLanes(depthOffsets[root.slug] ?: 0, lanes)
    }
  }

  private fun recordOwnershipFallback(conflict: ReviewLaneOwnershipConflict) {
    diagnostics.error(
      "seam=review_lane_ownership failure_code=AMBIGUOUS_LANE_OWNERSHIP " +
        "area=${conflict.area} value_expected=unambiguous_owner value_used=${conflict.fallbackOwner} " +
        "candidate_owners=${diagnosticOwners(conflict.owners)} " +
        "owner_count=${conflict.owners.size} reason=${conflict.reason.take(MAX_DIAGNOSTIC_REASON_LENGTH)}",
    )
  }

  private fun diagnosticOwners(owners: List<String>): String =
    owners.take(MAX_DIAGNOSTIC_OWNERS).joinToString { it.take(MAX_DIAGNOSTIC_SLUG_LENGTH) }

  private fun recordMissingQualityOwner(reason: String) {
    diagnostics.warning(
      "seam=review_code_quality_owner value_used=omitted value_expected=local_or_generic_fallback reason=$reason",
    )
  }

  private fun preserveAcceptedQualityOwner(
    planned: List<PlannedReviewRubric>,
    evidence: ReviewDiffEvidence,
    manifests: List<PlatformManifest>,
    acceptedLanes: List<ReviewRunLane>,
  ): List<PlannedReviewRubric> {
    val qualityOwners =
      acceptedLanes.filter { it.area in UNIVERSAL_CODE_REVIEW_AREAS }
        .distinctBy { it.packSlug to it.area }
    if (qualityOwners.isNotEmpty() &&
      (qualityOwners.size > 1 || planned.count { it.descriptor.area in UNIVERSAL_CODE_REVIEW_AREAS } > 1)
    ) {
      return preserveMultipleAcceptedQualityOwners(planned, evidence, manifests, qualityOwners)
    }
    val accepted = acceptedLanes.lastOrNull { it.area in UNIVERSAL_CODE_REVIEW_AREAS } ?: return planned
    val current = planned.singleOrNull { it.descriptor.area == accepted.area }
    if (current?.descriptor?.skillName == accepted.laneSkillName) return planned
    val owner =
      manifests.singleOrNull { it.slug == accepted.packSlug }
        ?: throw reviewAggregationIntegrityError(
          "accepted lane owner '${accepted.packSlug}/${accepted.laneSkillName}' is unavailable",
          listOf(accepted.laneSkillName),
        )
    if (accepted.area !in owner.declaredCodeReviewAreas) {
      throw reviewAggregationIntegrityError(
        "accepted lane owner '${accepted.packSlug}/${accepted.laneSkillName}' no longer declares '${accepted.area}'",
        listOf(accepted.laneSkillName),
      )
    }
    val paths = current?.descriptor?.ownedPaths ?: evidence.files.map { it.path }
    val descriptor =
      (
        current?.descriptor ?: ReviewLaunchLane(
          skillName = accepted.laneSkillName,
          packSlug = accepted.packSlug,
          area = accepted.area,
          depth = accepted.depth,
          originLayerChain = accepted.originLayerChain,
          required = accepted.required,
          addOns = emptyList(),
          orderIndex = accepted.orderIndex,
          inclusionReason = "accepted review owner",
        )
      ).copy(
        skillName = accepted.laneSkillName,
        packSlug = accepted.packSlug,
        area = accepted.area,
        depth = accepted.depth,
        originLayerChain = accepted.originLayerChain,
        originLayerChains = listOf(accepted.originLayerChain),
        required = accepted.required,
        addOns = ReviewAddonSelectionPolicy.select(owner, accepted.laneSkillName).map { it.slug },
        orderIndex = accepted.orderIndex,
        ownedPaths = paths.distinct().sorted(),
        changedHunkIds = evidence.hunks.filter { it.path in paths }.map { it.hunkId },
      )
    val preserved = toPlannedRubric(evidence, ReviewReconciledLane(descriptor, listOf(descriptor)), manifests)
    return planned.filterNot { it.descriptor.area == accepted.area } + preserved
  }

  private fun preserveMultipleAcceptedQualityOwners(
    planned: List<PlannedReviewRubric>,
    evidence: ReviewDiffEvidence,
    manifests: List<PlatformManifest>,
    qualityOwners: List<ReviewRunLane>,
  ): List<PlannedReviewRubric> {
    val fallbackSlug = ReviewFallbackResolver.resolveOptional(manifests)?.slug
    val fallback =
      planned.filter {
        it.descriptor.area in UNIVERSAL_CODE_REVIEW_AREAS && it.descriptor.packSlug == fallbackSlug
      }
    val base = planned - fallback.toSet()
    return qualityOwners.fold(base) { result, accepted ->
      val current =
        result.singleOrNull {
          it.descriptor.area == accepted.area && it.descriptor.packSlug == accepted.packSlug
        }
      if (fallback.isEmpty() && (current != null || base.any { it.descriptor.area == accepted.area })) {
        result
      } else {
        val seed = current ?: fallback.firstOrNull { it.descriptor.area == accepted.area }
        val paths =
          (
            current?.descriptor?.ownedPaths.orEmpty() +
              fallback.flatMap {
                it.descriptor.ownedPaths
              }
          ).distinct().sorted()
        val scoped =
          seed?.copy(
            descriptor =
              seed.descriptor.copy(
                ownedPaths = paths,
                changedHunkIds =
                  evidence.hunks.filter {
                    it.path in paths
                  }.map { it.hunkId },
              ),
          )
        val preserved = preserveAcceptedQualityOwner(listOfNotNull(scoped), evidence, manifests, listOf(accepted))
        result.filterNot { it === current } + preserved
      }
    }
  }

  private fun toPlannedRubric(
    evidence: ReviewDiffEvidence,
    reconciled: ReviewReconciledLane,
    manifests: List<PlatformManifest>,
  ): PlannedReviewRubric {
    val lane = reconciled.lane
    require(
      reconciled.inputs.filter { it.packSlug == lane.packSlug }.all {
        it.area == lane.area && it.skillName == lane.skillName && it.addOns == lane.addOns
      },
    ) {
      "Conflicting ownership for specialist '${lane.skillName}'."
    }
    val owner = manifests.single { it.slug == lane.packSlug }
    val ownedEvidence =
      evidence.ownedFiles(lane.ownedPaths.toSet()).map {
        ReviewOwnedFileEvidence(it.path, it.changedContent)
      }
    val resolvedOwner = reviewRubricResolver.resolve(owner, ownedEvidence, lane.skillName)
    val resolved =
      resolvedOwner
        .specialists.singleOrNull { it.area == lane.area }
        ?: resolvedOwner
    return PlannedReviewRubric(
      descriptor = lane.copy(addOns = resolved.selectedAddOns),
      rubric = ReviewRubricProjection(lane.skillName, resolved.body, resolved.area ?: lane.area),
      originLayerChains = reconciled.inputs.flatMap { it.originLayerChains }.distinct(),
    )
  }

  private fun horizontalPlannedRubrics(evidence: ReviewDiffEvidence): List<PlannedReviewRubric> {
    val rubric = reviewRubricResolver.resolve(null)
    return listOf(
      PlannedReviewRubric(
        ReviewLaunchLane(
          rubric.rubricId,
          "horizontal",
          rubric.area ?: "generic",
          0,
          listOf("horizontal"),
          true,
          emptyList(),
          0,
          "horizontal base behavior",
          ownedPaths = evidence.hunks.map { it.path }.distinct().sorted(),
          changedHunkIds = evidence.hunks.map { it.hunkId },
        ),
        ReviewRubricProjection(rubric.rubricId, rubric.body, rubric.area),
        workerKind = GENERIC,
      ),
    )
  }

  private fun laneOwnedPaths(
    lane: ReviewLaunchLane,
    files: List<ReviewChangedFileEvidence>,
  ): List<String> =
    files.filter { file ->
      ReviewLaneInclusionPolicy.ownsChangedFile(lane, file.path, file.changedContent)
    }.map { it.path }
}

private const val MAX_DIAGNOSTIC_OWNERS = 12
private const val MAX_DIAGNOSTIC_SLUG_LENGTH = 64
private const val MAX_DIAGNOSTIC_REASON_LENGTH = 1024
