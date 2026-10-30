package skillbill.infrastructure.skills.install.nativeagent.inventory

import skillbill.error.core.InvalidNativeAgentLinkInventoryDecodeError
import skillbill.infrastructure.contracts.sha256Hex
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import skillbill.infrastructure.skills.nativeagent.support.agentDirectoryEntries
import skillbill.infrastructure.skills.nativeagent.support.agentObservedAttributes
import skillbill.infrastructure.skills.nativeagent.support.agentPathIsSymbolicLink
import skillbill.infrastructure.skills.nativeagent.support.rethrowAgentOperationInterruption
import skillbill.infrastructure.skills.nativeagent.support.verifyObservedDirectory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

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
    environment: Map<String, String>,
  ): BootstrapPlan {
    val results =
      NativeAgentProvider.entries.map { provider ->
        bootstrapProvider(provider, home, managedRoots, sourceRoot, environment)
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
    environment: Map<String, String>,
  ): ProviderBootstrapResult {
    val retain = mutableListOf<NativeAgentLinkInventoryEntry>()
    val remove = mutableListOf<NativeAgentLinkInventoryEntry>()
    val sourceRootPath = sourceRoot.toAbsolutePath().normalize()
    val context = LinkInspectionContext(provider, home, managedRoots, sourceRootPath, retain, remove)
    provider.inventoryHomeAgentDirs(home, environment)
      .forEach { target ->
        target.observedParent?.let { parent ->
          verifyObservedDirectory(parent, "inspect ${provider.name} native-agent profile root")
        }
        val operation = "inspect ${provider.name} native-agent link"
        val links =
          agentDirectoryEntries(target.path, "list ${provider.name} native-agent directory")
            .mapNotNull { link ->
              val attributes = agentObservedAttributes(link, operation, followLinks = false)
              if (attributes.isSymbolicLink) link else null
            }
        links.forEach { link -> inspectLink(link, context, environment) }
        target.observedParent?.let { parent ->
          verifyObservedDirectory(parent, "inspect ${provider.name} native-agent profile root")
        }
      }
    return ProviderBootstrapResult(retain, remove)
  }

  private fun inspectLink(
    link: Path,
    context: LinkInspectionContext,
    environment: Map<String, String>,
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
    if (
      NativeAgentLinkInventoryDecode.isBootstrapSemanticallyValid(
        entry,
        context.home,
        context.managedRoots,
        environment,
      )
    ) {
      context.retain += entry
    } else {
      context.remove += entry
    }
  }

  private fun readManagedLinkTarget(link: Path): Path {
    return try {
      Files.readSymbolicLink(link)
    } catch (error: IOException) {
      rethrowAgentOperationInterruption(error)
      throw InvalidNativeAgentLinkInventoryDecodeError(
        path = link.toString(),
        reason = "managed link target could not be read",
        cause = error,
      )
    } catch (error: SecurityException) {
      throw InvalidNativeAgentLinkInventoryDecodeError(
        path = link.toString(),
        reason = "managed link target could not be read",
        cause = error,
      )
    }
  }

  private fun bootstrapAttributes(resolved: Path): BasicFileAttributes? =
    try {
      Files.readAttributes(resolved, BasicFileAttributes::class.java)
    } catch (error: NoSuchFileException) {
      rethrowAgentOperationInterruption(error)
      null
    } catch (error: IOException) {
      rethrowAgentOperationInterruption(error)
      throw InvalidNativeAgentLinkInventoryDecodeError(
        path = resolved.toString(),
        reason = "linked artifact cannot be inspected",
        cause = error,
      )
    } catch (error: SecurityException) {
      throw InvalidNativeAgentLinkInventoryDecodeError(
        path = resolved.toString(),
        reason = "linked artifact cannot be inspected",
        cause = error,
      )
    }

  private fun digestForBootstrapEntry(resolved: Path): String? {
    val attributes = bootstrapAttributes(resolved) ?: return null
    if (!attributes.isRegularFile) {
      return null
    }
    return try {
      sha256Hex(Files.readAllBytes(resolved))
    } catch (error: NoSuchFileException) {
      rethrowAgentOperationInterruption(error)
      throw InvalidNativeAgentLinkInventoryDecodeError(
        path = resolved.toString(),
        reason = "linked artifact disappeared after inspection",
        cause = error,
      )
    } catch (error: IOException) {
      rethrowAgentOperationInterruption(error)
      failUnhashableArtifact(resolved, error)
    } catch (error: SecurityException) {
      failUnhashableArtifact(resolved, error)
    }
  }

  private fun failUnhashableArtifact(
    path: Path,
    error: Exception,
  ): Nothing =
    throw InvalidNativeAgentLinkInventoryDecodeError(
      path = path.toString(),
      reason = "linked artifact is not hashable: ${error.message.orEmpty()}",
      cause = error,
    )

  fun removeIfStillManaged(
    entry: NativeAgentLinkInventoryEntry,
    home: Path,
    managedRoots: List<Path>,
    beforeMutation: (Path) -> Unit,
  ) {
    val link = entry.installedPath
    val provider = NativeAgentLinkInventoryPaths.provider(entry.provider)
    if (link.fileName.toString() != provider.fileName(entry.logicalName)) return
    if (!agentPathIsSymbolicLink(link, "inspect managed ${provider.name} agent link")) return
    val rawTarget = readManagedLinkTarget(link)
    val resolved = (link.parent ?: link.toAbsolutePath().parent).resolve(rawTarget).toAbsolutePath().normalize()
    if (isCanonicalNativeAgentArtifactTarget(home, provider, entry.logicalName, resolved, managedRoots)) {
      beforeMutation(link)
      Files.deleteIfExists(link)
    }
  }
}
