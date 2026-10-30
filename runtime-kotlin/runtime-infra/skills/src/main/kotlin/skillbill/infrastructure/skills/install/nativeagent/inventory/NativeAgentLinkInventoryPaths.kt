package skillbill.infrastructure.skills.install.nativeagent.inventory

import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import skillbill.infrastructure.skills.nativeagent.support.agentDirectoryEntries
import skillbill.infrastructure.skills.nativeagent.support.agentObservedAttributes
import skillbill.install.model.SupportedAgent
import java.nio.file.Path

internal object NativeAgentLinkInventoryLimits {
  const val MAX_BYTES = 1024 * 1024L
  const val DIGEST_HEX_LENGTH = 64
  const val MAX_SOURCE_ROOT_LENGTH = 4096
  val LOGICAL_NAME = Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")
  val CACHE_GENERATION = Regex("(?:[a-z0-9](?:[a-z0-9-]{0,31})-)?[0-9a-f]{16}")
  val PROVIDERS = SupportedAgent.entries.map(SupportedAgent::wireValue).toSet()
}

internal object NativeAgentLinkInventoryPaths {
  fun canonicalManagedCacheRoots(
    home: Path,
    managedRoots: List<Path>,
  ): List<Path> {
    val normalizedHome = home.toAbsolutePath().normalize()
    val installedGenerations =
      generationChildren(
        normalizedHome.resolve(".skill-bill/installed-skills"),
        prefix = "native-agents-",
      )
    val legacyGenerations = generationChildren(normalizedHome.resolve(".skill-bill/native-agents"))
    return (managedRoots.map { it.toAbsolutePath().normalize() } + installedGenerations + legacyGenerations).distinct()
  }

  fun inventoryPath(home: Path): Path =
    home.resolve(".skill-bill/native-agent-link-inventory.json")
      .toAbsolutePath().normalize()

  fun provider(id: String): NativeAgentProvider = NativeAgentProvider.forSupportedAgent(SupportedAgent.fromWire(id))

  private fun generationChildren(
    parent: Path,
    prefix: String = "",
  ): List<Path> {
    val candidates =
      agentDirectoryEntries(
        parent,
        "list native-agent cache generations",
        followLinks = false,
      ).filter { child ->
        child.fileName.toString().startsWith(prefix) &&
          NativeAgentLinkInventoryLimits.CACHE_GENERATION.matches(child.fileName.toString().removePrefix(prefix))
      }
    return candidates.mapNotNull { child ->
      val attributes = agentObservedAttributes(child, "inspect native-agent cache generation", followLinks = false)
      if (attributes.isDirectory) child.toAbsolutePath().normalize() else null
    }
  }
}
