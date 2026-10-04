package skillbill.infrastructure.skills.externaladdon

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.ExternalAddonConfigError
import skillbill.infrastructure.host.readTelemetryConfigFile
import skillbill.infrastructure.host.resolveTelemetryConfigPath
import skillbill.infrastructure.host.writeTelemetryConfigFile
import skillbill.install.model.ExternalAddonSource
import skillbill.model.toPath
import skillbill.ports.install.addon.ExternalAddonSourceConfigPort
import skillbill.ports.install.addon.model.ExternalAddonSourceConfigRequest
import skillbill.ports.install.addon.model.ExternalAddonSourceConfigResult
import skillbill.ports.install.addon.model.ExternalAddonSourceRegistrationRequest
import skillbill.ports.repository.toFileLocation
import skillbill.scaffold.model.SkillKind
import skillbill.telemetry.model.TelemetryConfigDocument
import skillbill.telemetry.model.TelemetryOpenDocument
import java.nio.file.Path

@Inject
class FileExternalAddonSourceConfigStore : ExternalAddonSourceConfigPort {
  override fun readExternalAddonSources(request: ExternalAddonSourceConfigRequest): ExternalAddonSourceConfigResult {
    val (configPath, entries) =
      readExternalAddonSourceEntries(request.environment, request.userHome, PLATFORM_LIST_SHAPE_MESSAGE)
        ?: return ExternalAddonSourceConfigResult()
    val sources = entries.mapIndexedNotNull { index, entry -> parseEntry(configPath, request.userHome, index, entry) }
    return ExternalAddonSourceConfigResult(sources)
  }

  override fun registerExternalAddonSource(
    request: ExternalAddonSourceRegistrationRequest,
  ): ExternalAddonSourceConfigResult {
    val configPath = resolveTelemetryConfigPath(request.environment, request.userHome)
    val existing =
      try {
        readTelemetryConfigFile(configPath)
      } catch (error: IllegalArgumentException) {
        throw ExternalAddonConfigError(error.message.orEmpty(), error)
      }
    val payload = LinkedHashMap<String, Any?>(existing?.payload.orEmpty())
    val rawSources = rawExternalAddonSources(configPath, payload)
    val existingSources =
      rawSources.mapIndexedNotNull { index, entry ->
        parseEntry(configPath, request.userHome, index, entry)
      }
    val registeredSource = request.source.normalized()
    val alreadyRegistered =
      existingSources.any { source ->
        source.path == registeredSource.path && source.platform == registeredSource.platform
      }
    val sources =
      if (alreadyRegistered) {
        existingSources
      } else {
        val updatedRawSources =
          rawSources +
            mapOf(
              "path" to registeredSource.path.toString(),
              "platform" to registeredSource.platform,
            )
        payload["external_addon_sources"] = updatedRawSources
        writeTelemetryConfigFile(configPath, TelemetryConfigDocument(TelemetryOpenDocument.from(payload)))
        existingSources + registeredSource
      }
    return ExternalAddonSourceConfigResult(sources)
  }

  private fun rawExternalAddonSources(
    configPath: Path,
    payload: Map<String, Any?>,
  ): List<Any?> {
    val raw = payload["external_addon_sources"] ?: return emptyList()
    if (raw !is List<*>) {
      throw ExternalAddonConfigError(
        "External addon config at '$configPath': 'external_addon_sources' $PLATFORM_LIST_SHAPE_MESSAGE",
      )
    }
    return raw
  }

  private fun parseEntry(
    configPath: Path,
    userHome: Path,
    index: Int,
    entry: Any?,
  ): ExternalAddonSource? {
    val map = requireExternalAddonEntryMap(configPath, index, entry)
    val kind = (map["kind"] as? String)?.trim()
    if (kind == SkillKind.AGENT_ADDON.wireValue) return null
    validateExternalAddonEntryKind(configPath, index, kind)
    val rawPath = requireExternalAddonEntryPath(configPath, index, map)
    val platform = requireExternalAddonEntryPlatform(configPath, index, map)
    val resolvedPath = resolveSourcePath(userHome, rawPath)
    validateExternalAddonEntryDirectory(configPath, index, rawPath, resolvedPath)
    return ExternalAddonSource(path = resolvedPath.toFileLocation(), platform = platform.trim())
  }

  private fun ExternalAddonSource.normalized(): ExternalAddonSource =
    ExternalAddonSource(path = path.toPath().toAbsolutePath().normalize().toFileLocation(), platform = platform.trim())

  private companion object {
    const val PLATFORM_LIST_SHAPE_MESSAGE = "must be a list of {path, platform} entries."
  }
}
