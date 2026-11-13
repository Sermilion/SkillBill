package skillbill.infrastructure.skills.skillremove

import skillbill.infrastructure.host.jvm.resolveEnvironmentMap
import skillbill.infrastructure.skills.install.nativeagent.inventory.NativeAgentLinkInventory
import skillbill.infrastructure.skills.install.nativeagent.inventory.NativeAgentLinkInventoryEntry
import skillbill.infrastructure.skills.install.nativeagent.inventory.NativeAgentLinkInventoryPaths
import skillbill.infrastructure.skills.nativeagent.composition.parseNativeAgentSourceFile
import skillbill.infrastructure.skills.nativeagent.discovery.discoverNativeAgentSources
import skillbill.install.model.SupportedAgent
import skillbill.skillremove.model.AgentSymlinkUnlink
import skillbill.skillremove.model.SkillRemovalRequest
import java.nio.file.Files
import java.nio.file.Path

internal fun SkillRemoveJvmFileSystemPlanning.agentUnlinksForSkills(
  request: SkillRemovalRequest,
  cascadedSkillNames: List<String>,
): List<AgentSymlinkUnlink> {
  val resolvedHome = skillRemoveUserHome(request, home)
  val environment = resolveEnvironmentMap(request.environment)
  val repoRoot = skillRemoveRepoRoot(request)
  val roots = cascadedSkillNames.map { repoRoot.resolve("skills").resolve(it).toAbsolutePath().normalize() }
  val names =
    discoverNativeAgentSources(repoRoot.resolve("platform-packs"), repoRoot.resolve("skills"))
      .filter { source -> roots.any(source.toAbsolutePath().normalize()::startsWith) }
      .flatMap(::parseNativeAgentSourceFile)
      .map { it.name }
      .toSet()
  return inventoryUnlinks(resolvedHome, environment, names, repoRoot)
}

internal fun SkillRemoveJvmFileSystemPlanning.agentUnlinksForPlatform(
  request: SkillRemovalRequest,
  platform: String,
): List<AgentSymlinkUnlink> {
  val resolvedHome = skillRemoveUserHome(request, home)
  val environment = resolveEnvironmentMap(request.environment)
  val repoRoot = skillRemoveRepoRoot(request)
  val packRoot = repoRoot.resolve("platform-packs").resolve(platform).toAbsolutePath().normalize()
  val names =
    discoverNativeAgentSources(repoRoot.resolve("platform-packs"), null, listOf(platform))
      .filter { it.toAbsolutePath().normalize().startsWith(packRoot) }
      .flatMap(::parseNativeAgentSourceFile)
      .map { it.name }
      .toSet()
  return inventoryUnlinks(resolvedHome, environment, names, repoRoot)
}

private fun inventoryUnlinks(
  home: Path,
  environment: Map<String, String>,
  names: Set<String>,
  sourceRoot: Path,
): List<AgentSymlinkUnlink> {
  if (names.isEmpty()) return emptyList()
  val path = NativeAgentLinkInventoryPaths.inventoryPath(home)
  if (!NativeAgentLinkInventory.inventoryPathExists(path)) return emptyList()
  val entries = NativeAgentLinkInventory.read(home, emptyList(), environment = environment)
  return entries.filter { entry ->
    entry.logicalName in names && entry.sourceRoot == sourceRoot.toAbsolutePath().normalize() &&
      entry.installedPath.toAbsolutePath().normalize().parent in
      NativeAgentLinkInventoryPaths.provider(entry.provider).homeAgentDirs(home, environment)
        .map { it.toAbsolutePath().normalize() } &&
      isRecordedTarget(entry)
  }.map { entry ->
    AgentSymlinkUnlink(
      provider = SupportedAgent.fromWire(entry.provider),
      path = entry.installedPath.toString().replace('\\', '/'),
    )
  }
}

private fun isRecordedTarget(entry: NativeAgentLinkInventoryEntry): Boolean {
  val installed = entry.installedPath.toAbsolutePath().normalize()
  if (!Files.isSymbolicLink(installed)) return false
  val raw = Files.readSymbolicLink(installed)
  return installed.parent.resolve(raw).toAbsolutePath().normalize() ==
    entry.cacheTargetPath.toAbsolutePath().normalize()
}
