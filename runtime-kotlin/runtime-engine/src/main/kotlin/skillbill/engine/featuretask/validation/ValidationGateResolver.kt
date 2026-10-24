package skillbill.engine.featuretask.validation

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.validation.model.ValidationGateResolution
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure
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
  fun resolve(changedPaths: List<String>): ValidationGateResolution =
    withManifests { manifests -> resolution(manifests, dominantPacks(manifests, changedPaths)) }

  fun declaredCandidates(): List<ValidationGateResolution> =
    installedCatalog.manifests().map { manifest ->
      manifest.validationGate?.let { ValidationGateResolution.Declared(manifest.slug, it) }
        ?: ValidationGateResolution.Absent(manifest.slug)
    } + ValidationGateResolution.Absent(null)

  private fun withManifests(resolve: (List<PlatformManifest>) -> ValidationGateResolution): ValidationGateResolution {
    val manifests =
      try {
        installedCatalog.manifests()
      } catch (e: SkillBillRuntimeException) {
        e.rethrowUnless(e.isShellContentContractFailure())
        return ValidationGateResolution.Incompatible(
          "Installed platform pack discovery failed: ${e.message ?: e.javaClass.simpleName}. " +
            "Repair the installed platform packs before running validation.",
        )
      }
    if (manifests.isEmpty()) {
      return ValidationGateResolution.Absent(null)
    }
    return resolve(manifests)
  }

  private fun resolution(
    manifests: List<PlatformManifest>,
    candidates: List<PlatformManifest>,
  ): ValidationGateResolution {
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
      ValidationGateResolution.Absent(
        dominant.slug.takeUnless { it == ReviewFallbackResolver.resolveOptional(manifests)?.slug },
      )
    }
  }
}

internal fun routedConcretePacks(
  manifests: List<PlatformManifest>,
  paths: List<String>,
): List<PlatformManifest> = concretePacks(manifests, route(manifests, paths))

internal fun dominantPacks(
  manifests: List<PlatformManifest>,
  paths: List<String>,
): List<PlatformManifest> {
  val routing = route(manifests, paths)
  val concrete = concretePacks(manifests, routing)
  val scopeBySlug =
    concrete.associateWith { pack ->
      val baselines = pack.codeReviewComposition?.baselineLayers.orEmpty().map { it.platform }
      (listOf(pack.slug) + baselines).flatMap { routing.ownedPathsBySlug[it].orEmpty() }.toSet().size
    }
  val largestScope = scopeBySlug.values.maxOrNull() ?: return emptyList()
  return scopeBySlug.filterValues { it == largestScope }.keys.toList()
}

private fun route(
  manifests: List<PlatformManifest>,
  paths: List<String>,
): ReviewStackRoutingResult = ReviewStackRouting.route(manifests, paths.map { ReviewRoutingChangedFile(it, "") })

private fun concretePacks(
  manifests: List<PlatformManifest>,
  routing: ReviewStackRoutingResult,
): List<PlatformManifest> {
  val bySlug = manifests.associateBy { it.slug }
  val routed = routing.routedSlugs.mapNotNull(bySlug::get)
  val fallback = ReviewFallbackResolver.resolveOptional(manifests)
  val concrete = routed.filterNot { it.slug == fallback?.slug }.ifEmpty { routed }
  val absorbed =
    concrete.flatMap { pack -> pack.codeReviewComposition?.baselineLayers.orEmpty().map { it.platform } }.toSet()
  return concrete.filterNot { it.slug in absorbed }
}
