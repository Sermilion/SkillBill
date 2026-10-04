package skillbill.infrastructure.skills.externaladdon

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.ExternalAddonConfigError
import skillbill.install.model.ExternalAgentAddonSource
import skillbill.ports.agentaddon.ExternalAgentAddonSourceConfigPort
import skillbill.ports.agentaddon.model.ExternalAgentAddonSourceConfigRequest
import skillbill.ports.agentaddon.model.ExternalAgentAddonSourceConfigResult
import skillbill.ports.repository.toFileLocation
import skillbill.scaffold.model.SkillKind
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileExternalAgentAddonSourceConfigStore : ExternalAgentAddonSourceConfigPort {
  override fun readExternalAgentAddonSources(
    request: ExternalAgentAddonSourceConfigRequest,
  ): ExternalAgentAddonSourceConfigResult {
    val (configPath, entries) =
      readExternalAddonSourceEntries(request.environment, request.userHome, "must be a list of source entries.")
        ?: return ExternalAgentAddonSourceConfigResult()
    val sources = entries.mapIndexedNotNull { index, entry -> parseEntry(configPath, request.userHome, index, entry) }
    return ExternalAgentAddonSourceConfigResult(sources)
  }

  private fun parseEntry(
    configPath: Path,
    userHome: Path,
    index: Int,
    entry: Any?,
  ): ExternalAgentAddonSource? {
    val map =
      entry as? Map<*, *>
        ?: invalidConfig(configPath, "$CONFIG_KEY[$index]", "must be a mapping")
    val kind = (map["kind"] as? String)?.trim()
    if (kind == null && map.containsKey("platform")) return null
    if (kind == SkillKind.PLATFORM_PACK.wireValue) return null
    if (kind != SkillKind.AGENT_ADDON.wireValue) {
      invalidConfig(
        configPath,
        "$CONFIG_KEY[$index].kind",
        "must be 'agent-addon' for agent add-on sources",
      )
    }
    val rawPath =
      (map["path"] as? String)?.takeIf(String::isNotBlank)
        ?: invalidConfig(configPath, "$CONFIG_KEY[$index].path", "must be a non-empty string")
    val resolvedPath = resolveSourcePath(userHome, rawPath)
    if (!Files.isDirectory(resolvedPath)) {
      invalidConfig(
        configPath,
        "$CONFIG_KEY[$index].path",
        "'$rawPath' does not exist or is not a directory",
      )
    }
    return ExternalAgentAddonSource(resolvedPath.toFileLocation())
  }

  private companion object {
    const val CONFIG_KEY = "external_addon_sources"
  }
}

private fun invalidConfig(
  configPath: Path,
  field: String,
  reason: String,
): Nothing {
  throw ExternalAddonConfigError("External agent add-on config at '$configPath': '$field' $reason.")
}
