package skillbill.infrastructure.skills.install.nativeagent.link

import skillbill.contracts.config.ExternalPlatformPackTelemetryPayloadKeys
import skillbill.error.core.ExternalPlatformPackPublishError
import skillbill.error.core.catchingNonCancellation
import skillbill.infrastructure.host.jvm.atomicMoveReplacing
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformManifest
import skillbill.infrastructure.skills.scaffold.platformpack.sourceKind
import skillbill.model.toPath
import skillbill.scaffold.policy.platformpack.externalPlatformPackTelemetryPayload
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal fun stageReviewCatalogPacks(
  platformPacksRoot: Path,
  selectedPlatforms: List<String>?,
  staging: Path,
  effectivePackRoots: List<Path> = emptyList(),
) {
  val selected = selectedPlatforms?.toSet()
  val desiredPacks =
    if (effectivePackRoots.isNotEmpty()) {
      effectivePackRoots.filter { root ->
        selected == null || root.fileName.toString() in selected
      }
    } else {
      Files.list(platformPacksRoot).use { packs ->
        packs.filter(Files::isDirectory)
          .filter { selected == null || it.fileName.toString() in selected }
          .toList()
      }
    }
  desiredPacks.forEach { source ->
    val failure =
      catchingNonCancellation { stageReviewCatalogPack(source, staging) }.exceptionOrNull() ?: return@forEach
    throw reviewCatalogStageFailure(platformPacksRoot, source, failure)
  }
}

internal fun retainedCatalogFailure(
  error: Throwable,
  platformPacksRoot: Path? = null,
  effectivePackRoots: List<Path> = emptyList(),
): ExternalPlatformPackPublishError {
  if (error is ExternalPlatformPackPublishError) return error
  val source = effectivePackRoots.singleOrNull()
  val sourceKind =
    source?.let { packRoot ->
      platformPacksRoot?.let { bundledRoot -> sourceKind(bundledRoot, packRoot) }
    }
  val payload =
    externalPlatformPackTelemetryPayload(
      error,
      slug = source?.fileName?.toString(),
      sourceKind = sourceKind,
    ).toMutableMap()
  payload[ExternalPlatformPackTelemetryPayloadKeys.RECOVERY] = "previous_catalog_retained"
  return ExternalPlatformPackPublishError(
    "Installed review catalog was not promoted; the previous catalog remains.",
    payload,
    error,
  )
}

private fun reviewCatalogStageFailure(
  platformPacksRoot: Path,
  source: Path,
  error: Throwable,
): Throwable {
  if (error is ExternalPlatformPackPublishError) return error
  val payload =
    externalPlatformPackTelemetryPayload(
      error,
      slug = source.fileName.toString(),
      sourceKind = sourceKind(platformPacksRoot, source),
    ).toMutableMap()
  payload[ExternalPlatformPackTelemetryPayloadKeys.RECOVERY] = "previous_catalog_retained"
  return ExternalPlatformPackPublishError(
    "Installed review catalog for platform pack '${source.fileName}' was not promoted; " +
      "the previous catalog remains.",
    payload,
    error,
  )
}

private fun stageReviewCatalogPack(
  source: Path,
  staging: Path,
) {
  val stagedPack = staging.resolve(source.fileName.toString())
  val manifest = loadPlatformManifest(source)
  val runtimeFiles =
    buildList {
      add(source.resolve("platform.yaml"))
      manifest.qaCapability?.guidanceFile?.let { guidance -> add(source.resolve(guidance)) }
      manifest.declaredFiles.baseline?.let { baseline -> add(baseline.toPath()) }
      addAll(manifest.declaredFiles.areas.values.map { area -> area.toPath() })
      val declaredAddons =
        manifest.addonUsage.flatMap { it.addons } +
          manifest.featureAddonUsage.flatMap { it.addons }
      declaredAddons.forEach { addon ->
        add(source.resolve("addons").resolve(addon.entrypoint))
        addon.companionPointers.forEach { pointer -> add(source.resolve("addons").resolve(pointer)) }
      }
    }.distinct()
  runtimeFiles.forEach { path ->
    val relative = source.relativize(path.toAbsolutePath().normalize())
    require(!relative.startsWith("..")) {
      "Installed review catalog path escapes platform pack '${manifest.slug}'."
    }
    val realSource = source.toRealPath()
    val realFile = path.toRealPath()
    require(realFile.startsWith(realSource)) {
      "Installed review catalog path escapes platform pack '${manifest.slug}'."
    }
    val target = stagedPack.resolve(relative).normalize()
    require(target.startsWith(staging)) { "Installed review catalog path escapes its cache root." }
    require(Files.isRegularFile(path) && !Files.isSymbolicLink(path)) {
      "Installed review catalog source must be a regular manifest-declared file: '$path'."
    }
    target.parent?.let(Files::createDirectories)
    Files.copy(path, target, REPLACE_EXISTING)
  }
}

internal fun journalReviewCatalogSwap(
  catalogRoot: Path,
  staging: Path,
  journal: ProviderMutationJournal,
) {
  if (Files.exists(catalogRoot, LinkOption.NOFOLLOW_LINKS)) {
    Files.walk(catalogRoot).use { paths -> paths.sorted().forEach(journal::beforeMutation) }
  }
  Files.walk(staging).use { paths ->
    paths.sorted().forEach { staged ->
      journal.beforeMutation(catalogRoot.resolve(staging.relativize(staged)))
    }
  }
}

internal fun swapReviewCatalogIntoPlace(
  catalogRoot: Path,
  staging: Path,
  superseded: Path,
) {
  var supersededMoved = false
  val publishResult =
    catchingNonCancellation {
      if (Files.exists(catalogRoot, LinkOption.NOFOLLOW_LINKS)) {
        atomicMoveReplacing(catalogRoot, superseded)
        supersededMoved = true
      }
      atomicMoveReplacing(staging, catalogRoot)
      deleteRecursively(superseded)
    }
  publishResult.exceptionOrNull()?.let { error ->
    if (supersededMoved && Files.exists(superseded, LinkOption.NOFOLLOW_LINKS)) {
      catchingNonCancellation {
        if (Files.exists(catalogRoot, LinkOption.NOFOLLOW_LINKS)) {
          deleteRecursively(catalogRoot)
        }
        atomicMoveReplacing(superseded, catalogRoot)
      }.exceptionOrNull()?.let(error::addSuppressed)
    }
    publishResult.getOrThrow()
  }
}
