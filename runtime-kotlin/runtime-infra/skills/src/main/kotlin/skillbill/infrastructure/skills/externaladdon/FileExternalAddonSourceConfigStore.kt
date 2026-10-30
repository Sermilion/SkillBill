package skillbill.infrastructure.skills.externaladdon

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.ExternalAddonConfigError
import skillbill.infrastructure.host.readTelemetryConfigFileRead
import skillbill.infrastructure.host.resolveTelemetryConfigLockPaths
import skillbill.infrastructure.host.resolveTelemetryConfigPath
import skillbill.infrastructure.host.system.FileConfigDocumentLocks
import skillbill.infrastructure.host.telemetryConfigFingerprintAt
import skillbill.infrastructure.host.writeTelemetryConfigFile
import skillbill.install.model.ExternalAddonSource
import skillbill.model.toPath
import skillbill.ports.config.model.ConfigDocumentReplaceResult
import skillbill.ports.install.addon.ExternalAddonSourceConfigPort
import skillbill.ports.install.addon.model.ExternalAddonSourceConfigRequest
import skillbill.ports.install.addon.model.ExternalAddonSourceConfigResult
import skillbill.ports.install.addon.model.ExternalAddonSourceRegistrationRequest
import skillbill.ports.repository.toFileLocation
import skillbill.ports.telemetry.model.TelemetryConfigRead
import skillbill.scaffold.model.SkillKind
import skillbill.telemetry.model.TelemetryConfigDocument
import skillbill.telemetry.model.TelemetryOpenDocument
import java.nio.file.Path

@Inject
class FileExternalAddonSourceConfigStore : ExternalAddonSourceConfigPort {
  override fun readExternalAddonSources(request: ExternalAddonSourceConfigRequest): ExternalAddonSourceConfigResult =
    FileConfigDocumentLocks.withLocks(resolveTelemetryConfigLockPaths(request.environment, request.userHome)) {
      val (configPath, entries) =
        readExternalAddonSourceEntries(request.environment, request.userHome, PLATFORM_LIST_SHAPE_MESSAGE)
          ?: return@withLocks ExternalAddonSourceConfigResult()
      val sources = entries.mapIndexedNotNull { index, entry -> parseEntry(configPath, request.userHome, index, entry) }
      ExternalAddonSourceConfigResult(sources)
    }

  override fun registerExternalAddonSource(
    request: ExternalAddonSourceRegistrationRequest,
  ): ExternalAddonSourceConfigResult =
    FileConfigDocumentLocks.withLocks(
      resolveTelemetryConfigLockPaths(request.environment, request.userHome),
    ) {
      val configPath = resolveTelemetryConfigPath(request.environment, request.userHome)
      val expected = telemetryConfigFingerprintAt(configPath)
      val existing =
        when (val read = readTelemetryConfigFileRead(configPath)) {
          TelemetryConfigRead.Absent -> null
          is TelemetryConfigRead.Malformed -> throw ExternalAddonConfigError(read.reason)
          is TelemetryConfigRead.Present -> read.document
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
                ExternalAddonConfigKeys.PATH to registeredSource.path.toString(),
                ExternalAddonConfigKeys.PLATFORM to registeredSource.platform,
              )
          payload[ExternalAddonConfigKeys.SOURCES] = updatedRawSources
          val result =
            writeTelemetryConfigFile(
              configPath,
              TelemetryConfigDocument(TelemetryOpenDocument.from(payload)),
              expected,
            )
          if (result is ConfigDocumentReplaceResult.ChangedOnDisk) {
            throw ExternalAddonConfigError("External addon config at '$configPath' changed while it was being updated.")
          }
          existingSources + registeredSource
        }
      ExternalAddonSourceConfigResult(sources)
    }

  private fun rawExternalAddonSources(
    configPath: Path,
    payload: Map<String, Any?>,
  ): List<Any?> {
    val raw = payload[ExternalAddonConfigKeys.SOURCES] ?: return emptyList()
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
    val kind = (map[ExternalAddonConfigKeys.KIND] as? String)?.trim()
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
