package skillbill.infrastructure.skills.scaffold.platformpack.catalog

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.AmbiguousExternalPlatformPackError
import skillbill.error.core.ExternalPlatformPackConfigError
import skillbill.infrastructure.skills.scaffold.platformpack.loader.childDirectories
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformManifest
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformPack
import skillbill.infrastructure.skills.scaffold.platformpack.loader.validatePlatformPack
import skillbill.infrastructure.skills.scaffold.platformpack.loader.validatePlatformPackCompositions
import skillbill.infrastructure.skills.scaffold.platformpack.loader.validatePlatformPackFallbacks
import skillbill.infrastructure.skills.scaffold.platformpack.sourceKind
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.SHELL_CONTRACT_VERSION
import skillbill.install.model.ExternalPlatformPackSource
import skillbill.model.toPath
import skillbill.ports.install.platformpack.ExternalPlatformPackSourceConfigPort
import skillbill.ports.install.platformpack.PlatformPackCatalogPort
import skillbill.ports.install.platformpack.model.ExternalPlatformPackRootRequest
import skillbill.ports.install.platformpack.model.ExternalPlatformPackRootResult
import skillbill.ports.install.platformpack.model.ExternalPlatformPackSourceConfigRequest
import skillbill.ports.install.platformpack.model.PlatformPackCatalogRequest
import skillbill.ports.install.platformpack.model.PlatformPackCatalogResult
import skillbill.ports.repository.toFileLocation
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.policy.platformpack.buildEffectivePlatformPackCatalog
import skillbill.scaffold.policy.platformpack.model.EffectivePlatformPackCatalog
import skillbill.scaffold.policy.platformpack.model.LoadedPlatformPack
import skillbill.scaffold.policy.platformpack.model.PlatformPackSourceKind
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger

data class PlatformPackDiscoveryContext(
  val repoRoot: Path,
  val userHome: Path,
  val environment: Map<String, String> = emptyMap(),
  val enforceContractVersion: Boolean = true,
  val catalogLoader: PlatformPackCatalogLoader? = null,
  val enforceGovernedReviewStructure: Boolean = true,
)

