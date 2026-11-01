package skillbill.infrastructure.workflow.review.specialists

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.catchingNonCancellation
import skillbill.infrastructure.skills.scaffold.platformpack.loader.declaredCodeReviewSkillNames
import skillbill.infrastructure.skills.scaffold.platformpack.loader.discoverPlatformPackManifests
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.review.preparation.ReviewAttributionPort
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort
import skillbill.review.plan.ReviewLaunchPlanPolicy
import skillbill.review.plan.model.ReviewLaunchPlan
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileSystemReviewAttribution(
  private val installedCatalog: InstalledPlatformPackCatalogPort,
  private val diagnostics: RuntimeDiagnostics,
) : ReviewAttributionPort {
  override fun routedSkillPlatformSlugs(): Map<String, String> =
    catchingNonCancellation { platformReviewAttributionMappings(installedCatalog.manifests()) }.getOrElse { error ->
      diagnostics.warning("review attribution mappings unavailable; routing falls back to no platform slugs", error)
      emptyMap()
    }

  override fun composedLaunchPlan(routedPackSlug: String): ReviewLaunchPlan {
    val manifests = installedCatalog.manifests()
    if (manifests.none { it.slug == routedPackSlug }) return ReviewLaunchPlan(routedPackSlug, emptyList())

    val selectedAreas = ReviewLaunchPlanPolicy.composedAreas(routedPackSlug, manifests)
    return ReviewLaunchPlanPolicy.flatten(routedPackSlug, manifests, selectedAreas)
  }
}

fun platformReviewAttributionMappings(platformPacksRoot: Path): Map<String, String> {
  if (!Files.isDirectory(platformPacksRoot)) {
    return emptyMap()
  }
  return platformReviewAttributionMappings(discoverPlatformPackManifests(platformPacksRoot))
}

fun platformReviewAttributionMappings(manifests: List<PlatformManifest>): Map<String, String> =
  manifests
    .sortedBy(PlatformManifest::slug)
    .flatMap { manifest ->
      manifest.declaredCodeReviewSkillNames().sorted().map { skillName -> skillName to manifest.slug }
    }
    .toMap()
