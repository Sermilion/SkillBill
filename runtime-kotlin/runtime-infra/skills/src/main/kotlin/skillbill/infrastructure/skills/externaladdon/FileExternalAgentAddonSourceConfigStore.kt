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
        ?: invalidConfig(configPath, "${ExternalAddonConfigKeys.SOURCES}[$index]", "must be a mapping")
    val kind = (map[ExternalAddonConfigKeys.KIND] as? String)?.trim()
    if (kind == null && map.containsKey(ExternalAddonConfigKeys.PLATFORM)) return null
    if (kind == SkillKind.PLATFORM_PACK.wireValue) return null
    if (kind != SkillKind.AGENT_ADDON.wireValue) {
      invalidConfig(
        configPath,
        "${ExternalAddonConfigKeys.SOURCES}[$index].${ExternalAddonConfigKeys.KIND}",
        "must be 'agent-addon' for agent add-on sources",
      )
    }
    val rawPath =
      (map[ExternalAddonConfigKeys.PATH] as? String)?.takeIf(String::isNotBlank)
        ?: invalidConfig(
          configPath,
          "${ExternalAddonConfigKeys.SOURCES}[$index].${ExternalAddonConfigKeys.PATH}",
          "must be a non-empty string",
        )
    val resolvedPath = resolveSourcePath(userHome, rawPath)
    if (!Files.isDirectory(resolvedPath)) {
      invalidConfig(
        configPath,
        "${ExternalAddonConfigKeys.SOURCES}[$index].${ExternalAddonConfigKeys.PATH}",
        "'$rawPath' does not exist or is not a directory",
      )
    }
    return ExternalAgentAddonSource(resolvedPath.toFileLocation())
  }
}

private fun invalidConfig(
  configPath: Path,
  field: String,
  reason: String,
): Nothing {
  throw ExternalAddonConfigError("External agent add-on config at '$configPath': '$field' $reason.")
}
