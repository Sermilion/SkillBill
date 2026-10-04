package skillbill.infrastructure.skills.install.nativeagent.link

import skillbill.error.core.ExternalPlatformPackFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.externalPlatformPackPublish
import skillbill.infrastructure.host.jvm.atomicMoveReplacing
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformManifest
import skillbill.model.toPath
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.coroutines.cancellation.CancellationException

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
    val failure = runCatching { stageReviewCatalogPack(source, staging) }.exceptionOrNull() ?: return@forEach
    throw reviewCatalogStageFailure(source, failure)
  }
}

internal fun retainedCatalogFailure(error: Throwable): SkillBillRuntimeException {
  if (error is SkillBillRuntimeException && error.code == ExternalPlatformPackFailureCode.PUBLISH) return error
  return externalPlatformPackPublish(
    "Installed review catalog was not promoted; the previous catalog remains.",
    error,
  )
}

private fun reviewCatalogStageFailure(
  source: Path,
  error: Throwable,
): Throwable {
  if (error is CancellationException ||
    (error as? SkillBillRuntimeException)?.code == ExternalPlatformPackFailureCode.PUBLISH
  ) {
    return error
  }
  return externalPlatformPackPublish(
    "Installed review catalog for platform pack '${source.fileName}' was not promoted; " +
      "the previous catalog remains.",
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
    runCatching {
      if (Files.exists(catalogRoot, LinkOption.NOFOLLOW_LINKS)) {
        atomicMoveReplacing(catalogRoot, superseded)
        supersededMoved = true
      }
      atomicMoveReplacing(staging, catalogRoot)
      deleteRecursively(superseded)
    }
  publishResult.exceptionOrNull()?.let { error ->
    if (supersededMoved && Files.exists(superseded, LinkOption.NOFOLLOW_LINKS)) {
      runCatching {
        if (Files.exists(catalogRoot, LinkOption.NOFOLLOW_LINKS)) {
          deleteRecursively(catalogRoot)
        }
        atomicMoveReplacing(superseded, catalogRoot)
      }.exceptionOrNull()?.let(error::addSuppressed)
    }
    publishResult.getOrThrow()
  }
}
