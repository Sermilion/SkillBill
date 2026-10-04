package skillbill.infrastructure.skills.externaladdon

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.externalPlatformPackOverlay
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackCatalogLoader
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackDiscoveryContext
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformManifest
import skillbill.infrastructure.skills.scaffold.platformpack.manifest.declaredSkillRelativeDirs
import skillbill.infrastructure.skills.scaffold.platformpack.sourceKind
import skillbill.install.model.ExternalAddonSource
import skillbill.model.toPath
import skillbill.ports.install.addon.ExternalAddonOverlayPort
import skillbill.ports.install.addon.model.AppliedExternalAddonSource
import skillbill.ports.install.addon.model.ExternalAddonOverlayRequest
import skillbill.ports.install.addon.model.ExternalAddonOverlayResult
import skillbill.ports.install.addon.model.SkippedExternalAddonSource
import skillbill.ports.install.platformpack.ExternalPlatformPackSourceConfigPort
import skillbill.ports.install.platformpack.model.ExternalPlatformPackSourceConfigRequest
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.policy.platformpack.model.LoadedPlatformPack
import skillbill.scaffold.policy.platformpack.model.PlatformPackSourceKind
import java.nio.file.Files
import java.nio.file.Path

internal const val ADDONS_DIR = "addons"
internal const val MANIFEST_FILE = "platform.yaml"
internal const val SOURCE_MANIFEST_FILE = "addon-manifest.yaml"
internal const val MANIFEST_TEMP_SUFFIX = ".platform.yaml.tmp"
internal val POINTER_NAME_PATTERN = Regex("^[^/\\\\]+\\.md$")
internal val ADDON_SLUG_PATTERN = Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*\$")
internal val POINTER_ENTRY_KEYS = setOf("name", "target")
internal val ADDON_ENTRY_KEYS = setOf("slug", "entrypoint", "companion_pointers", "activation", "specialist_areas")

@Inject
class FileSystemExternalAddonOverlay(
  private val externalPlatformPackSourceConfigPort: ExternalPlatformPackSourceConfigPort,
  private val platformPackCatalogLoader: PlatformPackCatalogLoader,
) : ExternalAddonOverlayPort {
  override fun applyOverlay(request: ExternalAddonOverlayRequest): ExternalAddonOverlayResult {
    if (request.sources.isEmpty()) {
      return ExternalAddonOverlayResult(touched = false)
    }
    val context = overlayPlanningContext(request, externalPlatformPackSourceConfigPort, platformPackCatalogLoader)
    val skipped = mutableListOf<SkippedExternalAddonSource>()
    val plans = mutableListOf<SourcePlan>()
    val collisionIndex = CollisionIndex.empty()
    for (source in request.sources) {
      when (val outcome = planOverlaySource(source, context, collisionIndex)) {
        is OverlaySourceOutcome.Skip -> skipped += outcome.skipped
        is OverlaySourceOutcome.Ready -> plans += outcome.plan
      }
    }
    plans.forEach(::applyPlan)
    return ExternalAddonOverlayResult(
      appliedSources =
        plans.map { plan ->
          AppliedExternalAddonSource(
            platform = plan.platform,
            sourcePath = plan.sourcePath,
            addons = plan.copiedFiles.values.map { it.fileName.toString() }.sorted(),
          )
        },
      skippedSources = skipped,
      touched = plans.isNotEmpty(),
    )
  }
}

private data class OverlayPlanningContext(
  val platformPacksRoot: Path,
  val effectiveBySlug: Map<String, LoadedPlatformPack>,
)

private sealed interface OverlaySourceOutcome {
  data class Skip(val skipped: SkippedExternalAddonSource) : OverlaySourceOutcome

  data class Ready(val plan: SourcePlan) : OverlaySourceOutcome
}

