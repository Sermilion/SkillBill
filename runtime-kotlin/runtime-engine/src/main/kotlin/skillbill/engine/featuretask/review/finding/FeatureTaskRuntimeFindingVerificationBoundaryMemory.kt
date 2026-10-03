package skillbill.engine.featuretask.review.finding

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeFindingBoundaryMemoryRequest
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeFindingBoundaryMemorySection
import skillbill.ports.goalrunner.planning.GoalPlanningBoundaryBodyResolver
import skillbill.ports.goalrunner.planning.GoalPlanningContextDiscovery
import skillbill.ports.goalrunner.planning.model.GoalPlanningBoundaryBodyResolutionCaps
import skillbill.ports.goalrunner.planning.model.GoalPlanningBoundaryHeading
import skillbill.ports.goalrunner.planning.model.GoalPlanningResolvedBoundaryBodies
import java.nio.file.Path

@Inject
class FeatureTaskRuntimeFindingVerificationBoundaryMemory(
  private val contextDiscovery: GoalPlanningContextDiscovery,
  private val boundaryBodyResolver: GoalPlanningBoundaryBodyResolver,
) {
  fun sectionsForFindings(
    repoRoot: Path,
    requests: List<FeatureTaskRuntimeFindingBoundaryMemoryRequest>,
  ): List<FeatureTaskRuntimeFindingBoundaryMemorySection> =
    requests.map { request ->
      FeatureTaskRuntimeFindingBoundaryMemorySection(
        findingId = request.findingId,
        discovery =
          contextDiscovery.discoverForFindingPaths(
            repoRoot = repoRoot,
            findingPaths = request.findingPaths,
            loudFailOnCapExceeded = false,
          ),
      )
    }

  fun resolveSelectedBodies(
    repoRoot: Path,
    catalog: List<GoalPlanningBoundaryHeading>,
    selectedHeadingIds: List<String>,
  ): GoalPlanningResolvedBoundaryBodies =
    boundaryBodyResolver.resolve(
      repoRoot = repoRoot,
      headingIds = selectedHeadingIds,
      catalogHeadingIds = catalog.map(GoalPlanningBoundaryHeading::headingId).toSet(),
      caps = GoalPlanningBoundaryBodyResolutionCaps.VERIFICATION,
      loudFailOnCapExceeded = true,
    )
}
