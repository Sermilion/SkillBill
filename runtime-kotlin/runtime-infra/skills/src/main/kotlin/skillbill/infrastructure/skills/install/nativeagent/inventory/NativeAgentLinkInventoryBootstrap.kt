package skillbill.infrastructure.skills.install.nativeagent.inventory

import skillbill.error.core.invalidNativeAgentLinkInventoryDecode
import skillbill.infrastructure.contracts.sha256Hex
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal object NativeAgentLinkInventoryBootstrap {
  data class BootstrapPlan(
    val retain: List<NativeAgentLinkInventoryEntry>,
    val remove: List<NativeAgentLinkInventoryEntry>,
  )

  private data class ProviderBootstrapResult(
    val retain: List<NativeAgentLinkInventoryEntry>,
    val remove: List<NativeAgentLinkInventoryEntry>,
  )

  private data class LinkInspectionContext(
    val provider: NativeAgentProvider,
    val home: Path,
    val managedRoots: List<Path>,
    val sourceRoot: Path,
    val retain: MutableList<NativeAgentLinkInventoryEntry>,
    val remove: MutableList<NativeAgentLinkInventoryEntry>,
  )

  fun bootstrap(
    home: Path,
    managedRoots: List<Path>,
    sourceRoot: Path,
  ): BootstrapPlan {
    val results =
      NativeAgentProvider.entries.map { provider ->
        bootstrapProvider(provider, home, managedRoots, sourceRoot)
      }
    return BootstrapPlan(
      retain = results.flatMap { it.retain },
      remove = results.flatMap { it.remove },
    )
  }

  private fun bootstrapProvider(
    provider: NativeAgentProvider,
    home: Path,
    managedRoots: List<Path>,
    sourceRoot: Path,
  ): ProviderBootstrapResult {
    val retain = mutableListOf<NativeAgentLinkInventoryEntry>()
    val remove = mutableListOf<NativeAgentLinkInventoryEntry>()
    val sourceRootPath = sourceRoot.toAbsolutePath().normalize()
    val context = LinkInspectionContext(provider, home, managedRoots, sourceRootPath, retain, remove)
    provider.homeAgentDirs(home)
      .filter(Files::isDirectory)
      .forEach { directory ->
        Files.list(directory).use { paths ->
          paths.filter(Files::isSymbolicLink).forEach { link ->
            inspectLink(link, context)
          }
        }
      }
    return ProviderBootstrapResult(retain, remove)
  }

  private fun inspectLink(
    link: Path,
    context: LinkInspectionContext,
  ) {
    val raw = readManagedLinkTarget(link)
    val resolved = link.parent.resolve(raw).toAbsolutePath().normalize()
    val logicalName = link.fileName.toString().removeSuffix(".${context.provider.extension}")
    if (
      !NativeAgentLinkInventoryLimits.LOGICAL_NAME.matches(logicalName) ||
      link.fileName.toString() != context.provider.fileName(logicalName)
    ) {
      return
    }
    if (
      !isCanonicalNativeAgentArtifactTarget(
        context.home,
        context.provider,
        logicalName,
        resolved,
        context.managedRoots,
      )
    ) {
      return
    }
    val digest = digestForBootstrapEntry(resolved)
    if (digest == null) {
      context.remove +=
        NativeAgentLinkInventoryEntry(
          logicalName = logicalName,
          provider = context.provider.name.lowercase(),
          installedPath = link.toAbsolutePath().normalize(),
          cacheTargetPath = resolved,
          contentDigest = "",
          sourceRoot = context.sourceRoot,
        )
      return
    }
    val entry =
      NativeAgentLinkInventoryEntry(
        logicalName = logicalName,
        provider = context.provider.name.lowercase(),
        installedPath = link.toAbsolutePath().normalize(),
        cacheTargetPath = resolved,
        contentDigest = digest,
        sourceRoot = context.sourceRoot,
      )
    if (NativeAgentLinkInventoryDecode.isSemanticallyValid(entry, context.home, context.managedRoots)) {
      context.retain += entry
    } else {
      context.remove += entry
    }
  }

  private fun readManagedLinkTarget(link: Path): Path {
    return try {
      Files.readSymbolicLink(link)
    } catch (error: IOException) {
      throw invalidNativeAgentLinkInventoryDecode(
        path = link.toString(),
        reason = "managed link target could not be read",
        cause = error,
      )
    }
  }

  private fun digestForBootstrapEntry(resolved: Path): String? {
    if (!Files.isRegularFile(resolved) || !Files.isReadable(resolved)) {
      return null
    }
    return try {
      sha256Hex(Files.readAllBytes(resolved))
    } catch (error: IOException) {
      throw invalidNativeAgentLinkInventoryDecode(
        path = resolved.toString(),
        reason = "linked artifact is not hashable: ${error.message.orEmpty()}",
        cause = error,
      )
    }
  }

  fun removeIfStillManaged(
    entry: NativeAgentLinkInventoryEntry,
    home: Path,
    managedRoots: List<Path>,
    beforeMutation: (Path) -> Unit,
  ) {
    val link = entry.installedPath
    val provider = NativeAgentLinkInventoryPaths.provider(entry.provider)
    if (link.fileName.toString() != provider.fileName(entry.logicalName)) return
    if (!Files.isSymbolicLink(link)) return
    val rawTarget = readManagedLinkTarget(link)
    val resolved = (link.parent ?: link.toAbsolutePath().parent).resolve(rawTarget).toAbsolutePath().normalize()
    if (isCanonicalNativeAgentArtifactTarget(home, provider, entry.logicalName, resolved, managedRoots)) {
      beforeMutation(link)
      Files.deleteIfExists(link)
    }
  }
}
