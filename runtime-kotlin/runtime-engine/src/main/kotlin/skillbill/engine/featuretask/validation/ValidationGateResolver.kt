package skillbill.engine.featuretask.validation

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.validation.model.ValidationGateResolution
import skillbill.error.core.ShellContentContractException
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort
import skillbill.review.plan.ReviewFallbackResolver
import skillbill.review.plan.ReviewStackRouting
import skillbill.review.plan.model.ReviewRoutingChangedFile
import skillbill.review.plan.model.ReviewStackRoutingResult
import skillbill.scaffold.model.PlatformManifest

@Inject
class ValidationGateResolver(
  private val installedCatalog: InstalledPlatformPackCatalogPort,
) {
  fun resolve(changedPaths: List<String>): ValidationGateResolution {
    val manifests =
      try {
        installedCatalog.manifests()
      } catch (e: ShellContentContractException) {
        return ValidationGateResolution.Incompatible(
          "Installed platform pack discovery failed: ${e.message ?: e.javaClass.simpleName}. " +
            "Repair the installed platform packs before running validation.",
        )
      }
    if (manifests.isEmpty()) {
      return ValidationGateResolution.Absent(null)
    }
    val routing =
      ReviewStackRouting.route(
        manifests,
        changedPaths.map { ReviewRoutingChangedFile(it, "") },
      )
    val candidates = dominantPacks(manifests, routing)
    val dominant =
      candidates.singleOrNull()
        ?: return ValidationGateResolution.Incompatible(
          if (candidates.isEmpty()) {
            "Cannot select a validation gate: the changed-file scope has no routable platform evidence."
          } else {
            "Cannot select a validation gate: the changed-file scope is equally owned by " +
              "${candidates.map { it.slug }.sorted().joinToString(", ")}."
          },
        )
    val declaration = dominant.validationGate
    return if (declaration != null) {
      ValidationGateResolution.Declared(dominant.slug, declaration)
    } else {
      ValidationGateResolution.Absent(dominant.slug)
    }
  }

  private fun dominantPacks(
    manifests: List<PlatformManifest>,
    routing: ReviewStackRoutingResult,
  ): List<PlatformManifest> {
    val bySlug = manifests.associateBy { it.slug }
    val routed = routing.routedSlugs.mapNotNull(bySlug::get)
    val fallback = ReviewFallbackResolver.resolveOptional(manifests)
    val concrete = routed.filterNot { it.slug == fallback?.slug }.ifEmpty { routed }
    val largestScope = concrete.maxOfOrNull { routing.ownedPathsBySlug[it.slug]?.size ?: 0 } ?: return emptyList()
    val leaders = concrete.filter { routing.ownedPathsBySlug[it.slug]?.size == largestScope }
    val composedBaselines = leaders.flatMap { it.codeReviewComposition?.baselineLayers.orEmpty() }.map { it.platform }
    return leaders.filterNot { it.slug in composedBaselines }
  }
}
