package skillbill.infrastructure.skills.install.nativeagent.link

import skillbill.error.core.catchingNonCancellation
import skillbill.infrastructure.skills.install.nativeagent.InstallNativeAgentResult
import skillbill.infrastructure.skills.install.nativeagent.inventory.NativeAgentLinkInventory
import skillbill.infrastructure.skills.install.nativeagent.inventory.NativeAgentLinkInventoryEntry
import skillbill.infrastructure.skills.install.nativeagent.inventory.NativeAgentLinkInventoryReconcileRequest
import skillbill.infrastructure.skills.install.symlink.resolveSymlinkTarget
import skillbill.infrastructure.skills.nativeagent.NativeAgentOperations
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentInstallRenderOverrides
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentInstallRenderRequest
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentInstallRenderResult
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackCatalogLoader
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackDiscoveryContext
import skillbill.infrastructure.skills.scaffold.platformpack.loader.childDirectories
import skillbill.install.model.AgentTarget
import skillbill.model.toPath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

internal fun linkProviderAgentsBody(args: NativeAgentLinkProviderBodyArgs): NativeAgentLinkOutcome {
  val generated =
    NativeAgentOperations.renderInstallArtifacts(
      NativeAgentInstallRenderRequest(
        platformPacksRoot = args.request.platformPacksRoot,
        skillsRoot = args.request.skillsRoot,
        selectedPlatforms = args.request.selectedPlatforms,
        provider = args.provider,
        home = args.resolvedHome,
        compositionContext = args.compositionContext,
        overrides =
          NativeAgentInstallRenderOverrides(
            cacheRoot = args.request.overrides.installCacheRoot,
            sourceRoots = args.request.overrides.sourceRoots,
            beforeMutation = args.journal::beforeMutation,
            afterTemporaryCreation = args.journal::afterTemporaryCreation,
          ),
      ),
    )
  val managedRoots = listOfNotNull(generated.cacheRoot, args.request.overrides.legacyManagedRoot)
  publishInstalledReviewCatalog(
    args.request.platformPacksRoot,
    args.request.selectedPlatforms,
    generated.cacheRoot,
    args.journal,
    args.effectivePackRoots,
  )
  val linkResults = linkGeneratedNativeAgentFiles(args, generated, managedRoots)
  val desired =
    desiredNativeAgentInventory(
      provider = args.provider,
      targets = args.targets,
      generated = generated,
      linked = linkResults.linked,
      validationRoot = args.validationRoot,
    )
  desired.forEach(::verifyInstalledNativeAgent)
  NativeAgentLinkInventory.reconcile(
    NativeAgentLinkInventoryReconcileRequest(
      home = args.resolvedHome,
      provider = args.provider.name.lowercase(),
      desired = desired,
      managedRoots = managedRoots,
      sourceRoot = args.validationRoot,
      environment = args.request.environment,
      beforeMutation = args.journal::beforeMutation,
      afterTemporaryCreation = args.journal::afterTemporaryCreation,
    ),
  )
  return NativeAgentLinkOutcome(linkResults.linked, linkResults.skipped)
}

private data class NativeAgentFileLinkResults(val linked: List<Path>, val skipped: List<NativeAgentSkippedLink>)

private fun linkGeneratedNativeAgentFiles(
  args: NativeAgentLinkProviderBodyArgs,
  generated: NativeAgentInstallRenderResult,
  managedRoots: List<Path>,
): NativeAgentFileLinkResults {
  val linked = mutableListOf<Path>()
  val skipped = mutableListOf<NativeAgentSkippedLink>()
  val artifactsByPath = generated.artifacts.associateBy { it.path }
  args.targets.forEach { target ->
    generated.generatedFiles.forEach { file ->
      when (
        val result =
          installNativeAgentFile(
            file,
            target,
            managedSourceRoots = managedRoots,
            ownership =
              NativeAgentLinkOwnership(
                args.resolvedHome,
                args.provider,
                requireNotNull(artifactsByPath[file]).logicalName,
              ),
            beforeMutation = args.journal::beforeMutation,
          )
      ) {
        is InstallNativeAgentResult.Linked -> linked.add(result.link)
        is InstallNativeAgentResult.Skipped -> skipped.add(NativeAgentSkippedLink(result.link, result.reason))
      }
    }
  }
  return NativeAgentFileLinkResults(linked, skipped)
}

internal fun desiredNativeAgentInventory(
  provider: NativeAgentProvider,
  targets: List<AgentTarget>,
  generated: NativeAgentInstallRenderResult,
  linked: List<Path>,
  validationRoot: Path,
): List<NativeAgentLinkInventoryEntry> {
  val linkedPaths = linked.toSet()
  return generated.artifacts.flatMap { artifact ->
    targets.mapNotNull { target ->
      val agentDir = target.path.toPath()
      val installedPath = agentDir.resolve(artifact.path.fileName)
      val isOurs =
        installedPath in linkedPaths ||
          (Files.isSymbolicLink(installedPath) && resolveSymlinkTarget(installedPath) == artifact.path)
      if (!isOurs) return@mapNotNull null
      NativeAgentLinkInventoryEntry(
        logicalName = artifact.logicalName,
        provider = provider.name.lowercase(),
        installedPath = installedPath,
        cacheTargetPath = artifact.path,
        contentDigest = artifact.contentDigest,
        sourceRoot = validationRoot,
      )
    }
  }
}

internal fun effectivePackRootsForInstall(
  platformPacksRoot: Path,
  userHome: Path,
  environment: Map<String, String>,
  selectedPlatforms: List<String>?,
  catalogLoader: PlatformPackCatalogLoader? = null,
): List<Path> {
  val repoRoot = platformPacksRoot.toAbsolutePath().normalize().parent ?: return emptyList()
  val selected = selectedPlatforms?.toSet()
  val loader =
    catalogLoader ?: return childDirectories(platformPacksRoot)
      .map { packRoot -> packRoot.toAbsolutePath().normalize() }
      .filter { packRoot -> selected == null || packRoot.fileName.toString() in selected }
  return loader.loadEffectiveCatalog(
    PlatformPackDiscoveryContext(
      repoRoot = repoRoot,
      userHome = userHome,
      environment = environment,
      catalogLoader = loader,
    ),
  ).entries
    .map { entry -> entry.loaded }
    .filter { loaded -> selected == null || loaded.manifest.slug in selected }
    .map { loaded -> Path.of(loaded.canonicalRoot) }
}

internal fun linkProviderAgentsWithJournal(
  journal: ProviderMutationJournal,
  block: () -> NativeAgentLinkOutcome,
): NativeAgentLinkOutcome =
  try {
    catchingNonCancellation(block).onFailure { error ->
      journal.restore().forEach { suppressed -> error.addSuppressed(suppressed) }
    }.getOrThrow()
  } catch (cancelled: CancellationException) {
    journal.restore().forEach(cancelled::addSuppressed)
    throw cancelled
  } catch (interrupted: InterruptedException) {
    journal.restore().forEach(interrupted::addSuppressed)
    throw interrupted
  }
