package skillbill.infrastructure.skills.install.nativeagent.link

import skillbill.infrastructure.skills.install.nativeagent.InstallNativeAgentResult
import skillbill.infrastructure.skills.install.nativeagent.inventory.NativeAgentLinkInventory
import skillbill.infrastructure.skills.install.nativeagent.inventory.NativeAgentLinkInventoryEntry
import skillbill.infrastructure.skills.install.nativeagent.inventory.NativeAgentLinkInventoryReconcileRequest
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentInstallRenderOverrides
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentInstallRenderRequest
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentInstallRenderResult
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentOperations
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackCatalogLoader
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackDiscoveryContext
import skillbill.infrastructure.skills.scaffold.platformpack.loader.childDirectories
import skillbill.install.model.AgentTarget
import skillbill.model.toPath
import java.nio.file.Files
import java.nio.file.Path

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
  val linkResults = linkGeneratedNativeAgentFiles(args, generated, managedRoots)
  if (linkResults.failure != null) {
    return NativeAgentLinkOutcome(linkResults.linked, linkResults.skipped, linkResults.failure)
  }
  val desired =
    desiredNativeAgentInventory(
      provider = args.provider,
      targets = args.targets,
      generated = generated,
      linked = linkResults.linked,
      validationRoot = args.validationRoot,
    )
  desired.forEach(::verifyInstalledNativeAgent)
  publishInstalledReviewCatalog(
    args.request.platformPacksRoot,
    args.request.selectedPlatforms,
    generated.cacheRoot,
    args.journal,
    args.effectivePackRoots,
  )
  NativeAgentLinkInventory.reconcile(
    NativeAgentLinkInventoryReconcileRequest(
      home = args.resolvedHome,
      provider = args.provider.name.lowercase(),
      desired = desired,
      managedRoots = managedRoots,
      sourceRoot = args.validationRoot,
      beforeMutation = args.journal::beforeMutation,
      afterTemporaryCreation = args.journal::afterTemporaryCreation,
    ),
  )
  return NativeAgentLinkOutcome(linkResults.linked, linkResults.skipped)
}

private data class NativeAgentFileLinkResults(
  val linked: List<Path>,
  val skipped: List<NativeAgentSkippedLink>,
  val failure: NativeAgentLinkFailure? = null,
)

private fun linkGeneratedNativeAgentFiles(
  args: NativeAgentLinkProviderBodyArgs,
  generated: NativeAgentInstallRenderResult,
  managedRoots: List<Path>,
): NativeAgentFileLinkResults {
  val linked = mutableListOf<Path>()
  val skipped = mutableListOf<NativeAgentSkippedLink>()
  val artifactsByPath = generated.artifacts.associateBy { it.path }
  for (target in args.targets) {
    for (file in generated.generatedFiles) {
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
        is InstallNativeAgentResult.Failed ->
          return NativeAgentFileLinkResults(
            linked,
            skipped,
            NativeAgentLinkFailure(result.failedPath, result.error),
          )
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
): NativeAgentLinkOutcome {
  val result = runCatching(block)
  result.exceptionOrNull()?.let { error ->
    journal.restore().forEach(error::addSuppressed)
  }
  val outcome = result.getOrThrow()
  outcome.failure?.error?.let { error ->
    journal.restore().forEach(error::addSuppressed)
  }
  return outcome
}