@Inject
class PlatformPackCatalogLoader(
  private val externalPlatformPackSourceConfigPort: ExternalPlatformPackSourceConfigPort,
) : PlatformPackCatalogPort {
  override fun loadEffectiveCatalog(request: PlatformPackCatalogRequest): PlatformPackCatalogResult =
    PlatformPackCatalogResult(
      catalog =
        loadEffectiveCatalog(
          PlatformPackDiscoveryContext(
            repoRoot = request.repoRoot,
            userHome = request.userHome,
            environment = request.environment,
            enforceContractVersion = request.enforceContractVersion,
          ),
        ),
    )

  fun loadEffectiveCatalog(context: PlatformPackDiscoveryContext): EffectivePlatformPackCatalog =
    loadEffectiveCatalogInternal(context)

  private fun loadEffectiveCatalogInternal(
    context: PlatformPackDiscoveryContext,
    pendingExternalRoot: Path? = null,
  ): EffectivePlatformPackCatalog {
    val external = externalPacksIncludingPending(context, pendingExternalRoot)
    val shadowedSlugs = external.map { pack -> pack.manifest.slug }.toSet()
    val bundled = loadBundledPacks(context, shadowedSlugs)
    val catalog =
      buildEffectivePlatformPackCatalog(
        bundled = bundled,
        external = external,
        bundledSlugs = bundledPackSlugs(context),
      )
    val manifests = catalog.manifests
    validatePlatformPackCompositions(manifests, catalog.manifestsBySlug)
    validatePlatformPackFallbacks(manifests)
    manifests.forEach { pack ->
      validatePlatformPack(
        pack,
        SHELL_CONTRACT_VERSION,
        context.enforceContractVersion,
        context.enforceGovernedReviewStructure,
      )
    }
    return catalog
  }

  fun loadEffectiveManifests(context: PlatformPackDiscoveryContext): List<PlatformManifest> =
    loadEffectiveCatalog(context).manifests

  override fun validateExternalPackRoot(request: ExternalPlatformPackRootRequest): ExternalPlatformPackRootResult {
    val catalog = loadEffectiveCatalog(request.catalog).catalog
    val pack = loadPlatformPack(request.packRoot, catalog.manifestsBySlug)
    return ExternalPlatformPackRootResult(pack.slug)
  }

  override fun assertRegistrableExternalPack(
    request: ExternalPlatformPackRootRequest,
  ): ExternalPlatformPackRootResult {
    val normalized = request.packRoot.toAbsolutePath().normalize()
    if (!Files.isDirectory(normalized)) {
      throw ExternalPlatformPackConfigError(
        "Pack path '$normalized' does not resolve to an existing directory.",
      )
    }
    val catalog =
      loadEffectiveCatalogInternal(
        PlatformPackDiscoveryContext(
          repoRoot = request.catalog.repoRoot,
          userHome = request.catalog.userHome,
          environment = request.catalog.environment,
          enforceContractVersion = request.catalog.enforceContractVersion,
        ),
        pendingExternalRoot = normalized,
      )
    val incoming = loadPlatformPack(normalized, catalog.manifestsBySlug)
    val billSharedRoot = request.catalog.repoRoot.toAbsolutePath().normalize().resolve(".bill-shared")
    assertExternalPackContentPresent(incoming)
    assertExternalPlatformPackDeclaredReads(incoming, billSharedRoot)
    val conflict =
      catalog.entries.any { entry ->
        entry.loaded.sourceKind == PlatformPackSourceKind.EXTERNAL &&
          entry.loaded.manifest.slug == incoming.slug &&
          entry.loaded.canonicalRoot != normalized.toString()
      }
    if (conflict) {
      throw AmbiguousExternalPlatformPackError(
        "External platform pack slug '${incoming.slug}' is already registered at a different root.",
      )
    }
    return ExternalPlatformPackRootResult(incoming.slug)
  }

  private fun loadBundledPacks(
    context: PlatformPackDiscoveryContext,
    shadowedSlugs: Set<String>,
  ): List<LoadedPlatformPack> {
    val packsRoot = context.repoRoot.toAbsolutePath().normalize().resolve("platform-packs")
    if (!Files.isDirectory(packsRoot)) {
      return emptyList()
    }
    return childDirectories(packsRoot).map { packRoot ->
      if (packRoot.fileName.toString() in shadowedSlugs) {
        return@map null
      }
      val manifest = loadPlatformManifest(packRoot, context.enforceContractVersion)
      LoadedPlatformPack(
        manifest = manifest,
        sourceKind = PlatformPackSourceKind.BUNDLED,
        canonicalRoot = packRoot.toAbsolutePath().normalize().toString(),
      )
    }.filterNotNull()
  }

  private fun bundledPackSlugs(context: PlatformPackDiscoveryContext): Set<String> {
    val packsRoot = context.repoRoot.toAbsolutePath().normalize().resolve("platform-packs")
    if (!Files.isDirectory(packsRoot)) {
      return emptySet()
    }
    return childDirectories(packsRoot).map { packRoot -> packRoot.fileName.toString() }.toSet()
  }

  private fun bundledPackDirectoryExists(
    context: PlatformPackDiscoveryContext,
    slug: String,
  ): Boolean {
    if (slug.isEmpty()) return false
    val packsRoot = context.repoRoot.toAbsolutePath().normalize().resolve("platform-packs")
    return Files.isDirectory(packsRoot.resolve(slug))
  }

  private fun externalPacksIncludingPending(
    context: PlatformPackDiscoveryContext,
    pendingExternalRoot: Path?,
  ): List<LoadedPlatformPack> {
    val registered = loadExternalPacks(context)
    val pendingRoot = pendingExternalRoot?.toAbsolutePath()?.normalize() ?: return registered
    val pending = loadExternalPack(ExternalPlatformPackSource(pendingRoot.toFileLocation()), context)
    val conflict =
      registered.any { pack ->
        pack.manifest.slug == pending.manifest.slug && pack.canonicalRoot != pending.canonicalRoot
      }
    if (conflict) {
      throw AmbiguousExternalPlatformPackError(
        "External platform pack slug '${pending.manifest.slug}' is already registered at a different root.",
      )
    }
    return if (registered.any { pack -> pack.canonicalRoot == pending.canonicalRoot }) {
      registered
    } else {
      registered + pending
    }
  }

  private fun loadExternalPacks(context: PlatformPackDiscoveryContext): List<LoadedPlatformPack> {
    val sources =
      externalPlatformPackSourceConfigPort.readExternalPlatformPackSources(
        ExternalPlatformPackSourceConfigRequest(
          userHome = context.userHome,
          environment = context.environment,
        ),
      ).sources
    return sources.mapNotNull { source ->
      val canonicalRoot = source.path.toPath().toAbsolutePath().normalize()
      val slug = canonicalRoot.fileName?.toString().orEmpty()
      if (!Files.isDirectory(canonicalRoot) && !bundledPackDirectoryExists(context, slug)) {
        catalogLog.warning(
          "external platform pack source skipped: seam=PlatformPackCatalogLoader.loadExternalPacks " +
            "pack=$slug used=omitted expected=$canonicalRoot " +
            "cause=the external directory is absent and no bundled pack directory exists",
        )
        null
      } else if (!Files.isDirectory(canonicalRoot)) {
        throw ExternalPlatformPackConfigError(
          "External platform pack source '$canonicalRoot' does not resolve to an existing directory.",
        )
      } else {
        loadExternalPack(source, context)
      }
    }
  }

  private fun loadExternalPack(
    source: ExternalPlatformPackSource,
    context: PlatformPackDiscoveryContext,
  ): LoadedPlatformPack {
    val canonicalRoot = source.path.toPath().toAbsolutePath().normalize()
    val manifest = loadPlatformManifest(canonicalRoot, context.enforceContractVersion)
    val billSharedRoot = context.repoRoot.toAbsolutePath().normalize().resolve(".bill-shared")
    assertExternalPackContentPresent(manifest)
    assertExternalPlatformPackDeclaredReads(manifest, billSharedRoot)
    assertExternalPlatformPackTreeReads(canonicalRoot, billSharedRoot)
    return LoadedPlatformPack(
      manifest = manifest,
      sourceKind = PlatformPackSourceKind.EXTERNAL,
      canonicalRoot = canonicalRoot.toString(),
    )
  }

  private companion object {
    val catalogLog: Logger = Logger.getLogger("skillbill.scaffold.platformpack.PlatformPackCatalogLoader")
  }
}