private fun overlayPlanningContext(
  request: ExternalAddonOverlayRequest,
  packSources: ExternalPlatformPackSourceConfigPort,
  catalogLoader: PlatformPackCatalogLoader,
): OverlayPlanningContext {
  val platformPacksRoot = request.platformPacksRoot.toAbsolutePath().normalize()
  val repoRoot = request.repoRoot?.toAbsolutePath()?.normalize() ?: platformPacksRoot.parent
  val userHome = request.userHome
  val registeredPacks =
    if (userHome == null) {
      emptyList()
    } else {
      packSources.readExternalPlatformPackSources(
        ExternalPlatformPackSourceConfigRequest(userHome = userHome, environment = request.environment),
      ).sources
    }
  val effectiveBySlug =
    if (registeredPacks.isEmpty() || repoRoot == null || userHome == null) {
      emptyMap()
    } else {
      catalogLoader.loadEffectiveCatalog(
        PlatformPackDiscoveryContext(
          repoRoot = repoRoot,
          userHome = userHome,
          environment = request.environment,
          catalogLoader = catalogLoader,
        ),
      ).entries.associate { entry -> entry.loaded.manifest.slug to entry.loaded }
    }
  return OverlayPlanningContext(platformPacksRoot, effectiveBySlug)
}

private fun planOverlaySource(
  source: ExternalAddonSource,
  context: OverlayPlanningContext,
  collisionIndex: CollisionIndex,
): OverlaySourceOutcome {
  val externalSkip = skipUninstalledExternalPack(source, context)
  if (externalSkip != null) return OverlaySourceOutcome.Skip(externalSkip)
  val packRoot = context.platformPacksRoot.resolve(source.platform)
  if (!Files.isRegularFile(packRoot.resolve(MANIFEST_FILE))) {
    return OverlaySourceOutcome.Skip(
      SkippedExternalAddonSource(
        platform = source.platform,
        sourcePath = source.path.toPath(),
        reason = "platform pack '${source.platform}' is not installed; skipping external addon source.",
      ),
    )
  }
  val installed = loadPlatformManifest(packRoot)
  val effective = context.effectiveBySlug[source.platform]
  requireEffectiveDeclaredDirs(source.platform, effective, installed)
  collisionIndex.mergeInstalled(installed.pointers, installed.addonUsage)
  val plan = validateAndPlan(source, installed, collisionIndex)
  requirePlannedConsumerDirs(source.platform, effective, plan)
  return OverlaySourceOutcome.Ready(plan)
}

private fun skipUninstalledExternalPack(
  source: ExternalAddonSource,
  context: OverlayPlanningContext,
): SkippedExternalAddonSource? {
  val effective = context.effectiveBySlug[source.platform]
  if (effective?.sourceKind != PlatformPackSourceKind.EXTERNAL) return null
  val installedRoot = context.platformPacksRoot.resolve(source.platform).toAbsolutePath().normalize()
  if (installedRoot == Path.of(effective.canonicalRoot)) {
    throw externalPlatformPackOverlay(
      "External addon overlay for '${source.platform}' refuses to modify the authored pack root.",
    )
  }
  if (Files.isRegularFile(installedRoot.resolve(MANIFEST_FILE))) return null
  return SkippedExternalAddonSource(
    platform = source.platform,
    sourcePath = source.path.toPath(),
    reason =
      "platform pack '${source.platform}' is not installed in the managed tree; " +
        "skipping external addon source.",
  )
}

private fun requireEffectiveDeclaredDirs(
  platform: String,
  effective: LoadedPlatformPack?,
  installed: PlatformManifest,
) {
  if (effective?.sourceKind != PlatformPackSourceKind.EXTERNAL) return
  val allowed = effective.manifest.declaredSkillRelativeDirs()
  if (allowed.containsAll(installed.declaredSkillRelativeDirs())) return
  throw externalPlatformPackOverlay(
    "External addon overlay for '$platform' found installed consumer paths that are absent " +
      "from the effective pack.",
  )
}

private fun requirePlannedConsumerDirs(
  platform: String,
  effective: LoadedPlatformPack?,
  plan: SourcePlan,
) {
  if (effective?.sourceKind != PlatformPackSourceKind.EXTERNAL) return
  val allowed = effective.manifest.declaredSkillRelativeDirs()
  val referenced = plan.pointersToAppend.keys + plan.addonsToAppend.keys
  if (referenced.all { dir -> dir in allowed }) return
  throw externalPlatformPackOverlay(
    "External addon overlay for '$platform' references a consumer path that exists only on " +
      "the shadowed bundled pack.",
  )
}
