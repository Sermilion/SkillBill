package skillbill.infrastructure.launcher

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import me.tatarka.inject.annotations.Inject
import skillbill.error.shellcontent.missingInstalledNativeAgent
import skillbill.infrastructure.skills.install.nativeagent.inventory.NativeAgentLinkInventory
import skillbill.infrastructure.skills.install.nativeagent.inventory.NativeAgentLinkInventoryEntry
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import skillbill.install.model.SupportedAgent
import skillbill.model.EnvironmentContext
import skillbill.ports.review.launch.ReviewLaunchAgentStagingPort
import skillbill.ports.review.model.ReviewLaunchAgentStagingRequest

@Inject
class FileSystemReviewLaunchAgentStaging(
  private val environment: EnvironmentContext,
) : ReviewLaunchAgentStagingPort {
  override fun stage(request: ReviewLaunchAgentStagingRequest) {
    if (request.logicalWorkerNames.isEmpty()) return
    val provider =
      provider(request.agentId)
        ?: throw missingInstalledNativeAgent(
          request.logicalWorkerNames.first(),
          request.agentId,
          environment.userHome.toString(),
          "provider does not support native-agent staging",
          REPAIR_COMMAND,
        )
    val inventory = NativeAgentLinkInventory.read(environment.userHome, emptyList())
    request.logicalWorkerNames.distinct().forEach { logicalName ->
      val entry = inventoryEntry(inventory, provider, logicalName)
      copyArtifact(entry, provider, request.reviewLaunchDirectory)
    }
  }

  private fun inventoryEntry(
    inventory: List<NativeAgentLinkInventoryEntry>,
    provider: NativeAgentProvider,
    logicalName: String,
  ): NativeAgentLinkInventoryEntry {
    val entries =
      inventory.filter {
        it.provider == provider.name.lowercase() && it.logicalName == logicalName
      }
    if (entries.isEmpty()) {
      fail(
        logicalName,
        provider,
        provider.homeAgentDirs(environment.userHome).first().resolve(provider.fileName(logicalName)),
        "managed inventory entry is missing",
      )
    }
    val cachePath = entries.first().cacheTargetPath
    if (!Files.isReadable(cachePath)) {
      fail(logicalName, provider, cachePath, "managed cache artifact is missing or unreadable")
    }
    return entries.first()
  }

  private fun copyArtifact(
    entry: NativeAgentLinkInventoryEntry,
    provider: NativeAgentProvider,
    reviewLaunchDirectory: Path,
  ) {
    val destinationDir =
      reviewLaunchDirectory
        .resolve(requireNotNull(provider.supportedAgent.simpleHomeDirectory))
        .resolve("agents")
    Files.createDirectories(destinationDir)
    val destination = destinationDir.resolve(provider.fileName(entry.logicalName))
    try {
      Files.copy(entry.cacheTargetPath, destination, StandardCopyOption.REPLACE_EXISTING)
    } catch (error: IOException) {
      fail(entry.logicalName, provider, destination, "failed to copy managed cache artifact", error)
    }
  }

  private fun provider(agentId: String): NativeAgentProvider? =
    runCatching {
      SupportedAgent.fromWire(agentId)
        .takeIf { agent -> agent == SupportedAgent.CURSOR }
        ?.let(NativeAgentProvider::forSupportedAgent)
    }.getOrNull()

  private fun fail(
    logicalName: String,
    provider: NativeAgentProvider,
    path: Path,
    reason: String,
    cause: Throwable? = null,
  ): Nothing =
    throw missingInstalledNativeAgent(
      logicalName,
      provider.name.lowercase(),
      path.toString(),
      reason,
      REPAIR_COMMAND,
      cause,
    )

  private companion object {
    const val REPAIR_COMMAND = "skill-bill install apply"
  }
}
